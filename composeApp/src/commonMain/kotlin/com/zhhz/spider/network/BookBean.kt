package com.zhhz.spider.network

import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.ChapterEntity
import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val detailUrl: String,
    val title: String,
    val author: String,
    val cover: String,
    val ruleId: String,
    val lastReadChapterTitle: String = "",
    val lastReadChapterIndex: Int = 0,
    val lastReadPageIndex: Int = 0,
    val inLibrary: Boolean = false
)

@Serializable
data class SearchBook(
    val title: String,
    val author: String,
    val cover: String,
    val detailUrl: String,
    val ruleId: String, // 记录来源，点击进入详情时需要用它
    val sourceName: String
)

data class Chapter(
    val index: Int,     // 章节的绝对序号（0, 1, 2...），非常关键
    val title: String,   // 章节标题
    val url: String     // 抓取正文用的 URL
)

data class BookDetail(
    val url: String,
    val title: String,
    val author: String,
    val cover: String,
    val desc: String
)

data class ReaderContent(
    val title: String,
    val body: String,           // 小说文本
    val images: List<String>,   // 漫画图片列表
    val nextChapterUrl: String? = null,
    val prevChapterUrl: String? = null
)

fun Book.toDomain() = BookEntity(
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

// 扩展函数：Domain 转化为 Data
fun Chapter.toEntity(bookUrl: String): ChapterEntity {
    return ChapterEntity(
        bookUrl = bookUrl,
        indexNum = this.index,
        title = this.title,
        url = this.url
    )
}