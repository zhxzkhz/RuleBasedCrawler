package com.zhhz.spider.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhhz.spider.network.Chapter

@Entity(
    tableName = "chapters",
    // 建立外键约束：如果书删了，对应的目录也自动删除（级联删除）
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["detailUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // 加索引提升查询速度，特别是按 bookUrl 查目录时
    indices = [Index(value = ["bookUrl"])]
)
data class ChapterEntity(
    // 为了防止 URL 变动，我们用 bookUrl + index 作为复合主键的感觉
    // 也可以直接用自动生成的 ID，这里使用自动递增
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val bookUrl: String,      // 所属书籍的 ID
    val title: String,         // 章节名
    val url: String,          // 章节正文地址
    val indexNum: Int,           // 章节排序（1, 2, 3...）
    val isRead: Boolean = false
)

fun ChapterEntity.toDomain(): Chapter {
    return Chapter(
        index = this.indexNum,
        title = this.title,
        url = this.url
    )
}