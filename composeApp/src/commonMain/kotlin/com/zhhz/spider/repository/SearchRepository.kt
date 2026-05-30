package com.zhhz.spider.repository

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.SearchBook
import kotlinx.coroutines.flow.Flow

// 1. 搜索相关业务数据仓库
interface SearchRepository {
    // 强制命名：从网络获取数据叫 fetchData
    fun fetchData(keyword: String, page: Int): Flow<List<SearchBook>>

    // 强制命名：保存数据到本地叫 saveData
    suspend fun saveData(book: Book)
}

