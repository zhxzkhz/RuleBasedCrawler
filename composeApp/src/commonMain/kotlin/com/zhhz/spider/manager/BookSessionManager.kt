package com.zhhz.spider.manager

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.SearchBook

// 纯粹的内存数据持有者
class BookSessionManager {
    private var _currentSearchBook: Book? = null

    private var _currentCatalog: List<Chapter> = emptyList()

    fun setCurrentBook(book: Book) {
        _currentSearchBook = book
    }

    fun getCurrentBook(): Book? {
        return _currentSearchBook
    }

    fun setCatalog(currentCatalog: List<Chapter>) {
        _currentCatalog = currentCatalog
    }

    fun getCatalog(): List<Chapter> {
        return _currentCatalog
    }

    fun clear() {
        _currentSearchBook = null
    }
}