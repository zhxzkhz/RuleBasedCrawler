package com.zhhz.spider.util

import cn.hutool.core.codec.Base64
import cn.hutool.crypto.SecureUtil
import cn.hutool.crypto.digest.HMac
import cn.hutool.crypto.digest.HmacAlgorithm
import cn.hutool.crypto.symmetric.SymmetricCrypto
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.TypeReference
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


private val logger = KotlinLogging.logger {}

// 专用调度器：限制 JS 脚本发起的 HTTP 并发数，防止阻塞通用 IO 线程池
private val jsHttpDispatcher = Dispatchers.IO.limitedParallelism(4)

// 定时线程池：替代每次 timeout 调用都创建新线程的做法，限制最大并发为 4
private val timeoutScheduler = Executors.newScheduledThreadPool(4) { r ->
    Thread(r, "js-timeout-worker").apply { isDaemon = true }
}

object JsExtensionClass {

    /**
     * 脚本日志助手：替代直接操作 java_log(KLogger)。
     * 💡 不要把 java_log 本身当值参与运算或作为参数传递——Rhino 无法把它转换为
     * 字符串等目标类型，会抛 "Invalid JavaScript value of type ...KLogger"。
     */
    fun log(message: String?) {
        logger.info { message }
    }

    fun logError(message: String?) {
        logger.error { message }
    }

    // 💡 由网络层(HttpFetcher)注入的共享客户端：带全局 CookieJar、连接池等，
    // 脚本通过无 client 参数的 ajax() 发请求时自动继承当前规则已积累的同域名 Cookie
    @Volatile
    private var sharedClient: OkHttpClient? = null

    fun attachSharedClient(client: OkHttpClient) {
        sharedClient = client
    }

    /**
     * 💡 动态 Header 签名钩子：由网络层在执行规则脚本前挂载。
     * 脚本内 java.ajax 发请求时会自动调用它为该 URL 生成签名头
     * （即基础信息里全局配置的 headerScript 产物），无需脚本手动处理。
     *
     * ⚠️ 使用 ThreadLocal 而非全局单例：挂载、脚本求值、卸载发生在同一线程的
     * 同步代码段内；不同书源的分类脚本并发执行时各自持有各自的签名器，
     * 彻底避免"拿到其他请求/规则的签名头"导致解密失败。
     */
    private val threadSigner = ThreadLocal<((url: String) -> Map<String, String>)?>()

    fun attachHeaderSigner(signer: ((String) -> Map<String, String>)?) {
        threadSigner.set(signer)
    }

    private fun currentSigner(): ((String) -> Map<String, String>)? = threadSigner.get()

    /**
     * 💡 响应处理钩子：由网络层在执行规则脚本前挂载（即基础信息里全局配置的
     * responseScript 产物）。脚本拿到 ajax 原始响应后，调用 processResponse
     * 即可获得与管线请求一致的解密/清洗结果。
     */
    // 响应处理器同样按线程隔离，原因同签名钩子；headers 参数为发送时的头快照
    private val threadProcessor = ThreadLocal<((String, String, Map<String, String>?) -> String)?>()

    fun attachResponseProcessor(processor: ((String, String, Map<String, String>?) -> String)?) {
        threadProcessor.set(processor)
    }

    private fun currentProcessor(): ((String, String, Map<String, String>?) -> String)? = threadProcessor.get()

    /**
     * 对 ajax 拿到的原始响应应用当前规则的 responseScript（解密/清洗）。
     * 可选传入本次请求的 url，供 responseScript 内的 java_request/java_url 引用。
     * 未挂载处理器或执行失败时原样返回。
     */
    fun processResponse(raw: String): String = processResponse(raw, "")

    fun processResponse(raw: String, url: String): String {
        val processor = currentProcessor() ?: return raw
        // 💡 弹出与该 url 配对的发送时头快照，responseScript 拿到的 java_request.headers
        // 与实际请求完全一致（时间戳不漂移），并发/交错请求也不会串
        val requestHeaders = popSnapshot(url)
        return runCatching { processor(raw, url, requestHeaders) }.getOrElse {
            logger.error(it) { "processResponse 执行失败" }
            raw
        }
    }

