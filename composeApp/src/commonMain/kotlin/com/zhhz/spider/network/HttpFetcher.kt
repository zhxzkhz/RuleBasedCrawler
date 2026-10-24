package com.zhhz.spider.network
import com.zhhz.spider.rule.TagAction
import com.zhhz.spider.util.JsExtensionClass
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

private val logger = KotlinLogging.logger {} // 声明类级日志对象

/**
 * 工业级网络抓取执行器（V3 性能优化版）
 * 解决了：Cookie 持久化、连接池复用、代理实例冗余构建、频率限制
 */
class HttpFetcher(private val snapshotInterceptor: FileSnapshotInterceptor) {

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val clientRegistry = ConcurrentHashMap<String, OkHttpClient>()
    private val configFingerprint = ConcurrentHashMap<String, Int>() // 存储配置的哈希指纹
    private val rateLimiter = SourceRateLimiter()

    private val myCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = cookieStore[host].orEmpty()
            // 按 name+path 去重合并，新 cookie 覆盖同名旧 cookie
            val merged = (existing + cookies).associateBy { "${it.name}@${it.path}" }.values.toList()
            cookieStore[host] = merged
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

    init {
        // 💡 把带全局 CookieJar 的基础客户端共享给 JS 脚本层：
        // 脚本里 java.ajax(url) 发出的请求自动继承/回存同域 Cookie
        JsExtensionClass.attachSharedClient(baseClient)
    }

    fun fetch(sourceId: String, rate: Long, req: FetchRequest): String {
        // 1. 限流（按源串行，见 SourceRateLimiter）
        rateLimiter.acquire(sourceId, rate)

        // 2. 获取或创建 Client (包含 Proxy 和 DNS 检测)
        val client = getOrCreateClient(sourceId, req.proxy, req.customDns)

        // 3. 构建请求并打标 (TagAction 逻辑)
        val tagAction = TagAction(req.isCache, req.cacheSalt)
        val okReq = Request.Builder().url(req.url).tag(TagAction::class.java, tagAction).apply {
            req.headers.forEach { (k, v) -> header(k, v) }
            if (req.method == "POST") {
                val mediaType = (if (req.isJson) "application/json" else "application/x-www-form-urlencoded") + "; charset=${req.charset}"
                post((req.body ?: "").toRequestBody(mediaType.toMediaTypeOrNull()))
            }
        }.build()

        return try {
            client.newCall(okReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body.string()
                    logger.error { "HTTP 请求失败 sourceId=$sourceId method=${req.method} url=${req.url} code=${resp.code} body=${errorBody.take(500)}" }
                    return "ERROR: HTTP ${resp.code} >> $errorBody"
                }
                val bytes = resp.body.bytes()
                String(bytes, Charset.forName(req.charset))
            }
        } catch (e: Exception) {
            logger.error(e) { "HTTP 请求异常 sourceId=$sourceId method=${req.method} url=${req.url}" }
            "ERROR: ${e.message}"
        }
    }

    fun getExistingClient(sourceId: String): OkHttpClient? {
        // 直接返回已经构建好的、带代理和 DNS 的 Client
        return clientRegistry[sourceId]
    }

    /**
     * 获取或创建带代理/DNS 配置的 Client。
     * 如果缓存中已有且配置未变，直接返回；否则按给定配置重建。
     */
    fun getOrCreateClient(sourceId: String, currentProxy: Proxy?, dnsConfig: String?): OkHttpClient {
        val currentFingerprint = Objects.hash(currentProxy, dnsConfig)

        val cachedClient = clientRegistry[sourceId]
        val oldFingerprint = configFingerprint[sourceId]

        if (cachedClient != null && oldFingerprint == currentFingerprint) {
            return cachedClient
        }

        // 重建 client 前只清理连接池：newBuilder() 与 baseClient 共享同一个 Dispatcher
        // 与 ConnectionPool，shutdown 共享的 executorService 会让 baseClient 以及所有
        // 已缓存 client 后续的异步请求直接抛 RejectedExecutionException。
        // （当前全项目都是同步 execute()，不经过该线程池，所以这个问题很隐蔽）
        cachedClient?.let {
            it.connectionPool.evictAll()
        }

        val newClient = baseClient.newBuilder().proxy(currentProxy).dns(SmartDns(dnsConfig)).build()

        clientRegistry[sourceId] = newClient
        configFingerprint[sourceId] = currentFingerprint

        return newClient
    }
}

/**
 * 按书源串行化的请求限流器：concurrentRate 的语义是"同一书源两次请求的最小间隔"。
 *
 * 旧实现先读 lastRequestMap[id] 再写、中间无原子性，并发线程会读到同一个旧时间戳、
 * 计算出相同等待并同时放行（漫画下载 imageSlots 默认 6 并发时可同源 6 连发）。
 * 这里在每个源自己的锁内完成"计算最早可发时刻 → 等待 → 登记"，
 * 后来的请求在锁上排队并基于前一个的完成时刻重新计算间隔；
 * park 循环抵御虚假唤醒。不同书源使用不同的锁，互不阻塞。
 */
internal class SourceRateLimiter {
    private val lastRequestMap = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun acquire(id: String, delayMs: Long) {
        if (delayMs <= 0) return
        val lock = locks.computeIfAbsent(id) { ReentrantLock() }
        lock.lock()
        try {
            val earliest = (lastRequestMap[id] ?: 0L) + delayMs
            var remaining = earliest - System.currentTimeMillis()
            while (remaining > 0) {
                LockSupport.parkNanos(remaining * 1_000_000)
                remaining = earliest - System.currentTimeMillis()
            }
            lastRequestMap[id] = System.currentTimeMillis()
        } finally {
            lock.unlock()
        }
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
    val customDns: String? = null,      // DNS 配置字段
    val cacheSalt: String = ""          // 快照缓存盐（规则指纹）：规则变更后旧快照自动失效
)
