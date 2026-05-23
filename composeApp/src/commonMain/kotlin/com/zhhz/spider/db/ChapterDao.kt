package com.zhhz.spider.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    // 批量插入章节
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    // 获取某本书的全部章节，按索引排序
    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY indexNum ASC")
    suspend fun getChapters(bookUrl: String): List<ChapterEntity>

    // 如果你想让 UI 响应式更新（例如某章被标记为已读后自动变色）
    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY indexNum ASC")
    fun getChaptersFlow(bookUrl: String): Flow<List<ChapterEntity>>

    // 标记某一章为已读
    // 唯一且稳定的标记接口：标记单章状态
    @Query("UPDATE chapters SET isRead = :isRead WHERE bookUrl = :bookUrl AND indexNum = :chapterIndex")
    suspend fun updateChapterReadStatus(bookUrl: String, chapterIndex: Int, isRead: Boolean = true)

    // 清空某本书的旧目录（用于重新抓取覆盖时）
    @Query("DELETE FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun deleteChapters(bookUrl: String)
}