    /**
     * 💡 原子组合调用：签名请求 + 响应解密一步完成。
     * 等价于 ajax(...) 后立刻 processResponse(text, url)，但不存在任何时间窗口，
     * 并发/交错场景下签名头与响应严格配对，推荐优先使用。
     */
    fun signedAjax(url: String): String? = signedAjax(url, "GET", "application/json", "{}", "")

    fun signedAjax(url: String, method: String): String? =
        signedAjax(url, method, "application/json", "{}", "")

    fun signedAjax(url: String, method: String, mediaType: String): String? =
        signedAjax(url, method, mediaType, "{}", "")

    fun signedAjax(
        url: String,
        method: String,
        mediaType: String,
        header: String,
        data: String
    ): String? {
        val raw = ajax(url, method, mediaType, header, data) ?: return null
        return processResponse(raw, url)
    }

    /**
     * 脚本便捷入口：无需传 OkHttpClient，自动使用共享客户端。
     * Cookie 语义：按目标 host 继承（当前规则此前经管线请求/登录积累的 Cookie 会自动携带）。
     *
     * 💡 Rhino 按参数个数精确匹配方法，Kotlin 默认参数对脚本不可见，
     * 因此这里显式提供 1/2/3 参重载，避免 "Can't find method" 错误。
     *
     * ── 两个系列 ──
     * ajax(...)    ：自动附加当前规则的动态签名头（headerScript 产物），适合请求站点业务接口
     * rawAjax(...) ：完全裸请求，只带脚本显式传入的 header（Cookie 仍按域继承），适合第三方接口
     */
    fun ajax(url: String): String? = ajax(url, "GET", "application/json", "{}", "")

    fun ajax(url: String, method: String): String? = ajax(url, method, "application/json", "{}", "")

    fun ajax(url: String, method: String, mediaType: String): String? =
        ajax(url, method, mediaType, "{}", "")

    // 💡 请求头快照栈（线程隔离）：每次发送压入"实际发送的头"，processResponse 时按
    // URL 配对弹出。交错请求、并发线程都能正确配对，解决签名时间戳漂移导致的
    // BadPadding 间歇性解密失败。
    private class HeaderSnapshot(val url: String, val headers: Map<String, String>)

    private val headerSnapshots = ThreadLocal<MutableList<HeaderSnapshot>?>()

    private fun pushSnapshot(url: String, headers: Map<String, String>) {
        val list = headerSnapshots.get() ?: mutableListOf<HeaderSnapshot>().also { headerSnapshots.set(it) }
        list.add(HeaderSnapshot(url, headers))
        if (list.size > 16) list.removeAt(0)
    }

    /** 从栈顶向下找与 url 匹配的最近一次快照并消费掉；url 为空则取最后一次 */
    private fun popSnapshot(url: String): Map<String, String>? {
        val list = headerSnapshots.get() ?: return null
        if (list.isEmpty()) return null
        if (url.isBlank()) return list.removeAt(list.size - 1).headers
        for (i in list.indices.reversed()) {
            if (list[i].url == url) return list.removeAt(i).headers
        }
        return null
    }

    private fun parseHeaderMap(header: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        runCatching {
            JSON.parseObject(header)?.forEach { (k, v) ->
                if (k != null && v != null) map[k.toString()] = v.toString()
            }
        }
        return map
    }

    fun ajax(
        url: String,
        method: String,
        mediaType: String,
        header: String,
        data: String
    ): String? {
        val client = sharedClient ?: OkHttpClient.Builder().build()

        // 💡 合并签名头：签名头打底，脚本显式传入的 header 可覆盖同名项
        val mergedMap = LinkedHashMap<String, String>()
        runCatching { currentSigner()?.invoke(url) }.getOrNull().orEmpty().forEach { (k, v) -> mergedMap[k] = v }
        parseHeaderMap(header).forEach { (k, v) -> mergedMap[k] = v }
        pushSnapshot(url, mergedMap)

        return rawSend(client, url, method, mediaType, JSON.toJSONString(mergedMap), data)
    }

