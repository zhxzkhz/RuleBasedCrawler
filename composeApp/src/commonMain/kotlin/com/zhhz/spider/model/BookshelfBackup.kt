package com.zhhz.spider.model

import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.ChapterEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val BOOKSHELF_BACKUP_VERSION = 1

/**
 * 书架备份文件结构。导出选中书籍的元数据、阅读进度和章节目录（含已读标记），
 * 可跨设备（Android/Desktop）导入恢复。
 */
@Serializable
data class BookshelfBackup(
    val version: Int = BOOKSHELF_BACKUP_VERSION,
    val exportedAt: Long = 0L,
    val books: List<BookBackup> = emptyList()
)

@Serializable
data class BookBackup(
    val book: BookEntity,
    val chapters: List<ChapterEntity> = emptyList()
)

data class BookshelfImportResult(
    val addedCount: Int,
    val skippedCount: Int
)

private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun BookshelfBackup.toJsonString(): String = backupJson.encodeToString(this)

fun parseBookshelfBackup(json: String): BookshelfBackup = backupJson.decodeFromString(json)
