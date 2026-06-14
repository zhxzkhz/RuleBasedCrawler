package com.zhhz.spider.network

import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchTaskRunnerTest {

    @Test
    fun combineUrlKeepsAbsoluteUrls() {
        val runner = testRunner()

        assertEquals(
            "https://cdn.example.com/image.jpg",
            runner.combineUrl("https://example.com/books/1", "https://cdn.example.com/image.jpg")
        )
    }

    @Test
    fun combineUrlResolvesRootRelativeUrls() {
        val runner = testRunner()

        assertEquals(
            "https://example.com/chapter/1",
            runner.combineUrl("https://example.com/books/100/catalog", "/chapter/1")
        )
    }

    @Test
    fun combineUrlResolvesParentRelativeUrls() {
        val runner = testRunner()

        assertEquals(
            "https://example.com/books/chapter/2",
            runner.combineUrl("https://example.com/books/100/catalog", "../chapter/2")
        )
    }

    @Test
    fun combineUrlResolvesProtocolRelativeUrlsWithBaseScheme() {
        val runner = testRunner()

        assertEquals(
            "http://cdn.example.com/page.jpg",
            runner.combineUrl("http://example.com/book/1", "//cdn.example.com/page.jpg")
        )
    }

    @Test
    fun combineUrlFallsBackWhenBaseIsBlank() {
        val runner = testRunner()

        assertEquals("/chapter/1", runner.combineUrl("", "/chapter/1"))
    }

    private fun testRunner(): FetchTaskRunner {
        val cacheDir = Files.createTempDirectory("rule-fetcher-test").toFile()
        return FetchTaskRunner(HttpFetcher(FileSnapshotInterceptor(cacheDir)), FakeRuleDao())
    }
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
