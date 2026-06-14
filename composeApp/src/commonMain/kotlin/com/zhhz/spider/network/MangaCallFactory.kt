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
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

class MangaCallFactory(
    private val httpFetcher: HttpFetcher,
    private val ruleRepository: RuleRepository,
    private val sessionRepository: SessionRepository,
    private val contextSessionManager: ContextSessionManager
) : okhttp3.Call.Factory {

    override fun newCall(request: okhttp3.Request): okhttp3.Call {
        val ruleId = request.headers["X-Internal-Rule-Id"]

        val (ctx, rule) = ruleId?.let { id ->
            // 采用标准同步锁机制，或使用 runBlocking，但必须指定 Dispatchers.IO 避开主线程
            runBlocking(Dispatchers.IO) {
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
            bindings["request"] = request
            bindings["newRequestBuilder"] = newRequestBuilder

            // 如果有动态脚本(Js)，执行它以生成最新的 Header
            if (!it.globalConfig.headerScript.isNullOrBlank()) {
                val dynamicHeaders =
                    JsExtensionClass.jsToJavaObject(JsEngineRunner.eval(it.globalConfig.headerScript!!, bindings))
                if (dynamicHeaders is String) {
                    try {
                        JSON.parseObject(dynamicHeaders, Map::class.java).map { (key, value) ->
                            newRequestBuilder.set(key.toString(), value.toString())
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Error parsing dynamic headers" }
                    }
                }
            }
        }

        val client = if (!ruleId.isNullOrBlank()) {
            httpFetcher.getExistingClient(ruleId) ?: httpFetcher.baseClient
        } else {
            httpFetcher.baseClient
        }

        return client.newCall(
            request.newBuilder().headers(newRequestBuilder.removeAll("X-Internal-Rule-Id").removeAll("X-Internal-Book-Url").build()).build()
        )
    }
}
