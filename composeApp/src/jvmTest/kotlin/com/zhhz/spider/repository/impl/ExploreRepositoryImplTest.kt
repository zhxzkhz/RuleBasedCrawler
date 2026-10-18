package com.zhhz.spider.repository.impl

import com.zhhz.spider.TestServer
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.db.SessionEntity
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.ExploreTab
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.FileSnapshotInterceptor
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.ExtractType
import com.zhhz.spider.rule.ExploreFixedTab
import com.zhhz.spider.rule.ExplorePage
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.SearchPage
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.StepType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreRepositoryImplTest {

    @Test
    fun fetchTabsAndBooksParsesExploreRule() = runTest {
        val server = TestServer(port = 18083).start()
        try {
            val rule = SourceRule(
                id = "explore-rule",
                name = "榜单书源",
                url = "${server.baseUrl}/explore",
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                explore = ExplorePage(
                    tabsSelector = css(".tab-item", ExtractType.ELEMENT),
                    tabNameSelector = css("a"),
                    tabUrlSelector = css("a", ExtractType.ATTR, "href"),
                    tabGroupSelector = css(".tab-group"),
                    listSelector = css(".book-item", ExtractType.ELEMENT),
                    nameSelector = css(".title"),
                    authorSelector = css(".author"),
                    coverSelector = css("img.cover", ExtractType.ATTR, "src"),
                    detailUrlSelector = css("a.title", ExtractType.ATTR, "href")
                )
            )
            val repository = ExploreRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeExploreRuleRepository(listOf(rule))
            )

            val tabs = repository.fetchTabs(rule.id)
            assertEquals(2, tabs.size)
            assertEquals("热门榜", tabs[0].title)
            assertEquals("男生", tabs[0].group)
            assertEquals("${server.baseUrl}/rank/hot", tabs[0].url)
            assertEquals("新作榜", tabs[1].title)
            assertEquals("女生", tabs[1].group)

            val books = repository.fetchBooks(rule.id, tabs[0].url)
            assertEquals(1, books.size)
            assertEquals("《榜单热门第一》", books[0].title)
            assertEquals("作者：王五", books[0].author)
            assertEquals(rule.id, books[0].ruleId)
        } finally {
            server.stop()
        }
    }

    @Test
    fun fetchBooksFallsBackToSearchListSelectorsWhenExploreListNotConfigured() = runTest {
        val server = TestServer(port = 18084).start()
        try {
            // explore 只配置分类标签；书籍列表结构复用 search 的配置
            val rule = SourceRule(
                id = "explore-fallback-rule",
                name = "榜单书源-回退",
                url = "${server.baseUrl}/explore",
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                search = SearchPage(
                    listSelector = css(".book-item", ExtractType.ELEMENT),
                    nameSelector = css(".title"),
                    authorSelector = css(".author"),
                    coverSelector = css("img.cover", ExtractType.ATTR, "src"),
                    detailUrlSelector = css("a.title", ExtractType.ATTR, "href")
                ),
                explore = ExplorePage(
                    tabsSelector = css(".tab-item", ExtractType.ELEMENT),
                    tabNameSelector = css("a"),
                    tabUrlSelector = css("a", ExtractType.ATTR, "href")
                )
            )
            val repository = ExploreRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeExploreRuleRepository(listOf(rule))
            )

            val tabs = repository.fetchTabs(rule.id)
            assertEquals(2, tabs.size)

            val books = repository.fetchBooks(rule.id, tabs[0].url)
            assertEquals(1, books.size)
            assertEquals("《榜单热门第一》", books[0].title)
            assertEquals("${server.baseUrl}/book/3001", books[0].url)
        } finally {
            server.stop()
        }
    }

    @Test
    fun fetchTabsUsesFixedTabsWithoutEntryFetch() = runTest {
        val server = TestServer(port = 18085).start()
        try {
            // 只配置固定分类 + 复用搜索页列表结构，入口页不应被请求
            val rule = SourceRule(
                id = "explore-fixed-rule",
                name = "固定榜单书源",
                url = "${server.baseUrl}/explore",
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                search = SearchPage(
                    listSelector = css(".book-item", ExtractType.ELEMENT),
                    nameSelector = css(".title"),
                    authorSelector = css(".author"),
                    coverSelector = css("img.cover", ExtractType.ATTR, "src"),
                    detailUrlSelector = css("a.title", ExtractType.ATTR, "href")
                ),
                explore = ExplorePage(
                    fixedTabs = listOf(
                        ExploreFixedTab(title = "热门榜", url = "${server.baseUrl}/rank/hot", group = "男生"),
                        ExploreFixedTab(title = " ", url = "https://ignored.example/blank"), // 无效项被过滤
                        ExploreFixedTab(title = "新作榜", url = "${server.baseUrl}/rank/new", group = "女生")
                    )
                )
            )
            val repository = ExploreRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeExploreRuleRepository(listOf(rule))
            )

            val tabs = repository.fetchTabs(rule.id)
            assertEquals(listOf("热门榜", "新作榜"), tabs.map { it.title })
            assertEquals(listOf("男生", "女生"), tabs.map { it.group })

            val books = repository.fetchBooks(rule.id, tabs[0].url)
            assertEquals(1, books.size)
            assertEquals("《榜单热门第一》", books[0].title)
        } finally {
            server.stop()
        }
    }

    @Test
    fun fetchTabsGeneratedByScriptWithoutEntryFetch() = runTest {
        val server = TestServer(port = 18086).start()
        try {
            val baseUrl = server.baseUrl
            val rule = SourceRule(
                id = "explore-script-rule",
                name = "脚本榜单书源",
                url = "${server.baseUrl}/explore",
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                search = SearchPage(
                    listSelector = css(".book-item", ExtractType.ELEMENT),
                    nameSelector = css(".title"),
                    authorSelector = css(".author"),
                    coverSelector = css("img.cover", ExtractType.ATTR, "src"),
                    detailUrlSelector = css("a.title", ExtractType.ATTR, "href")
                ),
                explore = ExplorePage(
                    tabsScript = """
                        var tabs = [];
                        for (var i = 1; i <= 2; i++) {
                            tabs.push({ title: '周榜' + i, url: '$baseUrl/rank/hot?page=' + i });
                        }
                        tabs;
                    """.trimIndent()
                )
            )
            val repository = ExploreRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeExploreRuleRepository(listOf(rule))
            )

            val tabs = repository.fetchTabs(rule.id)
            assertEquals(listOf("周榜1", "周榜2"), tabs.map { it.title })
            assertEquals("$baseUrl/rank/hot?page=1", tabs[0].url)

            // 脚本生成的地址可直接抓取书籍（列表结构复用搜索页）
            val books = repository.fetchBooks(rule.id, tabs[0].url)
            assertEquals(1, books.size)
            assertEquals("《榜单热门第一》", books[0].title)
        } finally {
            server.stop()
        }
    }

    @Test
    fun fetchBooksResolvesPageTemplateForPagination() = runTest {
        val server = TestServer(port = 18087).start()
        try {
            val rule = SourceRule(
                id = "explore-page-rule",
                name = "分页榜单书源",
                url = "${server.baseUrl}/explore",
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                search = SearchPage(
                    listSelector = css(".book-item", ExtractType.ELEMENT),
                    nameSelector = css(".title"),
                    authorSelector = css(".author"),
                    coverSelector = css("img.cover", ExtractType.ATTR, "src"),
                    detailUrlSelector = css("a.title", ExtractType.ATTR, "href")
                ),
                explore = ExplorePage(
                    fixedTabs = listOf(
                        ExploreFixedTab(title = "分页榜", url = "${server.baseUrl}/rank/hot?page={{page}}")
                    )
                )
            )
            val repository = ExploreRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeExploreRuleRepository(listOf(rule))
            )

            val tabs = repository.fetchTabs(rule.id)
            assertEquals(listOf("分页榜"), tabs.map { it.title })

            // {{page}} 模板按页码替换，返回不同页的书目
            val page1 = repository.fetchBooks(rule.id, tabs[0].url, page = 1)
            assertEquals("《榜单热门第一》", page1.single().title)

            val page2 = repository.fetchBooks(rule.id, tabs[0].url, page = 2)
            assertEquals("《榜单热门第二页》", page2.single().title)

            // 无模板的地址不受页码影响
            val staticTabs = listOf(ExploreTab(title = "静态榜", url = "${server.baseUrl}/rank/hot"))
            val again = repository.fetchBooks(rule.id, staticTabs[0].url, page = 5)
            assertEquals("《榜单热门第一》", again.single().title)
        } finally {
            server.stop()
        }
    }

    @Test
    fun fixedTabsAndScriptMergeDedupByUrlWithFixedFirst() = runTest {
        val server = TestServer(port = 18089).start()
        try {
            val baseUrl = server.baseUrl
            val rule = SourceRule(
                id = "explore-merge-rule",
                name = "合并榜单书源",
                url = "${server.baseUrl}/explore",
                concurrentRate = 0,
                useCache = false,
                globalConfig = FetchConfig(headers = emptyMap()),
                search = SearchPage(
                    listSelector = css(".book-item", ExtractType.ELEMENT),
                    nameSelector = css(".title"),
                    authorSelector = css(".author"),
                    coverSelector = css("img.cover", ExtractType.ATTR, "src"),
                    detailUrlSelector = css("a.title", ExtractType.ATTR, "href")
                ),
                explore = ExplorePage(
                    // 固定分类与 JS 脚本同时存在：合并、URL 去重、固定在前
                    fixedTabs = listOf(
                        ExploreFixedTab(title = "热门榜", url = "$baseUrl/rank/hot", group = "男生")
                    ),
                    tabsScript = """
                        var tabs = [];
                        tabs.push({ title: '重复榜', url: '$baseUrl/rank/hot' });
                        tabs.push({ group: '女生', title: '新作榜', url: '$baseUrl/rank/new' });
                        tabs;
                    """.trimIndent()
                )
            )
            val repository = ExploreRepositoryImpl(
                contextSessionManager = ContextSessionManager(),
                fetchTaskRunner = testRunner(),
                ruleRepository = FakeExploreRuleRepository(listOf(rule))
            )

            val tabs = repository.fetchTabs(rule.id)
            assertEquals(listOf("热门榜", "新作榜"), tabs.map { it.title })
            assertEquals(listOf("男生", "女生"), tabs.map { it.group })

            val books = repository.fetchBooks(rule.id, tabs[1].url)
            assertEquals("《榜单新作第一》", books.single().title)
        } finally {
            server.stop()
        }
    }

    private fun testRunner(): FetchTaskRunner {
        val cacheDir = Files.createTempDirectory("explore-repository-test-cache").toFile()
        return FetchTaskRunner(HttpFetcher(FileSnapshotInterceptor(cacheDir)), FakeExploreRuleDao())
    }

    private fun css(
        rule: String,
        extractType: ExtractType = ExtractType.TEXT,
        attr: String? = null
    ): Selector = Selector(
        steps = listOf(ParseStep(type = StepType.CSS, rule = rule, extractType = extractType, attr = attr))
    )
}

private class FakeExploreRuleRepository(
    private val rules: List<SourceRule>
) : RuleRepository {
    override fun loadData(): Flow<List<RuleEntity>> = flowOf(emptyList())
    override suspend fun syncRuleSubscription(url: String) = com.zhhz.spider.repository.RuleSubscriptionResult(0, 0, 0, "not implemented")

    override fun getEnabledRules(): List<SourceRule> = rules
    override suspend fun saveData(rule: RuleEntity) = Unit
    override suspend fun deleteData(rule: RuleEntity) = Unit
}

private class FakeExploreRuleDao : RuleDao {
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
