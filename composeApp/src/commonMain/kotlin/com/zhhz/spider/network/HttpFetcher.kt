package com.zhhz.spider.network
import com.zhhz.spider.rule.TagAction
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {} // 声明类级日志对象

/**
 * 工业级网络抓取执行器（V3 性能优化版）
 * 解决了：Cookie 持久化、连接池复用、代理实例冗余构建、频率限制
 */
class HttpFetcher(private val snapshotInterceptor: FileSnapshotInterceptor) {

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val clientRegistry = ConcurrentHashMap<String, OkHttpClient>()
    private val configFingerprint = ConcurrentHashMap<String, Int>() // 存储配置的哈希指纹
    private val lastRequestMap = ConcurrentHashMap<String, Long>()

    private val myCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host] ?: listOf()
    }

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cookieJar(myCookieJar)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .addInterceptor(snapshotInterceptor)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .build()
    }

    fun fetch(sourceId: String, rate: Long, req: FetchRequest): String {
        // 1. 限流
        applyRateLimit(sourceId, rate)

        // 2. 获取或创建 Client (包含 Proxy 和 DNS 检测)
        val client = getOrCreateClient(sourceId, req.proxy, req.customDns)

        // 3. 构建请求并打标 (TagAction 逻辑)
        val tagAction = TagAction(req.isCache)
        val okReq = Request.Builder().url(req.url).tag(TagAction::class.java, tagAction).apply {
            req.headers.forEach { (k, v) -> header(k, v) }
            if (req.method == "POST") {
                val mediaType = (if (req.isJson) "application/json" else "application/x-www-form-urlencoded") + "; charset=${req.charset}"
                post((req.body ?: "").toRequestBody(mediaType.toMediaTypeOrNull()))
            }
        }.build()

        return try {
            client.newCall(okReq).execute().use { resp ->
                if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code} >> ${resp.body.string()}"
                val bytes = resp.body.bytes()
                String(bytes, Charset.forName(req.charset))
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun getExistingClient(sourceId: String): OkHttpClient? {
        // 直接返回已经构建好的、带代理和 DNS 的 Client
        return clientRegistry[sourceId]
    }

    private fun getOrCreateClient(sourceId: String, currentProxy: Proxy?, dnsConfig: String?): OkHttpClient {
        // 1. 只计算影响网络构建的字段的哈希值
        val currentFingerprint = Objects.hash(currentProxy, dnsConfig)

        val cachedClient = clientRegistry[sourceId]
        val oldFingerprint = configFingerprint[sourceId]

        if (cachedClient != null && oldFingerprint == currentFingerprint) {
            return cachedClient
        }

        // 3. 否则重建
        val newClient = baseClient.newBuilder().proxy(currentProxy).dns(SmartDns(dnsConfig)).build()

        clientRegistry[sourceId] = newClient
        configFingerprint[sourceId] = currentFingerprint

        return newClient
    }

    private fun applyRateLimit(id: String, delay: Long) {
        val wait = (lastRequestMap[id] ?: 0) + delay - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestMap[id] = System.currentTimeMillis()
    }
}

/**
 * 网络请求参数封装类
 */
data class FetchRequest(
    val url: String,                    // 目标地址
    val keyword: String = "",           // 搜索词
    val method: String = "GET",         // GET 或 POST
    val charset: String = "UTF-8",      // 网页编码（GBK处理的关键）
    val isCache: Boolean = false,       // 是否缓存
    val headers: Map<String, String> = emptyMap(), // 自定义请求头
    val body: String? = null,           // POST 负载
    val isJson: Boolean = false,        // 是否为 JSON 提交
    val proxy: Proxy? = null,           // 代理服务器
    val customDns: String? = null       // DNS 配置字段
)