    fun rawAjax(url: String): String? = rawAjax(url, "GET", "application/json", "{}", "")

    fun rawAjax(url: String, method: String): String? = rawAjax(url, method, "application/json", "{}", "")

    fun rawAjax(url: String, method: String, mediaType: String): String? =
        rawAjax(url, method, mediaType, "{}", "")

    /** 裸请求：不注入动态签名头，仅使用脚本显式传入的 header */
    fun rawAjax(
        url: String,
        method: String,
        mediaType: String,
        header: String,
        data: String
    ): String? {
        val client = sharedClient ?: OkHttpClient.Builder().build()
        pushSnapshot(url, parseHeaderMap(header))
        return rawSend(client, url, method, mediaType, header, data)
    }

    fun ajax(
        okHttpClient: OkHttpClient,
        url: String,
        method: String = "GET",
        mediaType: String = "application/json",
        header: String = "{}",
        data: String = ""
    ): String? = rawSend(okHttpClient, url, method, mediaType, header, data)

    private fun rawSend(
        okHttpClient: OkHttpClient,
        url: String,
        method: String,
        mediaType: String,
        header: String,
        data: String
    ): String? {
        var builder: Request.Builder = Request.Builder().url(url)
        builder = if (method.equals("POST",true)) {
            val body: RequestBody = data.toRequestBody(mediaType.toMediaTypeOrNull())
            builder.post(body)
        } else {
            builder.get()
        }

        builder = builder.headers(
            (JSON.parseObject(
                header, object : TypeReference<HashMap<String?, String?>?>(){}.type
            ) as? HashMap<String, String> ?: HashMap()).toHeaders()
        )

        return runBlocking(jsHttpDispatcher) {
            kotlin.runCatching {
                okHttpClient.newCall(builder.build()).execute().use { it.body.string() }
            }.onFailure {
                logger.warn { "ajax($url) error: ${it.message}" }
            }.getOrNull()
        }

    }

    fun timeout(runnable: Runnable, long: Long) {
        timeoutScheduler.schedule(runnable, long, TimeUnit.MILLISECONDS)
    }

    fun md5(
        str: String
    ) : String {
        return str.toMd5()
    }

    /**
     * base64解密
     */
    fun base64Decrypt(str: String): String {
        return Base64.decodeStr(str)
    }

    /**
     * base64加密
     */
    fun base64Encrypt(str: String): String {
        return Base64.encode(str)
    }

    /**
     * 字符串DES解密
     * @param str 待解密的字符串
     * @param key DES 解密的key
     * @param paddings DES加密类型
     * @param iv ECB模式的偏移向量
     */
    fun desDecrypt(
        str: String,
        key: String,
        paddings: String = "DES/ECB/PKCS5Padding",
        iv: String
    ): String? {
        return crypt(str, key, "DES", paddings, iv, false)?.let { String(it) }
    }

    /**
     * 字符串DES加密
     * @param str 待解密的字符串
     * @param key DES 解密的key
     * @param paddings DES加密类型
     * @param iv ECB模式的偏移向量
     */
    fun desEncrypt(
        str: String,
        key: String,
        paddings: String = "DES/ECB/PKCS5Padding",
        iv: String
    ): String? {
        return crypt(str, key, "DES", paddings, iv, true)?.let { String(it) }
    }

    /**
     * AES 字符串解密
     * @param str 待解密的字符串
     * @param key AES 解密的key
     * @param paddings AES加密类型
     * @param iv ECB模式的偏移向量
     */
    fun aesDecrypt(
        str: String,
        key: String,
        paddings: String = "AES/CBC/PKCS5Padding",
        iv: String?
    ): String? {
            return crypt(str, key, "AES", paddings, iv, false)?.let { String(it) }
    }

