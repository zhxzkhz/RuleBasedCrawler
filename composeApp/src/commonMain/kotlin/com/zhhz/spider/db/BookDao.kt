package com.zhhz.spider.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun addToBookshelf(book: BookEntity)

    @Query("SELECT * FROM books ORDER BY updateTime DESC")
    fun getAllBooksFlow(): Flow<List<BookEntity>>

    // 实时观察这本书是否在书架上
    @Query("SELECT * FROM books WHERE detailUrl = :url")
    fun getBookFlow(url: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE detailUrl = :url")
    suspend fun getBookByUrl(url: String): BookEntity?

    @Query("SELECT * FROM books WHERE detailUrl IN (:urls)")
    suspend fun getBooksByUrls(urls: List<String>): List<BookEntity>

    @Delete
    suspend fun removeFromBookshelf(book: BookEntity)

    @Query("DELETE FROM books WHERE detailUrl = :url")
    suspend fun deleteBookByUrl(url: String)    /**
     * 更新阅读进度
     *
     * @param bookUrl 书籍的详情页URL（主键）
     * @param chapterIndex 当前阅读到的章节索引 (0, 1, 2...)
     * @param chapterTitle 章节名称（用于在书架展示）
     * @param chapterUrl 章节的真实URL（兜底使用）
     * @param pageIndex 章节内的图片索引/滚动进度 (漫画用到，小说可传0)
     * @param updateTime 触发时间（确保最后读过的书排在书架最前面）
     */
    @Query("""
        UPDATE books 
        SET lastReadChapterIndex = :chapterIndex,
            lastReadChapterTitle = :chapterTitle,
            lastReadChapterUrl = :chapterUrl,
            lastReadPageIndex = :pageIndex,
            updateTime = :updateTime
        WHERE detailUrl = :bookUrl
    """)
    suspend fun updateReadProgress(
        bookUrl: String,
        chapterIndex: Int,
        pageIndex: Int,
        chapterTitle: String,
        chapterUrl: String,
        updateTime: Long = System.currentTimeMillis()
    )

    // 2. 标记已读
    @Query("UPDATE chapters SET isRead = 1 WHERE bookUrl = :bookUrl AND url = :chapterUrl")
    suspend fun markAsRead(bookUrl: String, chapterUrl: String)

    // ---------------- 自定义分组（位掩码） ----------------

    /** 将一批书加入某分组（置位，保留其它分组归属） */
    @Query("UPDATE books SET groupFlags = groupFlags | :mask WHERE detailUrl IN (:urls)")
    suspend fun addToGroup(urls: List<String>, mask: Long)

    /** 将一批书移出某分组（清位，保留其它分组归属） */
    @Query("UPDATE books SET groupFlags = groupFlags & ~:mask WHERE detailUrl IN (:urls)")
    suspend fun removeFromGroup(urls: List<String>, mask: Long)

    /** 删除分组时：把该分组的位从所有书上清掉 */
    @Query("UPDATE books SET groupFlags = groupFlags & ~:mask")
    suspend fun clearGroupFromAllBooks(mask: Long)

    /** 软移除/加回书架：只改 inLibrary 标记，保留章节与进度 */
    @Query("UPDATE books SET inLibrary = :inLibrary WHERE detailUrl IN (:urls)")
    suspend fun setInLibrary(urls: List<String>, inLibrary: Boolean)

    /** 目录变化后更新总章节数（书架"未读 N 章"红点的数据源） */
    @Query("UPDATE books SET totalChapters = :totalChapters WHERE detailUrl = :bookUrl")
    suspend fun updateTotalChapters(bookUrl: String, totalChapters: Int)

    /** 插入章节；URL 已存在时忽略，保护数据库中已有的 isRead 状态不被覆盖 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChaptersIgnore(chapters: List<ChapterEntity>): List<Long>

}