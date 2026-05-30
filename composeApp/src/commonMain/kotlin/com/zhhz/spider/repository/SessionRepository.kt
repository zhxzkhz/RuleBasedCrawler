package com.zhhz.spider.repository

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.SearchBook

// 统一的接口契约
interface SessionRepository {
    // 强制命名：保存内存数据
    suspend fun saveData(book: Book)

    // 强制命名：读取内存数据
    fun loadData(): Book?

    // 清除数据（可选，防止内存泄漏）
    fun clearData()

    fun saveCatalog(chapters: List<Chapter>)

    fun loadCatalog(): List<Chapter>
}