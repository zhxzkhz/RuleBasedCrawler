package com.zhhz.spider.network

import com.zhhz.spider.DetailRoute
import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.ChapterEntity
import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val url: String,
    val title: String,
    val author: String,
    val cover: String,
    val ruleId: String,
    val lastReadChapterTitle: String = "",
    val lastReadChapterIndex: Int = 0,
    val lastReadPageIndex: Int = 0,
    val inLibrary: Boolean = false,
    val availableSources: List<SearchBookSource> = emptyList()
)

// 1. 新增：表示具体的某一个书源的信息
@Serializable
data class SearchBookSource(
    val ruleId: String,
    val sourceName: String,
    val url: String
)

@Serializable
data class SearchBook(
    val title: String,
    val author: String,
    val cover: String,
    val type: Int,
    val sources: List<SearchBookSource>
)

@Serializable
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
    val desc: String,
    val status: String,
    val latestChapterTitle: String,
    val catalogUrl: String
)

data class ReaderContent(
    val title: String,
    val body: String,           // 小说文本
    val images: List<String>,   // 漫画图片列表
    val nextChapterUrl: String? = null,
    val prevChapterUrl: String? = null
)

fun SearchBook.toDomain(searchBookSource: SearchBookSource = this.sources.first()) = Book(
    url = searchBookSource.url,
    title = this.title,
    author = this.author,
    cover = this.cover,
    ruleId = searchBookSource.ruleId
)


fun SearchBook.toRoute(searchBookSource: SearchBookSource = this.sources.first()) = DetailRoute(
    detailUrl = searchBookSource.url,
    title = this.title,
    author = this.author,
    cover = this.cover,
    ruleId = searchBookSource.ruleId
)



fun Book.toEntity() = BookEntity(
    detailUrl = this.url,
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