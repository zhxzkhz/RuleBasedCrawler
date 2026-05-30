package com.zhhz.spider.repository

import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.network.Book
import kotlinx.coroutines.flow.Flow

// 2. 书架相关业务数据仓库
interface BookRepository {
    // 强制命名：从本地数据库加载流叫 loadData
    fun loadData(): Flow<List<Book>>

    // 强制命名：删除本地数据叫 deleteData
    suspend fun deleteData(book: Book)
}