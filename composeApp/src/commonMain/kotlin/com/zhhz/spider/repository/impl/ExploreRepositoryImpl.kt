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

        // 💡 静态来源优先：fixedTabs 写死列表 > tabsScript JS 生成；都没有才请求入口页解析
        val staticTabs = ExploreTabResolver.resolveStatic(rule, ctx)
        if (staticTabs.isNotEmpty()) return@withContext staticTabs

        val entryInput = rule.url
        val entryUrl = rule.explore.getExploreUrl(entryInput, ctx).ifBlank { entryInput }
        check(entryUrl.isNotBlank()) { "发现入口地址为空，请在规则的 explore.url 中配置" }

        val html = fetchTaskRunner.fetch(rule, rule.explore, entryUrl, ctx)
        html.throwIfCrawlerError(rule, CrawlerStage.EXPLORE)

        rule.explore.getTabs(html, ctx)
            .mapIndexed { _, item ->
                ExploreTab(
                    title = rule.explore.getTabName(item, ctx).trim(),
                    url = rule.explore.getTabUrl(item, ctx).trim()
                )
            }
            .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }
            .distinctBy { it.url }
    }

    override suspend fun fetchBooks(ruleId: String, tabUrl: String): List<Book> = withContext(Dispatchers.IO) {
        val rule = findRule(ruleId)
        check(tabUrl.isNotBlank()) { "分类地址为空" }

        val ctx = contextSessionManager.getContext(rule.id)
        ctx["bookUrl"] = rule.url

        val html = fetchTaskRunner.fetch(rule, rule.explore, tabUrl, ctx)
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
