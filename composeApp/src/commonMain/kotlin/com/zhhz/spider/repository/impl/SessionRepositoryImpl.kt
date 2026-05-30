package com.zhhz.spider.repository.impl

import com.zhhz.spider.manager.BookSessionManager
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.repository.SessionRepository

// 具体的实现类
class SessionRepositoryImpl(
    private val sessionManager: BookSessionManager,
    private val contextSessionManager: ContextSessionManager
) : SessionRepository {

    override suspend fun saveData(book: Book) {
        sessionManager.setCurrentBook(book)
        contextSessionManager.forkContext(
            fromKey = book.ruleId,
            bookUrl = book.url
        )
        val ctx = contextSessionManager.getActiveContext(this,book.ruleId)
        ctx["bookUrl"] = book.url
        ctx["bookTitle"] = book.title
        ctx["bookAuthor"] = book.author
    }

    override fun loadData(): Book? {
        return sessionManager.getCurrentBook()
    }

    override fun clearData() {
        sessionManager.clear()
    }

    override fun saveCatalog(chapters: List<Chapter>) {
        sessionManager.setCatalog(chapters)
    }

    override fun loadCatalog(): List<Chapter> {
        return sessionManager.getCatalog()
    }
}