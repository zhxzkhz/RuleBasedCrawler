package com.zhhz.spider.debug

import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.ParseTraceEvent
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalDebugResult(
    val output: String,
    val traces: List<ParseTraceEvent>
)

data class NetworkDebugResult(
    val html: String,
    val localResult: LocalDebugResult,
    val isError: Boolean
)

data class DebugPageUpdate(
    val tabIndex: Int,
    val message: String,
    val html: String? = null,
    val localResult: LocalDebugResult? = null
)

data class FullChainDebugResult(
    val success: Boolean,
    val context: VariableContext,
    val errorTabIndex: Int? = null,
    val errorMessage: String? = null
)

object RuleDebugRunner {

    suspend fun runFullChain(
        input: String,
        rule: SourceRule,
        initialContext: VariableContext,
        taskRunner: FetchTaskRunner,
        contextSessionManager: ContextSessionManager,
        sessionRepository: SessionRepository,
        onUpdate: (DebugPageUpdate) -> Unit
    ): FullChainDebugResult {
        var ctx = initialContext
        var currentTab = 2

        return try {
            currentTab = 2
            onUpdate(DebugPageUpdate(currentTab, ">>> 正在搜索: $input ..."))
            val searchHtml = fetchOnIo { taskRunner.fetch(rule, rule.search, input, ctx) }
            val searchList = rule.search.getList(searchHtml, ctx)
            if (searchList.isEmpty()) {
                val localResult = runLocal(currentTab, searchHtml, rule, ctx)
                onUpdate(DebugPageUpdate(currentTab, "√ 搜索结果为空，流程中断\n${localResult.output}", searchHtml, localResult))
                throw IllegalStateException("搜索结果为空，流程中断")
            }

            val firstBookUrl = rule.search.getDetailUrl(searchList.first(), ctx)
            val searchLocalResult = runLocal(currentTab, searchHtml, rule, ctx)
            onUpdate(DebugPageUpdate(currentTab, "√ 搜索完成，取第一项: $firstBookUrl\n${searchLocalResult.output}", searchHtml, searchLocalResult))

            sessionRepository.saveData(
                Book(
                    url = firstBookUrl,
                    title = "",
                    author = "",
                    cover = "",
                    ruleId = rule.id,
                )
            )
            ctx = contextSessionManager.getActiveContext(sessionRepository, rule.id)

            currentTab = 3
            ctx["bookUrl"] = firstBookUrl
            onUpdate(DebugPageUpdate(currentTab, ">>> 正在抓取详情: $firstBookUrl ..."))
            val detailHtml = fetchOnIo { taskRunner.fetch(rule, rule.detail, firstBookUrl, ctx) }
            val detailLocalResult = runLocal(currentTab, detailHtml, rule, ctx)
            onUpdate(DebugPageUpdate(currentTab, ">>> 正在抓取详情: $firstBookUrl ...\n${detailLocalResult.output}", detailHtml, detailLocalResult))

            val bookName = rule.detail.getBookName(detailHtml, ctx)
            ctx["bookName"] = bookName
            ctx["bookCover"] = rule.detail.getBookCover(detailHtml, ctx)

            currentTab = 4
            val catalogUrl = rule.detail.getCatalogUrl(detailHtml, ctx)
            ctx["catalogUrl"] = catalogUrl
            val tocHtml = if (catalogUrl.isBlank()) {
                onUpdate(DebugPageUpdate(currentTab, "√ 详情与目录同页，跳过目录抓取"))
                detailHtml
            } else {
                onUpdate(DebugPageUpdate(currentTab, ">>> 正在抓取目录: $catalogUrl ..."))
                fetchOnIo { taskRunner.fetch(rule, rule.catalog, catalogUrl, ctx) }
            }

            val toc = rule.catalog.getChapters(tocHtml, ctx)
            if (toc.isEmpty()) {
                val catalogLocalResult = runLocal(currentTab, tocHtml, rule, ctx)
                onUpdate(DebugPageUpdate(currentTab, "√ 目录获取失败\n${catalogLocalResult.output}", tocHtml, catalogLocalResult))
                return FullChainDebugResult(success = false, context = ctx, errorTabIndex = currentTab, errorMessage = "目录为空")
            }

            val contentUrl = rule.catalog.getChapterUrl(toc.first(), ctx)
            val catalogLocalResult = runLocal(currentTab, tocHtml, rule, ctx)
            onUpdate(DebugPageUpdate(currentTab, "√ 目录获取完成，取第一项: $contentUrl\n${catalogLocalResult.output}", tocHtml, catalogLocalResult))

            currentTab = 5
            onUpdate(DebugPageUpdate(currentTab, ">>> 正文获取中: $contentUrl ..."))
            val contentHtml = fetchOnIo { taskRunner.fetch(rule, rule.content, contentUrl, ctx) }
            val contentLocalResult = runLocal(currentTab, contentHtml, rule, ctx)
            onUpdate(DebugPageUpdate(currentTab, contentLocalResult.output, contentHtml, contentLocalResult))

            FullChainDebugResult(success = true, context = ctx)
        } catch (e: Exception) {
            FullChainDebugResult(
                success = false,
                context = ctx,
                errorTabIndex = currentTab,
                errorMessage = e.message ?: e::class.simpleName ?: "未知错误"
            )
        }
    }

