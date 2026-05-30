package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.BookDao
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.*
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 搜索功能的具体实现
class SearchRepositoryImpl(
    private val contextSessionManager: ContextSessionManager,
    private val fetchTaskRunner: FetchTaskRunner, // 用来执行爬虫网络请求
    private val ruleRepository: RuleRepository, // 注入统一管理的规则库
    private val bookDao: BookDao                  // 用来加入书架
) : SearchRepository {

    override fun fetchData(keyword: String, page: Int): Flow<List<SearchBook>> = channelFlow {
        val enabledRules = ruleRepository.getEnabledRules()
        if (enabledRules.isEmpty()) throw Exception("没有启用任何书源")

        // 用于收集所有成功返回的原始数据（未合并版）
        val accumulatedRawResults = mutableListOf<SearchBook>()
        // 协程并发锁，防止多个书源同时返回数据时写 List 产生冲突
        val mutex = Mutex()

        // 遍历所有启用的规则，并发发起请求
        enabledRules.forEach { rule ->
            // 注意：在 channelFlow 中直接 launch，它们是完全并发的
            launch {
                try {
                    val ctx = contextSessionManager.getContext(rule.id)
                    val html = fetchTaskRunner.fetch(rule, rule.search, keyword, ctx)
                    val list = rule.search.getList(html, ctx)

                    val rawList = list.map { item ->
                        SearchBook(
                            title = rule.search.getName(item, ctx).trim(),
                            author = rule.search.getAuthor(item, ctx).trim(),
                            type = rule.type,
                            cover = rule.search.getCover(item, ctx),
                            sources = listOf(
                                SearchBookSource(rule.id, rule.name, rule.search.getDetailUrl(item, ctx))
                            )
                        )
                    }

                    if (rawList.isNotEmpty()) {
                        // 某个书源拿到数据了！加锁后放进总池子
                        mutex.withLock {
                            accumulatedRawResults.addAll(rawList)
                            // 立刻执行合并同类项逻辑
                            val aggregatedList = aggregateResults(accumulatedRawResults)
                            // 将当前合并后的最新状态，实时发射给 ViewModel！
                            send(aggregatedList)
                        }
                    }
                } catch (e: Exception) {
                    // 这个书源挂了，直接忽略，不影响其他正在执行的书源
                }
            }
        }
    }.flowOn(Dispatchers.IO) // 强制整个流在 IO 线程运行

    // 为了代码整洁，将上一问的合并逻辑抽离成私有方法
    private fun aggregateResults(rawList: List<SearchBook>): List<SearchBook> {
        return rawList.groupBy { "${it.title}_${it.author}_${it.type}" }
            .map { (_, groupList) ->
                val baseBook = groupList.firstOrNull { it.cover.isNotBlank() } ?: groupList.first()
                val combinedSources = groupList.flatMap { it.sources }
                baseBook.copy(sources = combinedSources)
            }
    }

    override suspend fun saveData(book: Book) {
        withContext(Dispatchers.IO) {
            // 调用 Dao 插入数据库
            bookDao.addToBookshelf(book.toEntity()) // 假设 Dao 里有 insert 方法
        }
    }
}
