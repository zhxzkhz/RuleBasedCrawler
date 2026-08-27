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
    val lastReadChapterTitle: String = "尚未开始",
    val lastReadChapterIndex: Int = -1,
    val lastReadPageIndex: Int = 0,
    val inLibrary: Boolean = false,
    // 分类/标签（逗号分隔），用于书架筛选
    val category: String = "",
    // 自定义分组位掩码（2,4,8,16...），一本书可同时属于多个分组；0=未分组
    val groupFlags: Long = 0,
    // 最后阅读时间（历史记录排序用）
    val updateTime: Long = 0L,
    // 总章节数（追更红点：计算未读章节数用）
    val totalChapters: Int = 0,
    // 加入书架时间（书架排序用）
    val createTime: Long = 0L,
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
    val url: String,     // 抓取正文用的 URL
    // 是否已读（仅本地目录数据携带，规则抓取的原始目录默认 false）
    val isRead: Boolean = false,
    // 💡 新增：章节所属的卷名/分组名（默认空字符串代表无卷名）
    val group: String = ""
)

/** 发现/榜单页的一个分类标签 */
@Serializable
data class ExploreTab(
    val title: String,
    val url: String,
    val group: String = ""
)

/** 书籍的一条评论（楼中楼回复嵌套在 replys 中） */
@Serializable
data class BookComment(
    val user: String,
    val content: String,
    val time: String = "",
    val score: String = "",
    val avatar: String = "",
    val replys: List<BookComment> = emptyList()
) {
    /** 含回复的总条数 */
    fun totalCount(): Int = 1 + replys.sumOf { it.totalCount() }
}

// 旧 Android Rhino 曾把 Java 集合包装器序列化为该对象，供缓存迁移使用。
fun Chapter.hasLegacyInvalidTitle(): Boolean {
    return title.trim() == "{\"empty\":false}" || title.trim() == "{\"empty\":true}"
}

data class BookDetail(
    val url: String,
    val title: String,
    val author: String,
    val cover: String,
    val desc: String,
    val status: String,
    val latestChapterTitle: String,
    val catalogUrl: String,
    val labels: List<String> = emptyList()
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
    inLibrary = this.inLibrary,
    category = this.category,
    groupFlags = this.groupFlags
)

// 扩展函数：Domain 转化为 Data
fun Chapter.toEntity(bookUrl: String): ChapterEntity {
    return ChapterEntity(
        bookUrl = bookUrl,
        indexNum = this.index,
        title = this.title,
        url = this.url,
        groupName = this.group
    )
}
