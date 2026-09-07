package com.zhhz.spider.ui.screen

import com.zhhz.spider.network.Chapter

internal sealed interface DetailCatalogRow {
    val key: String
    val contentType: String

    data class GroupHeader(
        val group: String,
        val firstChapterIndex: Int
    ) : DetailCatalogRow {
        override val key: String = "group:$firstChapterIndex:$group"
        override val contentType: String = "group-header"
    }

    data class ChapterItem(val chapter: Chapter) : DetailCatalogRow {
        override val key: String = "chapter:${chapter.index}:${chapter.url}"
        override val contentType: String = "chapter"
    }
}

internal fun flattenDetailCatalogRows(chapters: List<Chapter>): List<DetailCatalogRow> = buildList {
    var previousGroup: String? = null
    chapters.forEach { chapter ->
        val group = chapter.group
        if (group.isNotBlank() && group != previousGroup) {
            add(DetailCatalogRow.GroupHeader(group, chapter.index))
        }
        add(DetailCatalogRow.ChapterItem(chapter))
        previousGroup = group
    }
}
