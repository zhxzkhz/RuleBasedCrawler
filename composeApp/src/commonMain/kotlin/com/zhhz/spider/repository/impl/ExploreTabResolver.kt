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
 * 优先级：fixedTabs 写死列表 > tabsScript JS 生成 > 空列表（由调用方回退到入口页解析）。
 */
internal object ExploreTabResolver {

    fun resolveStatic(rule: SourceRule, ctx: VariableContext): List<ExploreTab> {
        val fixedTabs = rule.explore.fixedTabs
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .map { ExploreTab(title = it.title.trim(), url = it.url.trim()) }
            .distinctBy { it.url }
        if (fixedTabs.isNotEmpty()) return fixedTabs

        return evalTabsScript(rule, ctx)
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

            val result = jsToJavaObject(JsEngineRunner.eval(script, bindings))
            when (result) {
                is List<*> -> result.mapNotNull(::toExploreTab)
                is CharSequence -> parseTabsJson(result.toString())
                else -> {
                    logger.error { "发现分类脚本返回类型不支持 id=${rule.id}" }
                    emptyList()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "发现分类脚本执行失败 id=${rule.id}" }
            emptyList()
        }
    }

    private fun toExploreTab(row: Any?): ExploreTab? {
        val map = row as? Map<*, *> ?: return null
        val title = map["title"]?.toString()?.trim().orEmpty()
        val url = map["url"]?.toString()?.trim().orEmpty()
        return if (title.isEmpty() || url.isEmpty()) null else ExploreTab(title, url)
    }

    private fun parseTabsJson(text: String): List<ExploreTab> = runCatching {
        tabsJson.decodeFromString<List<ExploreTab>>(text.trim())
    }.getOrElse {
        logger.error(it) { "发现分类脚本 JSON 输出解析失败" }
        emptyList()
    }
}
