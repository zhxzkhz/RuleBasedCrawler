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
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.rule.SourceRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
}

private class FakeRuleRepository(
    private val rules: List<SourceRule>
) : RuleRepository {
    override fun loadData(): Flow<List<RuleEntity>> = flowOf(emptyList())

    override fun getEnabledRules(): List<SourceRule> = rules

    override suspend fun saveData(rule: RuleEntity) = Unit

    override suspend fun deleteData(rule: RuleEntity) = Unit
}

private class FakeRuleDao : RuleDao {
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

private class FakeBookDao : BookDao {
    override suspend fun addToBookshelf(book: BookEntity) = Unit

    override fun getAllBooksFlow(): Flow<List<BookEntity>> = flowOf(emptyList())

    override fun getBookFlow(url: String): Flow<BookEntity?> = flowOf(null)

    override suspend fun getBookByUrl(url: String): BookEntity? = null

    override suspend fun removeFromBookshelf(book: BookEntity) = Unit

    override suspend fun deleteBookByUrl(url: String) = Unit

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
}
