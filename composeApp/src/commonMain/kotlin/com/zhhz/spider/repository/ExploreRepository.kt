package com.zhhz.spider.repository

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.ExploreTab

interface ExploreRepository {
    // 💡 命名规范：网络拉取一律叫 fetchData；这里拉取发现页的分类/榜单标签列表
    suspend fun fetchTabs(ruleId: String): List<ExploreTab>

    // 拉取某个分类/榜单下的书籍列表；分类地址可包含 {{page}} 模板实现翻页
    suspend fun fetchBooks(ruleId: String, tabUrl: String, page: Int = 1): List<Book>
}
