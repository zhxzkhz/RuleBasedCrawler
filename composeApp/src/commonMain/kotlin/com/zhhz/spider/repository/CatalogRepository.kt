package com.zhhz.spider.repository

import com.zhhz.spider.network.Chapter

interface CatalogRepository {
    // 💡 严格遵循“网络拉取一律叫 fetchData”的命名规范
    suspend fun fetchData(catalogUrl: String, ruleId: String, bookUrl: String? = null): List<Chapter>

    suspend fun loadData(bookUrl: String): List<Chapter>

    suspend fun saveData(bookUrl: String, chapters: List<Chapter>)

}