package com.zhhz.spider.repository

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.BookDetail
import kotlinx.coroutines.flow.Flow


interface DetailRepository {
    // 强制命名：从网络获取详情数据
    suspend fun fetchData(detailUrl: String, ruleId: String): BookDetail

    // 检查是否在书架中（返回 Flow 以便实时监听状态变更）
    fun loadBookshelfStatus(bookUrl: String): Flow<Boolean>

    suspend fun loadData(bookUrl: String): Book?

    // 加入/移出 书架
    suspend fun saveData(book: Book)
    suspend fun deleteData(bookUrl: String)
}