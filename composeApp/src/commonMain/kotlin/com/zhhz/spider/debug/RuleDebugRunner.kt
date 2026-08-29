package com.zhhz.spider.debug

import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.repository.impl.ExploreTabResolver
import com.zhhz.spider.rule.ParseTraceEvent
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import com.zhhz.spider.rule.exploreAuthorSelector
import com.zhhz.spider.rule.exploreDetailUrlSelector
import com.zhhz.spider.rule.exploreListSelector
import com.zhhz.spider.rule.exploreNameSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class LocalDebugResult(
    val output: String,
    val traces: List<ParseTraceEvent>
)

@Serializable
data class NetworkDebugResult(
    val html: String,
    val localResult: LocalDebugResult,
    val isError: Boolean
)

@Serializable
data class DebugPageUpdate(
    val tabIndex: Int,
    val message: String,
    val html: String? = null,
    val localResult: LocalDebugResult? = null
)

@Serializable
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
            // 💡 发现页调试：
            // ① 输入框填了地址 → 直接抓该地址（测试某个具体分类的列表页）
            // ② 输入为空 → 先执行静态分类生成；失败则直接报出脚本错误，不再误抓首页
            // ③ 静态成功 → 自动取第一个分类的地址验证列表抓取与解析
            // ④ 都没有 → 回退入口页（urlSelector 模板或书源主页）
            6 -> {
                if (input.isNotBlank()) {
                    taskRunner.fetch(rule, rule.explore, input, ctx)
                } else {
                    val staticTabs = ExploreTabResolver.resolveStatic(rule, ctx)
                    if (staticTabs.isEmpty() &&
                        (rule.explore.fixedTabs.any { it.title.isNotBlank() && it.url.isNotBlank() } ||
                                !rule.explore.tabsScript.isNullOrBlank())
                    ) {
                        return NetworkDebugResult(
                            html = "静态分类生成失败：${ExploreTabResolver.lastEvalError ?: "返回为空"}\n" +
                                    "修复脚本后再测试列表抓取。",
                            localResult = LocalDebugResult("静态分类生成失败", emptyList()),
                            isError = true
                        )
                    }
                    val entryUrl = staticTabs.firstOrNull()?.url
                        ?: rule.explore.getExploreUrl(rule.url, ctx).ifBlank { rule.url }
                    taskRunner.fetch(rule, rule.explore, entryUrl, ctx)
                }
            }
            // 💡 评论页：输入为空时用书籍详情 URL 生成评论地址（{{key}} 模板）
            7 -> {
                val commentUrl = if (input.isNotBlank()) {
                    input
                } else {
                    rule.comment.getCommentUrl(ctx["bookUrl"] ?: rule.url, ctx)
                }
                taskRunner.fetch(rule, rule.comment, commentUrl, ctx)
            }
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
                    val  description = traceString("DetailPage.bookIntroSelector", html, detail.bookIntroSelector, ctx, traces)
                    log.appendLine("√ 书本详细")
                    log.appendLine("书名：${name}\n作者：$author\n封面：$cover\n标签：$labels\n简介：$description")
                    log.appendLine("书本章节URL -> $catalogUrl")
                }

                4 -> {
                    val chapters = traceList("CatalogPage.chapterListSelector", html, rule.catalog.chapterListSelector, ctx, traces)
                    log.appendLine("书本章节总数 -> ${chapters.size}")
                    chapters.forEach { chapter ->
                        val chapterName = traceString("CatalogPage.chapterNameSelector", chapter, rule.catalog.chapterNameSelector, ctx, traces)
                        val chapterUrl = traceString("CatalogPage.chapterUrlSelector", chapter, rule.catalog.chapterUrlSelector, ctx, traces)
                        val chapterGroup = if (rule.catalog.chapterGroupSelector.steps.isNotEmpty()) {
                            traceString("CatalogPage.chapterGroupSelector", chapter, rule.catalog.chapterGroupSelector, ctx, traces)
                        } else ""
                        if (chapterGroup.isNotBlank()) {
                            log.appendLine("[$chapterGroup] $chapterName >> $chapterUrl")
                        } else {
                            log.appendLine("$chapterName >> $chapterUrl")
                        }
                    }
                }

                5 -> {
                    val text = traceList("ContentPage.contentSelector", html, rule.content.contentSelector, ctx, traces)
                    log.appendLine("√ 内容提取成功，共 ${text.size} 项")
                    text.take(20).forEachIndexed { index, item ->
                        log.appendLine("[$index] $item")
                    }

                    if (rule.content.nextUrlSelector.steps.isNotEmpty()) {
                        val nextUrl = traceString("ContentPage.nextUrlSelector", html, rule.content.nextUrlSelector, ctx, traces)
                        if (nextUrl.isNotBlank()) log.appendLine("√ 下一页/章 URL: $nextUrl")
                    }

                    if (rule.content.imageHeaders.steps.isNotEmpty()) {
                        val headers = traceString("ContentPage.imageHeaders", html, rule.content.imageHeaders, ctx, traces)
                        if (headers.isNotBlank()) log.appendLine("√ 图片请求头: $headers")
                    }

                    if (rule.content.regexReplaceSelector.steps.isNotEmpty()) {
                        val replaces = traceList("ContentPage.regexReplaceSelector", html, rule.content.regexReplaceSelector, ctx, traces)
                        if (replaces.isNotEmpty()) log.appendLine("√ 正则替换规则: $replaces")
                    }
                }

                6 -> {
                    val explore = rule.explore

                    // 静态分类（写死列表 / JS 生成）直接输出
                    val staticTabs = ExploreTabResolver.resolveStatic(rule, ctx)
                    if (staticTabs.isNotEmpty()) {
                        log.appendLine("√ 静态/脚本分类 ${staticTabs.size} 个")
                        staticTabs.forEach { log.appendLine(" • ${it.title} >> ${it.url}") }
                    } else if (explore.fixedTabs.any { it.title.isNotBlank() && it.url.isNotBlank() } ||
                        !explore.tabsScript.isNullOrBlank()
                    ) {
                        log.appendLine("⚠ 固定分类/JS 脚本已配置但未生成任何有效分类")
                        log.appendLine("  原因：${ExploreTabResolver.lastEvalError ?: "返回为空"}")
                        log.appendLine("  JS 返回值必须是 [{group,title,url}] 对象数组，且 title/url 非空")
                    }

                    if (explore.tabsSelector.steps.isNotEmpty()) {
                        val tabs = traceList("ExplorePage.tabsSelector", html, explore.tabsSelector, ctx, traces)
                        log.appendLine("√ 找到 ${tabs.size} 个分类")
                        tabs.take(8).forEach {
                            val name = traceString("ExplorePage.tabNameSelector", it, explore.tabNameSelector, ctx, traces)
                            val url = traceString("ExplorePage.tabUrlSelector", it, explore.tabUrlSelector, ctx, traces)
                            if (explore.tabGroupSelector.steps.isNotEmpty()) {
                                val group = traceString("ExplorePage.tabGroupSelector", it, explore.tabGroupSelector, ctx, traces)
                                log.appendLine(" • [$group] $name >> $url")
                            } else {
                                log.appendLine(" • $name >> $url")
                            }
                        }
                    }

                    // 榜单列表结构未配置时回退复用搜索页选择器
                    val listSelector = rule.exploreListSelector()
                    if (explore.listSelector.steps.isEmpty() && listSelector.steps.isNotEmpty()) {
                        log.appendLine("√ 书籍列表未单独配置，复用搜索页列表结构")
                    }
                    val books = traceList("ExplorePage.listSelector", html, listSelector, ctx, traces)
                    if (books.isNotEmpty()) {
                        log.appendLine("√ 书籍列表 ${books.size} 项")
                        books.take(3).forEach { item ->
                            val name = traceString("ExplorePage.nameSelector", item, rule.exploreNameSelector(), ctx, traces)
                            val author = traceString("ExplorePage.authorSelector", item, rule.exploreAuthorSelector(), ctx, traces)
                            val detailUrl = traceString("ExplorePage.detailUrlSelector", item, rule.exploreDetailUrlSelector(), ctx, traces)
                            log.appendLine(" • 书名：$name\n\t\t\t作者：$author\n\t\t\t详细地址：$detailUrl")
                        }
                    }
                }

                7 -> {
                    val comment = rule.comment
                    val comments = traceList("CommentPage.listSelector", html, comment.listSelector, ctx, traces)
                    log.appendLine("√ 找到 ${comments.size} 条评论")

                    // 💡 递归输出评论 + 楼中楼（与仓库层平铺逻辑一致）
                    fun appendCommentItem(item: Any, depth: Int) {
                        val user = traceString("CommentPage.userSelector", item, comment.userSelector, ctx, traces)
                        val content = traceString("CommentPage.contentSelector", item, comment.contentSelector, ctx, traces)
                        val time = if (comment.timeSelector.steps.isNotEmpty()) {
                            traceString("CommentPage.timeSelector", item, comment.timeSelector, ctx, traces)
                        } else ""
                        val score = if (comment.scoreSelector.steps.isNotEmpty()) {
                            traceString("CommentPage.scoreSelector", item, comment.scoreSelector, ctx, traces)
                        } else ""

                        val indent = "    ".repeat(depth)
                        val head = buildString {
                            append(indent)
                            append(if (depth > 0) "↳ " else "• ")
                            append(user)
                            if (score.isNotBlank()) append(" ($score)")
                            if (time.isNotBlank()) append("  $time")
                        }
                        log.appendLine(head)
                        log.appendLine("$indent\t\t\t$content")

                        if (comment.replyListSelector.steps.isNotEmpty()) {
                            val replies = traceList("CommentPage.replyListSelector", item, comment.replyListSelector, ctx, traces)
                            replies.forEach { appendCommentItem(it, depth + 1) }
                        }
                    }

                    comments.take(5).forEach { appendCommentItem(it, 0) }
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
