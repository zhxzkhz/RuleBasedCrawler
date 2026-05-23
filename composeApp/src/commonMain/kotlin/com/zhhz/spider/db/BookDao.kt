package com.zhhz.spider.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToBookshelf(book: BookEntity)

    @Query("SELECT * FROM books ORDER BY updateTime DESC")
    fun getAllBooksFlow(): Flow<List<BookEntity>>

    // 实时观察这本书是否在书架上
    @Query("SELECT * FROM books WHERE detailUrl = :url")
    fun getBookFlow(url: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE detailUrl = :url")
    suspend fun getBook(url: String): BookEntity?

    @Delete
    suspend fun removeFromBookshelf(book: BookEntity)

    /**
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
    suspend fun updateLastRead(
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        chapterUrl: String,
        pageIndex: Int,
        updateTime: Long = System.currentTimeMillis()
    )

    // 1. 批量保存目录
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    // 2. 响应式获取某本书的目录
    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY indexNum ASC")
    fun getChaptersFlow(bookUrl: String): Flow<List<ChapterEntity>>

    // 3. 标记已读
    @Query("UPDATE chapters SET isRead = 1 WHERE bookUrl = :bookUrl AND url = :chapterUrl")
    suspend fun markAsRead(bookUrl: String, chapterUrl: String)

    // 1. 第一步：尝试插入。如果冲突（URL已存在），则什么都不做
    // 这样可以保护数据库中已有的 isRead 状态不被覆盖
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChaptersIgnore(chapters: List<ChapterEntity>): List<Long>

    // 2. 第二步（可选）：如果你希望网络抓到新标题时更新标题，而不动 isRead
    @Query("UPDATE chapters SET title = :name, indexNum = :index WHERE bookUrl = :bookUrl AND url = :chapterUrl")
    suspend fun updateChapterMetadata(bookUrl: String, chapterUrl: String, name: String, index: Int)

    /**
     * 3. 核心同步逻辑（组合拳）
     */
    @Transaction
    suspend fun syncChapters(newChapters: List<ChapterEntity>) {
        // 先插入新章节（已有的会被忽略，isRead 得以保留）
        insertChaptersIgnore(newChapters)

        // 如果你追求完美，可以遍历一遍更新标题，但通常只做插入就够了
        // newChapters.forEach { updateChapterMetadata(it.bookUrl, it.url, it.name, it.index) }
    }

}