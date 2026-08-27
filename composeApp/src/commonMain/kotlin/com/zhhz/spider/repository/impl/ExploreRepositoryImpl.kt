package com.zhhz.spider.repository.impl

import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.model.CrawlerStage
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.ExploreTab
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.ExploreRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import com.zhhz.spider.util.JsExtensionClass
import com.zhhz.spider.rule.exploreAuthorSelector
import com.zhhz.spider.rule.exploreCoverSelector
import com.zhhz.spider.rule.exploreDetailUrlSelector
import com.zhhz.spider.rule.exploreListSelector
import com.zhhz.spider.rule.exploreNameSelector
import com.zhhz.spider.rule.supportsExplore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExploreRepositoryImpl(
    private val contextSessionManager: ContextSessionManager,
    private val fetchTaskRunner: FetchTaskRunner,
    private val ruleRepository: RuleRepository
) : ExploreRepository {

    override suspend fun fetchTabs(ruleId: String): List<ExploreTab> = withContext(Dispatchers.IO) {
        val rule = findRule(ruleId)
        check(rule.supportsExplore()) { "该书源未配置发现/榜单规则" }

        val ctx = contextSessionManager.getContext(rule.id)
        ctx["bookUrl"] = rule.url

        // 💡 分类脚本可能通过 java.ajax 联网取数：挂载当前规则的动态签名头与响应处理器，
        // 使脚本请求/响应处理与管线行为一致（签名避免 401，processResponse 完成解密）
        val headerSigner = fetchTaskRunner.createJsHeaderSigner(rule, ctx)
        JsExtensionClass.attachHeaderSigner(headerSigner)
        // 响应处理器复用同一签名器，java_request.headers 即本次请求实际发送的头
        JsExtensionClass.attachResponseProcessor(
            fetchTaskRunner.createJsResponseProcessor(rule, ctx) { url -> headerSigner(url) }
        )
        try {
            fetchTabsInternal(rule, ctx)
        } finally {
            JsExtensionClass.attachHeaderSigner(null)
            JsExtensionClass.attachResponseProcessor(null)
        }
    }

    private suspend fun fetchTabsInternal(rule: SourceRule, ctx: VariableContext): List<ExploreTab> {
        // 💡 静态来源（固定分类 / JS 脚本）：只要配置了其一，就只走静态、绝不回退入口页。
        // 解析为空时直接报错——静默降级会让请求莫名其妙打到书源主页上。
        val staticConfigured = rule.explore.fixedTabs.any { it.title.isNotBlank() && it.url.isNotBlank() } ||
                !rule.explore.tabsScript.isNullOrBlank()
        val staticTabs = ExploreTabResolver.resolveStatic(rule, ctx)
        if (staticConfigured) {
            check(staticTabs.isNotEmpty()) {
                "固定分类/JS 脚本未生成任何有效分类。" +
                        "请检查：① JS 返回值必须是 [{group,title,url}] 对象数组；② title/url 不能为空；③ 详见日志中的脚本异常"
            }
            return staticTabs
        }

        // 动态来源：入口页解析分类标签
        val entryInput = rule.url
        val entryUrl = rule.explore.getExploreUrl(entryInput, ctx).ifBlank { entryInput }
        check(entryUrl.isNotBlank()) { "发现入口地址为空，请在规则的 explore.url 中配置" }

        val html = fetchTaskRunner.fetch(rule, rule.explore, entryUrl, ctx)
        html.throwIfCrawlerError(rule, CrawlerStage.EXPLORE)

        val tabs = rule.explore.getTabs(html, ctx)
            .mapIndexed { _, item ->
                ExploreTab(
                    title = rule.explore.getTabName(item, ctx).trim(),
                    url = rule.explore.getTabUrl(item, ctx).trim(),
                    group = rule.explore.getTabGroup(item, ctx)
                )
            }
            .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }
            .distinctBy { it.url }
        return tabs
    }

    override suspend fun fetchBooks(ruleId: String, tabUrl: String, page: Int): List<Book> = withContext(Dispatchers.IO) {
        val rule = findRule(ruleId)
        check(tabUrl.isNotBlank()) { "分类地址为空" }

        val ctx = contextSessionManager.getContext(rule.id)
        ctx["bookUrl"] = rule.url
        // 💡 分类地址支持 {{page}} 模板翻页；无模板时原样请求
        val requestUrl = RuleParser.resolveTemplate(tabUrl, tabUrl, ctx.apply { put("page", page.toString()) })

        val html = fetchTaskRunner.fetch(rule, rule.explore, requestUrl, ctx)
        html.throwIfCrawlerError(rule, CrawlerStage.EXPLORE)

        val listSelector = rule.exploreListSelector()
        val nameSelector = rule.exploreNameSelector()
        val authorSelector = rule.exploreAuthorSelector()
        val coverSelector = rule.exploreCoverSelector()
        val detailUrlSelector = rule.exploreDetailUrlSelector()

        RuleParser.parseList(html, listSelector, ctx).mapNotNull { item ->
            val detailUrl = RuleParser.parseString(item, detailUrlSelector, ctx).trim()
            if (detailUrl.isEmpty()) return@mapNotNull null
            Book(
                url = detailUrl,
                title = RuleParser.parseString(item, nameSelector, ctx).trim(),
                author = RuleParser.parseString(item, authorSelector, ctx).trim(),
                cover = RuleParser.parseString(item, coverSelector, ctx).trim(),
                ruleId = rule.id
            )
        }
    }

    private fun findRule(ruleId: String): SourceRule =
        ruleRepository.getEnabledRules().find { it.id == ruleId }
            ?: throw Exception("找不到对应的书源规则")
}
