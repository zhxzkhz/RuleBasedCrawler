package com.zhhz.spider

import com.zhhz.spider.debug.DebugPageUpdate
import com.zhhz.spider.debug.RuleDebugRunner
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.db.SessionEntity
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.FileSnapshotInterceptor
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.CatalogPage
import com.zhhz.spider.rule.ContentPage
import com.zhhz.spider.rule.DetailPage
import com.zhhz.spider.rule.ExtractType
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.SearchPage
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.StepType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrawlerIntegrationTest {

    @Test
    fun ruleFetchesSearchDetailCatalogAndContent() = runTest {
        val server = TestServer(port = 18080).start()
        try {
            val runner = testRunner()
            val rule = testSourceRule(server.baseUrl)
            val ctx = mutableMapOf("page" to "1")

            val searchHtml = runner.fetch(rule, rule.search, "hot", ctx)
            assertFalse(searchHtml.startsWith("ERROR:"), searchHtml)

            val searchItems = rule.search.getList(searchHtml, ctx)
            assertEquals(2, searchItems.size)
            assertEquals("《测试小说：hot神话》", rule.search.getName(searchItems.first(), ctx))
            assertEquals("作者：张三", rule.search.getAuthor(searchItems.first(), ctx))

            val detailUrl = rule.search.getDetailUrl(searchItems.first(), ctx)
            assertEquals("${server.baseUrl}/book/1001", detailUrl)

            val detailHtml = runner.fetch(rule, rule.detail, detailUrl, ctx)
            assertFalse(detailHtml.startsWith("ERROR:"), detailHtml)
            assertEquals("测试小说：热血神话", rule.detail.getBookName(detailHtml, ctx))
            assertEquals("张三", rule.detail.getBookAuthor(detailHtml, ctx))

            val catalogUrl = rule.detail.getCatalogUrl(detailHtml, ctx)
            assertEquals("${server.baseUrl}/book/1001/catalog", catalogUrl)

            val catalogHtml = runner.fetch(rule, rule.catalog, catalogUrl, ctx)
            assertFalse(catalogHtml.startsWith("ERROR:"), catalogHtml)

            val chapters = rule.catalog.getChapters(catalogHtml, ctx)
            assertEquals(3, chapters.size)
            assertEquals("第一章：陨落的天才", rule.catalog.getChapterName(chapters.first(), ctx))

            val chapterUrl = rule.catalog.getChapterUrl(chapters.first(), ctx)
            assertEquals("${server.baseUrl}/chapter/1001_1", chapterUrl)

            val contentHtml = runner.fetch(rule, rule.content, chapterUrl, ctx)
            assertFalse(contentHtml.startsWith("ERROR:"), contentHtml)

            val content = rule.content.getContent(contentHtml, ctx).joinToString("\n")
            assertTrue(content.contains("莫欺少年穷"), content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun ruleDebugRunnerRunsFullChainAndEmitsPageUpdates() = runTest {
        val server = TestServer(port = 18082).start()
        try {
            val updates = mutableListOf<DebugPageUpdate>()
            val result = RuleDebugRunner.runFullChain(
                input = "hot",
                rule = testSourceRule(server.baseUrl),
                initialContext = mutableMapOf("page" to "1"),
                taskRunner = testRunner(),
                contextSessionManager = ContextSessionManager(),
                sessionRepository = FakeSessionRepository(),
                onUpdate = { updates.add(it) }
            )

            assertTrue(result.success, result.errorMessage.orEmpty())
            assertEquals(listOf(2, 3, 4, 5), updates.filter { it.html != null }.map { it.tabIndex })
            assertTrue(updates.last().message.contains("内容提取成功"))
            assertTrue(updates.any { update ->
                update.localResult?.traces?.any { it.selectorName == "ContentPage.contentSelector" } == true
            })
        } finally {
            server.stop()
        }
    }

    private fun testRunner(): FetchTaskRunner {
        val cacheDir = Files.createTempDirectory("crawler-integration-cache").toFile()
        return FetchTaskRunner(HttpFetcher(FileSnapshotInterceptor(cacheDir)), IntegrationRuleDao())
    }

    private fun testSourceRule(baseUrl: String): SourceRule {
        return SourceRule(
            id = "test-rule",
            name = "测试书源",
            url = baseUrl,
            concurrentRate = 0,
            useCache = false,
            globalConfig = FetchConfig(headers = emptyMap()),
            search = SearchPage(
                urlSelector = selector(StepType.TEMPLATE, "search?q={{key}}&page={{page}}"),
                listSelector = css(".book-item", ExtractType.ELEMENT),
                nameSelector = css(".title"),
                authorSelector = css(".author"),
                coverSelector = css("img.cover", ExtractType.ATTR, "src"),
                detailUrlSelector = css("a.title", ExtractType.ATTR, "href")
            ),
            detail = DetailPage(
                bookNameSelector = css(".book-title"),
                bookAuthorSelector = css(".author"),
                bookIntroSelector = css(".book-desc"),
                catalogUrlSelector = css(".catalog-link", ExtractType.ATTR, "href")
            ),
            catalog = CatalogPage(
                chapterListSelector = css(".chapter-list li", ExtractType.ELEMENT),
                chapterNameSelector = css("a"),
                chapterUrlSelector = css("a", ExtractType.ATTR, "href")
            ),
            content = ContentPage(
                contentSelector = css(".chapter-content")
            )
        )
    }

    private fun css(
        rule: String,
        extractType: ExtractType = ExtractType.TEXT,
        attr: String? = null
    ): Selector {
        return selector(StepType.CSS, rule, extractType, attr)
    }

    private fun selector(
        type: StepType,
        rule: String,
        extractType: ExtractType = ExtractType.TEXT,
        attr: String? = null
    ): Selector {
        return Selector(steps = listOf(ParseStep(type = type, rule = rule, extractType = extractType, attr = attr)))
    }
}

private class FakeSessionRepository : SessionRepository {
    private var book: Book? = null
    private var catalog: List<Chapter> = emptyList()

    override suspend fun saveData(book: Book) {
        this.book = book
    }

    override fun loadData(): Book? = book

    override fun clearData() {
        book = null
        catalog = emptyList()
    }

    override fun saveCatalog(chapters: List<Chapter>) {
        catalog = chapters
    }

    override fun loadCatalog(): List<Chapter> = catalog
}

private class IntegrationRuleDao : RuleDao {
    private val sessions = mutableMapOf<String, SessionEntity>()
    private val sessionFlows = mutableMapOf<String, MutableStateFlow<SessionEntity?>>()

    override suspend fun saveRule(rule: RuleEntity) = Unit

    override fun getAllRulesFlow(): Flow<List<RuleEntity>> = flowOf(emptyList())

    override suspend fun getRuleById(id: String): RuleEntity? = null

    override suspend fun deleteRule(rule: RuleEntity) = Unit

    override suspend fun getSession(ruleId: String): SessionEntity? = sessions[ruleId]

    override fun getSessionFlow(ruleId: String): Flow<SessionEntity?> {
        return sessionFlows.getOrPut(ruleId) { MutableStateFlow(sessions[ruleId]) }
    }

    override suspend fun saveSession(session: SessionEntity) {
        sessions[session.ruleId] = session
        sessionFlows[session.ruleId]?.value = session
    }

    override suspend fun deleteSession(ruleId: String) {
        sessions.remove(ruleId)
        sessionFlows[ruleId]?.value = null
    }
}
