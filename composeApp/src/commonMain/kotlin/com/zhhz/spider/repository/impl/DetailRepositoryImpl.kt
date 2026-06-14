package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.model.CrawlerStage
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.BookDetail
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.toEntity
import com.zhhz.spider.repository.DetailRepository
import com.zhhz.spider.repository.RuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.collections.set

class DetailRepositoryImpl(
    private val bookDao: BookDao,
    private val fetchTaskRunner: FetchTaskRunner, // 用来执行爬虫网络请求
    private val ruleRepository: RuleRepository, // 注入统一管理的规则库
    private val contextSessionManager: ContextSessionManager
) : DetailRepository {
    override suspend fun fetchData(
        detailUrl: String,
        ruleId: String
    ): BookDetail {
        val rule = ruleRepository.getEnabledRules().find { it.id == ruleId }
            ?: throw Exception("找不到对应的书源规则")
        return withContext(Dispatchers.IO) {
            val ctx = contextSessionManager.getContext(detailUrl, ruleId)
            ctx["bookUrl"] = detailUrl
            // 1. 抓取详情页 HTML
            val bookDetailHtml = fetchTaskRunner.fetch(rule, rule.detail, detailUrl, ctx)
            bookDetailHtml.throwIfCrawlerError(rule, CrawlerStage.DETAIL)
            ctx["bookDetailHtml"] = bookDetailHtml
            // 2. 解析基本信息
            val title = rule.detail.getBookName(bookDetailHtml, ctx)
            val author = rule.detail.getBookAuthor(bookDetailHtml, ctx)
            val cover = rule.detail.getBookCover(bookDetailHtml, ctx)
            val desc = rule.detail.getBookDesc(bookDetailHtml, ctx)
            val catalogUrl = rule.detail.getCatalogUrl(bookDetailHtml, ctx)
            ctx["title"] = title
            ctx["author"] = author
            ctx["cover"] = cover

            BookDetail(
                url = detailUrl,
                title = title,
                author = author,
                cover = cover,
                desc = desc,
                status = "",
                latestChapterTitle = "",
                catalogUrl = catalogUrl
            )
        }
    }

    override suspend fun loadData(bookUrl: String): Book? {
        return withContext(Dispatchers.IO) {
            // 调用你本地 DAO 中根据 URL 查找书籍的方法
            bookDao.getBookByUrl(bookUrl)?.toDomain()
        }
    }


    override fun loadBookshelfStatus(bookUrl: String): Flow<Boolean> {
        return bookDao.getBookFlow(bookUrl).map { it != null }.distinctUntilChanged()
    }

    override suspend fun saveData(book: Book) {
        withContext(Dispatchers.IO){
            bookDao.addToBookshelf(book.toEntity())
        }
    }

    override suspend fun deleteData(bookUrl: String) {
        withContext(Dispatchers.IO){
            bookDao.deleteBookByUrl(bookUrl)
        }
    }
}
