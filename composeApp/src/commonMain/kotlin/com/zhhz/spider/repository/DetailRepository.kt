package com.zhhz.spider.repository

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.BookDetail
import kotlinx.coroutines.flow.Flow


interface DetailRepository {
    // 强制命名：从网络获取详情数据
    suspend fun fetchData(detailUrl: String, ruleId: String): BookDetail

    // 从本地数据库读取已保存的详情信息（不发网络请求），未保存过返回 null
    suspend fun loadLocalDetail(bookUrl: String): BookDetail?

    // 将网络抓取到的详情信息持久化到本地；仅更新已存在的书籍行，不会为浏览过的书创建书架记录
    suspend fun saveDetailInfo(bookUrl: String, detail: BookDetail)

    // 检查是否在书架中（返回 Flow 以便实时监听状态变更）
    fun loadBookshelfStatus(bookUrl: String): Flow<Boolean>

    suspend fun loadData(bookUrl: String): Book?

    // 加入/移出 书架
    suspend fun saveData(book: Book)
    suspend fun deleteData(bookUrl: String)

    /** 软移除/加回书架：只改 inLibrary 标记，保留章节目录与阅读进度 */
    suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean)
}