package com.zhhz.spider.repository.impl

import com.zhhz.spider.network.ExploreTab
import com.zhhz.spider.rule.JsEngineRunner
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import com.zhhz.spider.util.JsExtensionClass
import com.zhhz.spider.util.JsExtensionClass.jsToJavaObject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

private val tabsJson = Json { ignoreUnknownKeys = true }

/**
 * 解析"静态来源"的分类标签。
 * 💡 固定列表(fixedTabs) 与 JS 脚本(tabsScript) 可同时配置：
 * 结果合并、按 URL 去重、固定列表排在前面；
 * 两者都为空才回退到入口页动态解析（由调用方处理）。
 */
internal object ExploreTabResolver {

    /** 最近一次分类脚本执行的错误信息（调试用），成功后清空 */
    var lastEvalError: String? = null
        private set

    fun resolveStatic(rule: SourceRule, ctx: VariableContext): List<ExploreTab> {
        val fixedTabs = rule.explore.fixedTabs
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .map {
                ExploreTab(
                    title = it.title.trim(),
                    url = it.url.trim(),
                    group = it.group.trim()
                )
            }

        val scriptTabs = evalTabsScript(rule, ctx)

        if (fixedTabs.isEmpty() && scriptTabs.isEmpty()) return emptyList()
        return (fixedTabs + scriptTabs).distinctBy { it.url }
    }

    /**
     * 执行 explore.tabsScript 生成分类。
     * 可用变量：java/java_ctx/java_url/java_log（与其他脚本一致）；
     * 返回值支持：[{title,url,...}] 对象数组，或等价的 JSON 字符串。
     */
    fun evalTabsScript(rule: SourceRule, ctx: VariableContext): List<ExploreTab> {
        val script = rule.explore.tabsScript ?: return emptyList()
        if (script.isBlank()) return emptyList()

        return try {
            val bindings = SimpleBindings()
            bindings.put("java", JsExtensionClass)
            bindings.put("java_ctx", ctx)
            bindings.put("java_url", rule.url)
            bindings.put("java_log", logger)
            // 💡 与管线脚本对齐的常用绑定，避免 ReferenceError
            bindings.put("java_source", rule)
            bindings.put("java_page", rule.explore)
            bindings.put(
                "java_request",
                com.zhhz.spider.network.FetchRequest(
                    url = rule.url,
                    method = (rule.explore.config.method ?: rule.globalConfig.method ?: "GET").uppercase(),
                    charset = rule.explore.config.charset ?: rule.globalConfig.charset ?: "UTF-8",
                    isCache = rule.useCache,
                    headers = rule.globalConfig.headers + rule.explore.config.headers,
                    body = rule.explore.config.bodyPayload ?: rule.globalConfig.bodyPayload,
                    isJson = rule.explore.config.isJson ?: rule.globalConfig.isJson ?: false
                )
            )

            val result = jsToJavaObject(JsEngineRunner.eval(rule.id, script, bindings))
            when (result) {
                is List<*> -> result.mapNotNull(::toExploreTab).also { tabs ->
                    lastEvalError = if (tabs.isEmpty()) "脚本执行成功但未产出有效分类（元素需含 title/url 字段）" else null
                }
                is CharSequence -> parseTabsJson(result.toString())
                else -> {
                    lastEvalError = "返回类型不支持：${result.let { it::class.simpleName } ?: "null"}，需为 [{group,title,url}] 数组或 JSON 字符串"
                    logger.error { "发现分类脚本返回类型不支持 id=${rule.id}" }
                    emptyList()
                }
            }
        } catch (e: Exception) {
            lastEvalError = "${e::class.simpleName}: ${e.message}"
            logger.error(e) { "发现分类脚本执行失败 id=${rule.id}" }
            emptyList()
        }
    }

    private fun toExploreTab(row: Any?): ExploreTab? {
        val map = row as? Map<*, *> ?: return null
        val title = map["title"]?.toString()?.trim().orEmpty()
        val url = map["url"]?.toString()?.trim().orEmpty()
        if (title.isEmpty() || url.isEmpty()) return null
        return ExploreTab(
            title = title,
            url = url,
            group = map["group"]?.toString()?.trim().orEmpty()
        )
    }

    private fun parseTabsJson(text: String): List<ExploreTab> = runCatching {
        tabsJson.decodeFromString<List<ExploreTab>>(text.trim())
    }.getOrElse {
        logger.error(it) { "发现分类脚本 JSON 输出解析失败" }
        emptyList()
    }
}
