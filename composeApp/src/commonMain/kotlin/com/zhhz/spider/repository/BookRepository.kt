package com.zhhz.spider.repository

import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.model.BookshelfImportResult
import com.zhhz.spider.network.Book
import kotlinx.coroutines.flow.Flow

// 2. 书架相关业务数据仓库
interface BookRepository {
    // 强制命名：从本地数据库加载流叫 loadData
    fun loadData(): Flow<List<Book>>

    // 强制命名：删除本地数据叫 deleteData
    suspend fun deleteData(book: Book)

    /** 将选中书籍（含章节目录与阅读进度）导出为 JSON 备份字符串 */
    suspend fun exportBackupJson(bookUrls: Collection<String>): String

    /** 从备份 JSON 导入书籍到书架，已存在的书跳过以保护本地进度 */
    suspend fun importBackupJson(json: String): BookshelfImportResult
}