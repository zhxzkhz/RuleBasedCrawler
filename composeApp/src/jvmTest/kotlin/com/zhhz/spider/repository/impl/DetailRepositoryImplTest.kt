package com.zhhz.spider.repository.impl

import com.zhhz.spider.TestServer
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.ChapterEntity
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.db.SessionEntity
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.FileSnapshotInterceptor
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.network.BookDetail
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.rule.SourceRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailRepositoryImplTest {

    @Test
    fun fetchDataThrowsStageErrorWhenHttpRequestFails() = runTest {
        val server = TestServer(port = 18081).start()
        try {
            val rule = SourceRule(
                id = "detail-error-rule",
                name = "错误书源",
                url = server.baseUrl,
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap())
            )
            val repository = DetailRepositoryImpl(
                bookDao = FakeBookDao(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeRuleRepository(listOf(rule)),
                contextSessionManager = ContextSessionManager()
            )

            val error = assertFailsWith<IllegalStateException> {
                repository.fetchData("${server.baseUrl}/missing-detail", rule.id)
            }

            assertTrue(error.message?.contains("错误书源 详情失败") == true, error.message ?: "")
            assertTrue(error.message?.contains("HTTP 404") == true, error.message ?: "")
        } finally {
            server.stop()
        }
    }

    private fun testRunner(): FetchTaskRunner {
        val cacheDir = Files.createTempDirectory("detail-repository-test-cache").toFile()
        return FetchTaskRunner(HttpFetcher(FileSnapshotInterceptor(cacheDir)), FakeRuleDao())
    }

    @Test
    fun saveDetailInfoPersistsAndLoadLocalDetailRoundTrips() = runTest {
        val book = BookEntity(
            detailUrl = "https://example.com/book/1",
            ruleId = "rule-1",
            title = "原标题",
            author = "原作者",
            cover = "cover.jpg"
        )
        val repository = DetailRepositoryImpl(
            bookDao = FakeBookDao(listOf(book)),
            fetchTaskRunner = testRunner(),
            ruleRepository = FakeRuleRepository(emptyList()),
            contextSessionManager = ContextSessionManager()
        )

        repository.saveDetailInfo(
            book.detailUrl,
            BookDetail(
                url = book.detailUrl,
                title = "",
                author = "",
                cover = "",
                desc = "测试简介",
                status = "",
                latestChapterTitle = "",
                catalogUrl = "https://example.com/book/1/catalog",
                labels = listOf("热血", "冒险")
            )
        )

        val local = repository.loadLocalDetail(book.detailUrl)
        assertTrue(local != null)
        assertEquals("测试简介", local.desc)
        assertEquals("https://example.com/book/1/catalog", local.catalogUrl)
        assertEquals(listOf("热血", "冒险"), local.labels)
        // 网络返回的空字段不应覆盖本地已有值
        assertEquals("原标题", local.title)
        assertEquals("原作者", local.author)

        // 第二次保存：标签与简介为空时，保留数据库已有值（合并语义）
        repository.saveDetailInfo(
            book.detailUrl,
            BookDetail(
                url = book.detailUrl,
                title = "",
                author = "",
                cover = "",
                desc = "",
                status = "",
                latestChapterTitle = "",
                catalogUrl = "",
                labels = emptyList()
            )
        )
        val merged = repository.loadLocalDetail(book.detailUrl)
        assertTrue(merged != null)
        assertEquals(listOf("热血", "冒险"), merged.labels)
        assertEquals("测试简介", merged.desc)
        assertEquals("https://example.com/book/1/catalog", merged.catalogUrl)

        // 未收藏过的书不会因浏览详情而生成记录
        repository.saveDetailInfo(
            "https://example.com/book/2",
            BookDetail(
                url = "https://example.com/book/2",
                title = "新书",
                author = "",
                cover = "",
                desc = "d",
                status = "",
                latestChapterTitle = "",
                catalogUrl = "",
                labels = emptyList()
            )
        )
        assertNull(repository.loadLocalDetail("https://example.com/book/2"))
    }
}

private class FakeRuleRepository(
    private val rules: List<SourceRule>
) : RuleRepository {
    override fun loadData(): Flow<List<RuleEntity>> = flowOf(emptyList())

    override suspend fun syncRuleSubscription(url: String) = com.zhhz.spider.repository.RuleSubscriptionResult(0, 0, 0, "not implemented")

    override fun getEnabledRules(): List<SourceRule> = rules

    override suspend fun saveData(rule: RuleEntity) = Unit

    override suspend fun deleteData(rule: RuleEntity) = Unit
}

private class FakeRuleDao : RuleDao {
    private val sessions = mutableMapOf<String, SessionEntity>()
    private val sessionFlows = mutableMapOf<String, MutableStateFlow<SessionEntity?>>()

    override suspend fun saveRule(rule: RuleEntity) = Unit

    override fun getAllRulesFlow(): Flow<List<RuleEntity>> = flowOf(emptyList())

    override suspend fun getAllRules(): List<RuleEntity> = emptyList()

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

private class FakeBookDao(
    initialBooks: List<BookEntity> = emptyList()
) : BookDao {
    private val books = LinkedHashMap<String, BookEntity>().apply {
        initialBooks.forEach { put(it.detailUrl, it) }
    }

    override suspend fun addToBookshelf(book: BookEntity) {
        books[book.detailUrl] = book
    }

    override fun getAllBooksFlow(): Flow<List<BookEntity>> = flowOf(books.values.toList())

    override fun getBookFlow(url: String): Flow<BookEntity?> = flowOf(books[url])

    override suspend fun getBookByUrl(url: String): BookEntity? = books[url]

    override suspend fun getBooksByUrls(urls: List<String>): List<BookEntity> =
        urls.mapNotNull { books[it] }

    override suspend fun getHistoryBooks(): List<BookEntity> =
        books.values.filter { !it.inLibrary }

    override suspend fun removeFromBookshelf(book: BookEntity) {
        books.remove(book.detailUrl)
    }

    override suspend fun deleteBookByUrl(url: String) {
        books.remove(url)
    }

    override suspend fun updateReadProgress(
        bookUrl: String,
        chapterIndex: Int,
        pageIndex: Int,
        chapterTitle: String,
        chapterUrl: String,
        updateTime: Long
    ) = Unit

    override fun getChaptersFlow(bookUrl: String): Flow<List<ChapterEntity>> = flowOf(emptyList())
    override suspend fun markAsRead(bookUrl: String, chapterUrl: String) = Unit

    override suspend fun insertChaptersIgnore(chapters: List<ChapterEntity>): List<Long> = emptyList()

    override suspend fun updateChapterMetadata(bookUrl: String, chapterUrl: String, name: String, index: Int) = Unit

    override suspend fun addToGroup(urls: List<String>, mask: Long) = Unit

    override suspend fun removeFromGroup(urls: List<String>, mask: Long) = Unit

    override suspend fun clearGroupFromAllBooks(mask: Long) = Unit

    override suspend fun setInLibrary(urls: List<String>, inLibrary: Boolean) = Unit

    override suspend fun updateTotalChapters(bookUrl: String, totalChapters: Int) = Unit
}