    suspend fun runNetwork(
        tabIndex: Int,
        input: String,
        rule: SourceRule,
        ctx: VariableContext,
        taskRunner: FetchTaskRunner
    ): NetworkDebugResult {
        val html = when (tabIndex) {
            1 -> taskRunner.fetchWithSession(rule, rule.login, input, ctx)
            2 -> taskRunner.fetch(rule, rule.search, input, ctx)
            3 -> taskRunner.fetch(rule, rule.detail, input, ctx)
            4 -> taskRunner.fetch(rule, rule.catalog, input, ctx)
            5 -> taskRunner.fetch(rule, rule.content, input, ctx)
            else -> ""
        }

        if (html.startsWith("ERROR")) {
            return NetworkDebugResult(
                html = html,
                localResult = LocalDebugResult("抓取失败: $html", emptyList()),
                isError = true
            )
        }

        return NetworkDebugResult(
            html = html,
            localResult = runLocal(tabIndex, html, rule, ctx),
            isError = false
        )
    }

    private suspend fun fetchOnIo(block: suspend () -> String): String {
        return withContext(Dispatchers.IO) { block() }
    }

    fun runLocal(tabIndex: Int, input: String, rule: SourceRule, ctx: VariableContext): LocalDebugResult {
        val log = StringBuilder()
        val traces = mutableListOf<ParseTraceEvent>()
        try {
            val html = input
            if (html.startsWith("ERROR")) {
                return LocalDebugResult(html, emptyList())
            }

            when (tabIndex) {
                1 -> {
                    val token = rule.login.getToken(html)
                    if (token.isNotBlank()) {
                        log.appendLine("√ token获取成功")
                        log.appendLine(token)
                    }
                }

                2 -> {
                    val list = traceList("SearchPage.listSelector", html, rule.search.listSelector, ctx, traces)
                    log.appendLine("√ 找到 ${list.size} 本书")

                    list.take(3).forEach {
                        val name = traceString("SearchPage.nameSelector", it, rule.search.nameSelector, ctx, traces)
                        val author = traceString("SearchPage.authorSelector", it, rule.search.authorSelector, ctx, traces)
                        val cover = traceString("SearchPage.coverSelector", it, rule.search.coverSelector, ctx, traces)
                        val detailUrl = traceString("SearchPage.detailUrlSelector", it, rule.search.detailUrlSelector, ctx, traces)
                        log.appendLine(" • 书名：$name\n\t\t\t作者：$author\n\t\t\t封面：$cover\n\t\t\t详细地址：$detailUrl")
                    }
                }

                3 -> {
                    val detail = rule.detail
                    val name = traceString("DetailPage.bookNameSelector", html, detail.bookNameSelector, ctx, traces)
                    ctx["bookName"] = name
                    val author = traceString("DetailPage.bookAuthorSelector", html, detail.bookAuthorSelector, ctx, traces)
                    val cover = traceString("DetailPage.bookCoverSelector", html, detail.bookCoverSelector, ctx, traces)
                    val labels = traceList("DetailPage.bookLabelSelector", html, detail.bookLabelSelector, ctx, traces)
                    val catalogUrl = traceString("DetailPage.catalogUrlSelector", html, detail.catalogUrlSelector, ctx, traces)

                    log.appendLine("√ 书本详细")
                    log.appendLine("书名：${name}\n作者：$author\n封面：$cover\n标签：$labels")
                    log.appendLine("书本章节URL -> $catalogUrl")
                }

                4 -> {
                    val chapters = traceList("CatalogPage.chapterListSelector", html, rule.catalog.chapterListSelector, ctx, traces)
                    log.appendLine("书本章节总数 -> ${chapters.size}")
                    chapters.forEach { chapter ->
                        val chapterName = traceString("CatalogPage.chapterNameSelector", chapter, rule.catalog.chapterNameSelector, ctx, traces)
                        val chapterUrl = traceString("CatalogPage.chapterUrlSelector", chapter, rule.catalog.chapterUrlSelector, ctx, traces)
                        log.appendLine("$chapterName >> $chapterUrl")
                    }
                }

                5 -> {
                    val text = traceList("ContentPage.contentSelector", html, rule.content.contentSelector, ctx, traces)
                    log.appendLine("√ 内容提取成功，共 ${text.size} 项")
                    text.take(20).forEachIndexed { index, item ->
                        log.appendLine("[$index] $item")
                    }
                }
            }
        } catch (e: Exception) {
            log.appendLine("异常: ${e.message}")
        }
        return LocalDebugResult(log.toString(), traces)
    }

    private fun traceString(
        selectorName: String,
        input: Any?,
        selector: Selector,
        ctx: VariableContext,
        traces: MutableList<ParseTraceEvent>
    ): String {
        val result = RuleParser.traceString(input, selector, ctx, selectorName)
        traces.addAll(result.events)
        return result.value
    }

    private fun traceList(
        selectorName: String,
        input: Any?,
        selector: Selector,
        ctx: VariableContext,
        traces: MutableList<ParseTraceEvent>
    ): List<Any> {
        val result = RuleParser.traceList(input, selector, ctx, selectorName)
        traces.addAll(result.events)
        return result.value
    }
}
