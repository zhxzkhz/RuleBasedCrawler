package com.zhhz.spider.network

import com.sun.net.httpserver.HttpServer
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.db.SessionEntity
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.rule.LoginPage
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.SearchPage
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.StepType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun autoLoginUsesNewTokenForCurrentFetch() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val loginRequests = AtomicInteger()
        var searchAuthorization: String? = null

        server.createContext("/login") { exchange ->
            loginRequests.incrementAndGet()
            exchange.sendText(200, "{}")
        }
        server.createContext("/search") { exchange ->
            searchAuthorization = exchange.requestHeaders.getFirst("Authorization")
            if (searchAuthorization == "Bearer fresh-token") {
                exchange.sendText(200, "results")
            } else {
                exchange.sendText(401, "missing token")
            }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val dao = FakeRuleDao()
            val cacheDir = Files.createTempDirectory("rule-fetcher-login-test").toFile()
            val runner = FetchTaskRunner(HttpFetcher(FileSnapshotInterceptor(cacheDir)), dao)
            val constant: (String) -> Selector = { value ->
                Selector(steps = listOf(ParseStep(type = StepType.CONSTANT, rule = value)))
            }
            val source = SourceRule(
                id = "login-source",
                name = "Login source",
                url = baseUrl,
                concurrentRate = 0,
                useCache = false,
                requireLogin = true,
                globalConfig = FetchConfig(headers = mapOf("Authorization" to "Bearer {{token}}")),
                login = LoginPage(
                    config = FetchConfig(method = "GET"),
                    urlSelector = constant("$baseUrl/login"),
                    tokenSelector = constant("fresh-token")
                ),
                search = SearchPage(config = FetchConfig(method = "GET"))
            )
            val context = mutableMapOf<String, String>()

            val response = runner.fetch(source, source.search, "$baseUrl/search", context)

            assertEquals("results", response)
            assertEquals("fresh-token", context["token"])
            assertEquals("fresh-token", dao.getSession(source.id)?.tokenValue)
            assertEquals("Bearer fresh-token", searchAuthorization)
            assertEquals(1, loginRequests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun testRunner(): FetchTaskRunner {
        val cacheDir = Files.createTempDirectory("rule-fetcher-test").toFile()
        return FetchTaskRunner(HttpFetcher(FileSnapshotInterceptor(cacheDir)), FakeRuleDao())
    }
}

private fun com.sun.net.httpserver.HttpExchange.sendText(status: Int, body: String) {
    val bytes = body.encodeToByteArray()
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
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
