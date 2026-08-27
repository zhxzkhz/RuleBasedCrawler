package com.zhhz.spider.repository

import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.BookGroupEntity
import com.zhhz.spider.model.BookshelfImportResult
import com.zhhz.spider.network.Book
import kotlinx.coroutines.flow.Flow

// 2. 书架相关业务数据仓库
interface BookRepository {
    // 强制命名：从本地数据库加载流叫 loadData
    fun loadData(): Flow<List<Book>>

    // 强制命名：删除本地数据叫 deleteData
    suspend fun deleteData(book: Book)

    /** 软移除：仅标记 inLibrary=false（保留章节目录与阅读进度），可从阅读历史找回 */
    suspend fun removeFromShelf(bookUrls: List<String>)

    /** 加回书架：标记 inLibrary=true */
    suspend fun restoreToShelf(bookUrls: List<String>)

    /**
     * 将选中书籍导出为 JSON 备份字符串（严格限于所选书）
     * @param includeProgress 是否包含阅读进度与章节已读标记（false 时仅保留目录）
     */
    suspend fun exportBackupJson(
        bookUrls: Collection<String>,
        includeProgress: Boolean = true
    ): String

    /**
     * 从备份 JSON 导入书籍到书架，已存在的书跳过以保护本地进度
     * @param groupMode 分组数据处理策略：合并创建缺失分组 / 全部归入某个现有分组
     * @param targetGroupId groupMode=MOVE_TO_EXISTING 时的目标分组 id
     */
    suspend fun importBackupJson(
        json: String,
        groupMode: com.zhhz.spider.viewModel.BookGroupImportMode = com.zhhz.spider.viewModel.BookGroupImportMode.MERGE,
        targetGroupId: Long? = null
    ): BookshelfImportResult

    // ---------------- 自定义分组 ----------------

    /** 实时观察全部分组定义 */
    fun observeGroups(): Flow<List<BookGroupEntity>>

    /**
     * 创建分组：自动分配下一个可用位掩码（2,4,8...二倍递增）
     * @param isAutoUpdate 是否允许自动更新
     * @throws IllegalStateException 名称重复或分组数达上限（62 个）
     */
    suspend fun createGroup(name: String, isAutoUpdate: Boolean = false): BookGroupEntity

    /** 重命名分组，并可修改其自动更新属性 */
    suspend fun updateGroup(groupId: Long, newName: String, isAutoUpdate: Boolean)

    /** 删除分组，并把该分组的位从所有书籍上清除 */
    suspend fun deleteGroup(groupId: Long)

    /** 设置一批书的分组归属（checked=true 加入，false 移出），保留其它分组 */
    suspend fun setBooksInGroup(urls: List<String>, mask: Long, checked: Boolean)
}