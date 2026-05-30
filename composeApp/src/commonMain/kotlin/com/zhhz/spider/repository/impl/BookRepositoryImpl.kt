package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.toEntity
import com.zhhz.spider.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// 书架功能的具体实现
class BookRepositoryImpl(
    private val bookDao: BookDao
) : BookRepository {

    override fun loadData(): Flow<List<Book>> {
        // Room/SQLDelight 的 Flow 默认已经在后台线程安全处理，直接返回即可
        return bookDao.getAllBooksFlow().map { bookEntities ->
            bookEntities.map {
                it.toDomain()
            }
        }
    }

    override suspend fun deleteData(book: Book) {
        withContext(Dispatchers.IO) {
            bookDao.removeFromBookshelf(book.toEntity())
        }
    }
}