    /**
     * AES 字符串解密
     * @param str 待解密的字符串
     * @param key AES 解密的key
     * @param paddings AES加密类型
     * @param iv ECB模式的偏移向量
     */
    fun aesDecryptBytes(
        str: ByteArray,
        key: String,
        paddings: String = "AES/CBC/PKCS5Padding",
        iv: String?
    ): ByteArray? {
        return crypt(str, key, "AES", paddings, iv, false)
    }

    /**
     * AES 字符串加密
     * @param str 待加密的字符串
     * @param key AES 解密的key
     * @param paddings AES加密类型
     * @param iv ECB模式的偏移向量
     */
    fun aesEncrypt(
        str: String,
        key: String,
        paddings: String = "AES/CBC/PKCS5Padding",
        iv: String
    ): String? {
        return crypt(str, key, "AES", paddings, iv, true)?.let { String(it) }
    }

    /**
     * 返回对称加密或解密的字节。
     * @param data 待处理字符串
     * @param key 解密的key
     * @param mode 加密的方式
     * @param paddings 加密的类型
     * @param iv ECB模式的偏移向量
     * @param isEncrypt 加密 or 解密
     */
    private fun crypt(
        data: String,
        key: String?,
        mode: String,
        paddings: String?,
        iv: String?,
        isEncrypt: Boolean
    ): ByteArray? {
        return crypt(SecureUtil.decode(data), key, mode, paddings, iv, isEncrypt)
    }

    /**
     * 返回对称加密或解密的字节。
     * @param data 待处理字符串
     * @param key 解密的key
     * @param mode 加密的方式
     * @param paddings 加密的类型
     * @param iv ECB模式的偏移向量
     * @param isEncrypt 加密 or 解密
     */
    private fun crypt(
        data: ByteArray,
        key: String?,
        mode: String,
        paddings: String?,
        iv: String?,
        isEncrypt: Boolean
    ): ByteArray? {

        if (data.isEmpty() || key.isNullOrEmpty()) return null

        val keySpec = SecretKeySpec(key.toByteArray(), mode)
        var params: AlgorithmParameterSpec? = null
        if (!iv.isNullOrEmpty()) {
            params = IvParameterSpec(iv.toByteArray())
        }
        val symmetricCrypto = if (params == null) SymmetricCrypto(paddings, keySpec) else SymmetricCrypto(paddings, keySpec, params)
        return if (isEncrypt) symmetricCrypto.encrypt(data) else symmetricCrypto.decrypt(data)
    }

    fun hmacSHA256(key: String, data: String): ByteArray {
        val hmac = HMac(HmacAlgorithm.HmacSHA256, SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        val result = hmac.digest(data.toByteArray()) as ByteArray
        return result
    }

    fun hmacSHA256StoHex(key: String, data: String): String {
        return hmacSHA256(key, data).joinToString("") { "%02x".format(it) }
    }



    /**
     * 将js类型转换成java类型
     *
     * @param value js类型
     * @return java类型
     */
    fun jsToJavaObject(value: Any?): Any {
        return when (value) {
            null, is Undefined -> ""
            is NativeArray -> value.toList().map(::jsToJavaObject)
            is Wrapper -> jsToJavaObject(value.unwrap())
            is CharSequence -> value.toString()
            is MutableMap<*, *> -> {
                // 💡 Rhino 的 NativeObject 实现了 Map，但 put() 只允许在活跃的 JS Context 内调用，
                // 在脚本上下文外写入会抛 UnsupportedOperationException。因此对 Rhino 对象退化为
                // 只读转换并生成新的 LinkedHashMap；普通 Java Map（如 fastjson2 JSONObject）
                // 仍然保持"原地递归转换并返回同一实例"的原有语义。
                @Suppress("UNCHECKED_CAST")
                val mutableMap = value as MutableMap<Any?, Any?>
                if (value is ScriptableObject) {
                    val converted = LinkedHashMap<Any?, Any?>(mutableMap.size)
                    mutableMap.forEach { (key, v) -> converted[key] = jsToJavaObject(v) }
                    converted
                } else {
                    mutableMap.keys.toList().forEach { key ->
                        mutableMap[key] = jsToJavaObject(mutableMap[key])
                    }
                    value
                }
            }
            else -> value
        }
    }

}
