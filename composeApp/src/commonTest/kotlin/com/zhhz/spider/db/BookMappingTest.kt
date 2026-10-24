package com.zhhz.spider.db

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.SearchBookSource
import com.zhhz.spider.network.toEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Book ↔ BookEntity 映射契约测试。
 *
 * 历史事故：toEntity 曾静默丢弃字段，叠加 @Upsert 整行覆盖导致阅读进度被清零；
 * availableSources 不在实体中，靠调用方在 Session 写入时手动保留（换源塌缩 bug 的根源）。
 * 这里把"哪些字段落库、哪些字段由调用方负责"钉成显式契约，防止无意识再丢字段。
 */
class BookMappingTest {

    private fun entity() = BookEntity(
        detailUrl = "https://book.example/1",
        ruleId = "rule-a",
        title = "书名",
        author = "作者",
        cover = "https://cdn/cover.jpg",
        description = "简介",
        category = "修仙,系统",
        catalogUrl = "https://book.example/1/catalog",
        lastReadChapterIndex = 12,
        lastReadChapterTitle = "第13章",
        lastReadChapterUrl = "https://book.example/1/c13",
        lastReadPageIndex = 3,
        inLibrary = true,
        totalChapters = 100,
        groupFlags = 2L or 8L,
        createTime = 111L,
        updateTime = 222L
    )

    @Test
    fun toDomainPreservesReadingStateAndShelfFields() {
        val book = entity().toDomain()

        assertEquals("https://book.example/1", book.url)
        assertEquals("rule-a", book.ruleId)
        assertEquals("书名", book.title)
        assertEquals("修仙,系统", book.category)
        assertEquals(12, book.lastReadChapterIndex)
        assertEquals("第13章", book.lastReadChapterTitle)
        assertEquals(3, book.lastReadPageIndex)
        assertEquals(100, book.totalChapters)      // 书架"未读 N 章"红点的数据源
        assertTrue(book.inLibrary)
        assertEquals(2L or 8L, book.groupFlags)
        assertEquals(111L, book.createTime)
        assertEquals(222L, book.updateTime)
    }

    @Test
    fun toEntityMapsIdentityProgressAndGrouping() {
        val book = Book(
            url = "https://book.example/1",
            title = "书名",
            author = "作者",
            cover = "https://cdn/cover.jpg",
            ruleId = "rule-a",
            lastReadChapterTitle = "第13章",
            lastReadChapterIndex = 12,
            lastReadPageIndex = 3,
            inLibrary = false,
            category = "修仙",
            groupFlags = 4L
        )

        val entity = book.toEntity()

        assertEquals("https://book.example/1", entity.detailUrl)
        assertEquals("rule-a", entity.ruleId)
        assertEquals(12, entity.lastReadChapterIndex)
        assertEquals("第13章", entity.lastReadChapterTitle)
        assertEquals(3, entity.lastReadPageIndex)
        assertEquals(false, entity.inLibrary)
        assertEquals("修仙", entity.category)
        assertEquals(4L, entity.groupFlags)
    }

    @Test
    fun toEntityDropsVolatileFieldsByDesign() {
        // 已知契约（改动此处须同步审查所有 @Upsert 调用方）：
        // - totalChapters 不经 toEntity 落库，由 CatalogRepositoryImpl.saveData 专门维护；
        // - createTime/updateTime 使用实体默认值（当前时间）——对已存在的行做整行 Upsert
        //   前必须先读旧实体合并，否则会重置排序时间（历史 bug）；
        // - availableSources 不在实体中，由 Session 层负责保留。
        val entity = Book(
            url = "https://book.example/1",
            title = "书名",
            author = "作者",
            cover = "c",
            ruleId = "rule-a",
            totalChapters = 100,
            createTime = 111L,
            updateTime = 222L,
            availableSources = listOf(SearchBookSource("rule-a", "源", "https://book.example/1"))
        ).toEntity()

        assertEquals(0, entity.totalChapters)
        assertTrue(entity.createTime > 222L && entity.updateTime > 222L)
    }
}
