package com.zhhz.spider.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zhhz.spider.network.Book
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "books")
data class BookEntity(
    // ----------------- 1. 核心唯一标识 -----------------
    @PrimaryKey
    val detailUrl: String,             // 书籍主页URL（作为主键）

    val ruleId: String,                // 解析规则ID（爬虫必选）

    // ----------------- 2. 基础元数据 -----------------
    val title: String,                 // 书名
    val author: String,                // 作者
    val cover: String,                 // 封面图片URL

    @ColumnInfo(defaultValue = "")
    val description: String = "",      // 内容简介

    @ColumnInfo(defaultValue = "")
    val category: String = "",         // 分类/标签（如 "热血", "修仙"），用于书架过滤

    @ColumnInfo(defaultValue = "0")
    val status: Int = 0,               // 连载状态: 0=连载中, 1=已完结

    // ----------------- 3. 深度阅读进度 -----------------
    @ColumnInfo(defaultValue = "-1")
    val lastReadChapterIndex: Int = -1,

    // 保留 Title，用于书架界面的快速展示，不用每次去查目录
    val lastReadChapterTitle: String = "尚未开始",

    // 保留 URL 作为兜底和恢复的冗余字段（非判定唯一标准）
    val lastReadChapterUrl: String = "",

    // 页内进度：第几张图 / 滚动的 Y 轴偏移量
    @ColumnInfo(defaultValue = "0")
    val lastReadPageIndex: Int = 0,

    // ----------------- 4. 状态与统计标识 -----------------
    @ColumnInfo(defaultValue = "1")
    val inLibrary: Boolean = true,     // 是否在书架上（false 表示只是阅读历史，不显示在主书架）

    @ColumnInfo(defaultValue = "0")
    val isDownloaded: Boolean = false, // 是否已全本/部分离线下载

    @ColumnInfo(defaultValue = "0")
    val totalChapters: Int = 0,        // 总章节数（用于展示进度百分比 10/100）

    // ----------------- 5. 时间戳记录 -----------------
    val createTime: Long = System.currentTimeMillis(), // 首次加入书架的时间
    var updateTime: Long = System.currentTimeMillis()  // 最后阅读/更新时间（决定书架排序）
)

fun BookEntity.toDomain() = Book(
    detailUrl = this.detailUrl,
    title = this.title,
    author = this.author,
    cover = this.cover,
    ruleId = this.ruleId,
    lastReadChapterTitle = this.lastReadChapterTitle,
    lastReadChapterIndex = this.lastReadChapterIndex,
    lastReadPageIndex = this.lastReadPageIndex,
    inLibrary = this.inLibrary
)