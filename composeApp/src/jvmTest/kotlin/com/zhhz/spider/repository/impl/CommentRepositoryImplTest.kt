package com.zhhz.spider.repository.impl

import com.zhhz.spider.TestServer
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.db.SessionEntity
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.FileSnapshotInterceptor
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.CommentPage
import com.zhhz.spider.rule.ExtractType
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.StepType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CommentRepositoryImplTest {

    @Test
    fun fetchCommentsParsesListWithOptionalFields() = runTest {
        val server = TestServer(port = 18090).start()
        try {
            val baseUrl = server.baseUrl
            val rule = SourceRule(
                id = "comment-rule",
                name = "评论书源",
                url = server.baseUrl,
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                comment = CommentPage(
                    urlSelector = Selector(
                        steps = listOf(
                            ParseStep(type = StepType.TEMPLATE, rule = "$baseUrl/comments?key={{key}}")
                        )
                    ),
                    listSelector = css(".comment-item", ExtractType.ELEMENT),
                    userSelector = css(".comment-user"),
                    contentSelector = css(".comment-content"),
                    timeSelector = css(".comment-time"),
                    scoreSelector = css(".comment-score")
                )
            )
            val repository = CommentRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeCommentRuleRepository(listOf(rule))
            )

            val comments = repository.fetchComments(rule.id, "${server.baseUrl}/book/1001")

            assertEquals(2, comments.size)
            assertEquals("书友甲", comments[0].user)
            assertEquals("写得不错，追更中！", comments[0].content)
            assertEquals("4.5分", comments[0].score)
            assertEquals("2024-01-01", comments[0].time)
            // 可选字段缺失时为空串
            assertEquals("书友乙", comments[1].user)
            assertEquals("", comments[1].score)
            assertEquals("", comments[1].time)
        } finally {
            server.stop()
        }
    }

    @Test
    fun fetchCommentsFlattensNestedReplys() = runTest {
        val server = TestServer(port = 18091).start()
        try {
            val baseUrl = server.baseUrl
            val rule = SourceRule(
                id = "comment-reply-rule",
                name = "楼中楼书源",
                url = server.baseUrl,
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                comment = CommentPage(
                    urlSelector = Selector(
                        steps = listOf(
                            ParseStep(type = StepType.TEMPLATE, rule = "$baseUrl/comments-json")
                        )
                    ),
                    listSelector = jsonPath("$.list[*]"),
                    userSelector = jsonPath("$.nickname"),
                    contentSelector = Selector(
                        steps = listOf(
                            jsonPath("$.content").steps.first(),
                            replaceTags()
                        )
                    ),
                    timeSelector = jsonPath("$.addtime"),
                    replyListSelector = jsonPath("$.replys[*]")
                )
            )
            val repository = CommentRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeCommentRuleRepository(listOf(rule))
            )

            val comments = repository.fetchComments(rule.id, "${server.baseUrl}/book/1001")

            // 嵌套结构：顶层 1 条，其 replys 内含 1 条楼中楼
            assertEquals(1, comments.size)
            val top = comments.first()
            assertEquals("海月薰", top.user)
            assertEquals("比租借女友纯爱", top.content)
            assertEquals(1, top.replys.size)

            val reply = top.replys.first()
            assertEquals("路人丙", reply.user)
            assertEquals("明明就是纯爱", reply.content)
            // 总条数含回复
            assertEquals(2, top.totalCount())
        } finally {
            server.stop()
        }
    }

    /** JSON 取值步骤 */
    private fun jsonPath(rule: String): Selector = Selector(
        steps = listOf(ParseStep(type = StepType.JSON, rule = rule))
    )

    /** 去除 HTML 标签 */
    private fun replaceTags(): ParseStep = ParseStep(
        type = StepType.REPLACE,
        rule = "<[^>]*>",
        replacement = ""
    )

    private fun testRunner(): FetchTaskRunner {
        val cacheDir = Files.createTempDirectory("comment-repository-test-cache").toFile()
        return FetchTaskRunner(HttpFetcher(FileSnapshotInterceptor(cacheDir)), FakeCommentRuleDao())
    }

    private fun css(
        rule: String,
        extractType: ExtractType = ExtractType.TEXT,
        attr: String? = null
    ): Selector = Selector(
        steps = listOf(ParseStep(type = StepType.CSS, rule = rule, extractType = extractType, attr = attr))
    )
}

private class FakeCommentRuleRepository(
    private val rules: List<SourceRule>
) : RuleRepository {
    override fun loadData(): Flow<List<RuleEntity>> = flowOf(emptyList())
    override suspend fun syncRuleSubscription(url: String) = com.zhhz.spider.repository.RuleSubscriptionResult(0, 0, 0, "not implemented")

    override fun getEnabledRules(): List<SourceRule> = rules
    override suspend fun saveData(rule: RuleEntity) = Unit
    override suspend fun deleteData(rule: RuleEntity) = Unit
}

private class FakeCommentRuleDao : RuleDao {
    override suspend fun saveRule(rule: RuleEntity) = Unit
    override fun getAllRulesFlow(): Flow<List<RuleEntity>> = flowOf(emptyList())
    override suspend fun getAllRules(): List<RuleEntity> = emptyList()
    override suspend fun getRuleById(id: String): RuleEntity? = null
    override suspend fun deleteRule(rule: RuleEntity) = Unit
    override suspend fun getSession(ruleId: String): SessionEntity? = null
    override fun getSessionFlow(ruleId: String): Flow<SessionEntity?> = flowOf(null)
    override suspend fun saveSession(session: SessionEntity) = Unit
    override suspend fun deleteSession(ruleId: String) = Unit
}
