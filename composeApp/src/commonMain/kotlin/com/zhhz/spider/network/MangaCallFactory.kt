package com.zhhz.spider.network

import com.alibaba.fastjson2.JSON
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.JsEngineRunner
import com.zhhz.spider.util.JsExtensionClass
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Proxy
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

// 专用调度器：避免 Coil 主线程调用时阻塞 Dispatchers.IO 通用池
private val mangaCallDispatcher = Dispatchers.IO.limitedParallelism(2)

class MangaCallFactory(
    private val httpFetcher: HttpFetcher,
    private val ruleRepository: RuleRepository,
    private val sessionRepository: SessionRepository,
    private val contextSessionManager: ContextSessionManager
) : okhttp3.Call.Factory {

    override fun newCall(request: okhttp3.Request): okhttp3.Call {
        val ruleId = request.headers["X-Internal-Rule-Id"]

        val (ctx, rule) = ruleId?.let { id ->
            // 采用专用调度器，避免阻塞通用 IO 线程池和主线程
            runBlocking(mangaCallDispatcher) {
                val context = contextSessionManager.getActiveContext(sessionRepository,id)
                val activeRule = ruleRepository.getEnabledRules().find { it.id == id }

                if (activeRule != null) {
                    context to activeRule // 💡 修正类型：Pair<VariableContext, SourceRule>
                } else {
                    null
                }
            }
        } ?: (null to null)

        val newRequestBuilder = request.headers.newBuilder()

        rule?.let {
            val bindings = SimpleBindings()
            bindings["java"] = JsExtensionClass
            bindings["java_log"] = logger
            bindings["java_ctx"] = ctx
            bindings["java_url"] = request.url.toString()
            bindings["java_source"] = rule
            bindings["java_request"] = request
            bindings["request"] = request
            bindings["newRequestBuilder"] = newRequestBuilder

            // 如果有动态脚本(Js)，执行它以生成最新的 Header
            if (!it.globalConfig.headerScript.isNullOrBlank()) {
                val dynamicHeaders =
                    JsExtensionClass.jsToJavaObject(JsEngineRunner.eval(it.id, it.globalConfig.headerScript!!, bindings))
                if (dynamicHeaders is String) {
                    try {
                        (JSON.parseObject(dynamicHeaders, Map::class.java) as? Map<*, *>)?.forEach { (key, value) ->
                            if (key != null && value != null) {
                                newRequestBuilder.set(key.toString(), value.toString())
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Error parsing dynamic headers" }
                    }
                }
            }
        }

        val client = if (!ruleId.isNullOrBlank()) {
            httpFetcher.getExistingClient(ruleId)
                ?: rule?.let { r ->
                    val proxy = r.proxyUrl?.takeIf { it.contains(":") }?.let {
                        try {
                            val parts = it.split(":")
                            Proxy(Proxy.Type.HTTP, InetSocketAddress(parts[0], parts[1].toInt()))
                        } catch (_: Exception) { null }
                    }
                    httpFetcher.getOrCreateClient(ruleId, proxy, r.customDns)
                }
                ?: httpFetcher.baseClient
        } else {
            httpFetcher.baseClient
        }

        return client.newCall(
            request.newBuilder().headers(newRequestBuilder.removeAll("X-Internal-Rule-Id").removeAll("X-Internal-Book-Url").build()).build()
        )
    }

    fun newCall(
        request: okhttp3.Request,
        rule: com.zhhz.spider.rule.SourceRule,
        ctx: com.zhhz.spider.rule.VariableContext
    ): okhttp3.Call {
        val newRequestBuilder = request.headers.newBuilder()
        val bindings = SimpleBindings().apply {
            put("java", JsExtensionClass)
            put("java_log", logger)
            put("java_ctx", ctx)
            put("java_url", request.url.toString())
            put("java_source", rule)
            put("request", request)
            put("newRequestBuilder", newRequestBuilder)
        }
        if (!rule.globalConfig.headerScript.isNullOrBlank()) {
            val dynamicHeaders = JsExtensionClass.jsToJavaObject(
                JsEngineRunner.eval(rule.id, rule.globalConfig.headerScript!!, bindings)
            )
            if (dynamicHeaders is String) {
                (JSON.parseObject(dynamicHeaders, Map::class.java) as? Map<*, *>)?.forEach { (key, value) ->
                    if (key != null && value != null) {
                        newRequestBuilder.set(key.toString(), value.toString())
                    }
                }
            }
        }
        val client = httpFetcher.getExistingClient(rule.id) ?: httpFetcher.baseClient
        return client.newCall(request.newBuilder().headers(newRequestBuilder.build()).build())
    }
}
