package com.zhhz.spider.ui.screen

import com.zhhz.spider.network.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailCatalogRowsTest {

    @Test
    fun chaptersWithoutGroupsRemainChapterRows() {
        val chapters = listOf(
            Chapter(0, "第一章", "/1"),
            Chapter(1, "第二章", "/2")
        )

        val rows = flattenDetailCatalogRows(chapters)

        assertEquals(
            listOf(
                DetailCatalogRow.ChapterItem(chapters[0]),
                DetailCatalogRow.ChapterItem(chapters[1])
            ),
            rows
        )
    }

    @Test
    fun groupChangesInsertHeaderBeforeChapter() {
        val chapters = listOf(
            Chapter(0, "第一章", "/1", group = "上卷"),
            Chapter(1, "第二章", "/2", group = "上卷"),
            Chapter(2, "第三章", "/3", group = "下卷")
        )

        val rows = flattenDetailCatalogRows(chapters)

        assertEquals(
            listOf(
                DetailCatalogRow.GroupHeader("上卷", 0),
                DetailCatalogRow.ChapterItem(chapters[0]),
                DetailCatalogRow.ChapterItem(chapters[1]),
                DetailCatalogRow.GroupHeader("下卷", 2),
                DetailCatalogRow.ChapterItem(chapters[2])
            ),
            rows
        )
    }

    @Test
    fun blankGroupDoesNotRepeatPreviousHeader() {
        val chapters = listOf(
            Chapter(0, "序章", "/0", group = "序"),
            Chapter(1, "第一章", "/1"),
            Chapter(2, "第二章", "/2", group = "正文")
        )

        val rows = flattenDetailCatalogRows(chapters)

        assertEquals(
            listOf("序", "序章", "第一章", "正文", "第二章"),
            rows.map { row ->
                when (row) {
                    is DetailCatalogRow.GroupHeader -> row.group
                    is DetailCatalogRow.ChapterItem -> row.chapter.title
                }
            }
        )
    }
}
