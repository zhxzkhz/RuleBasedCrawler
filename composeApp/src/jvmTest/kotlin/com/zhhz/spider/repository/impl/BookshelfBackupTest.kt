package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.db.ChapterEntity
import com.zhhz.spider.model.BookshelfBackup
import com.zhhz.spider.model.BookBackup
import com.zhhz.spider.model.parseBookshelfBackup
import com.zhhz.spider.model.toJsonString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookshelfBackupTest {

    @Test
    fun exportSelectedBooksWithChaptersAndProgress() = runTest {
        val book = BookEntity(
            detailUrl = "https://example.com/book/1",
            ruleId = "rule-1",
            title = "测试漫画",
            author = "作者",
            cover = "https://example.com/cover.jpg",
            lastReadChapterIndex = 3,
            lastReadChapterTitle = "第4话",
            lastReadChapterUrl = "https://example.com/book/1/ch4",
            lastReadPageIndex = 7,
            inLibrary = true,
            totalChapters = 10
        )
        val chapters = (0 until 10).map { index ->
            ChapterEntity(
                id = (index + 1).toLong(),
                bookUrl = book.detailUrl,
                title = "第${index + 1}话",
                url = "${book.detailUrl}/ch${index + 1}",
                indexNum = index,
                isRead = index <= 3
            )
        }
        val db = FakeBookshelfDb(books = listOf(book), initialChapters = listOf(book.detailUrl to chapters))
        val repository = BookRepositoryImpl(db.bookDao, db.chapterDao)

        val json = repository.exportBackupJson(setOf(book.detailUrl))
        val backup = parseBookshelfBackup(json)

        assertEquals(1, backup.books.size)
        assertEquals(book.detailUrl, backup.books.first().book.detailUrl)
        assertEquals(3, backup.books.first().book.lastReadChapterIndex)
        assertEquals(7, backup.books.first().book.lastReadPageIndex)
        assertEquals(10, backup.books.first().chapters.size)
        assertTrue(backup.books.first().chapters.first { it.indexNum == 2 }.isRead)
    }

    @Test
    fun importAddsNewBooksAndSkipsExistingOnes() = runTest {
        val existing = BookEntity(
            detailUrl = "https://example.com/book/existing",
            ruleId = "rule-1",
            title = "已存在",
            author = "a",
            cover = ""
        )
        val imported = BookEntity(
            detailUrl = "https://example.com/book/new",
            ruleId = "rule-2",
            title = "新书",
            author = "b",
            cover = "",
            lastReadChapterIndex = 5
        )
        val chaptersOfImported = listOf(
            ChapterEntity(
                id = 99L,
                bookUrl = imported.detailUrl,
                title = "第1话",
                url = "https://example.com/book/new/ch1",
                indexNum = 0,
                isRead = true
            )
        )
        val backup = BookshelfBackup(
            books = listOf(BookBackup(existing), BookBackup(imported, chaptersOfImported))
        )

        val db = FakeBookshelfDb(books = listOf(existing))
        val repository = BookRepositoryImpl(db.bookDao, db.chapterDao)

        val result = repository.importBackupJson(backup.toJsonString())

        assertEquals(1, result.addedCount)
        assertEquals(1, result.skippedCount)
        assertEquals("新书", db.bookDao.getBookByUrl(imported.detailUrl)?.title)
        assertTrue(db.bookDao.getBookByUrl(imported.detailUrl)?.inLibrary == true)

        val insertedChapters = db.chapters.getValue(imported.detailUrl)
        assertEquals(0L, insertedChapters.single().id)
        assertTrue(insertedChapters.single().isRead)
    }

    @Test
    fun importKeepsLocalProgressForExistingBooks() = runTest {
        val local = BookEntity(
            detailUrl = "https://example.com/book/local",
            ruleId = "rule-1",
            title = "本地进度更高",
            author = "a",
            cover = "",
            lastReadChapterIndex = 100
        )
        val remoteCopy = local.copy(lastReadChapterIndex = 1, title = "旧备份")
        val backup = BookshelfBackup(books = listOf(BookBackup(remoteCopy)))

        val db = FakeBookshelfDb(books = listOf(local))
        val repository = BookRepositoryImpl(db.bookDao, db.chapterDao)

        val result = repository.importBackupJson(backup.toJsonString())

        assertEquals(0, result.addedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(100, db.bookDao.getBookByUrl(local.detailUrl)?.lastReadChapterIndex)
        assertEquals("本地进度更高", db.bookDao.getBookByUrl(local.detailUrl)?.title)
    }

    @Test
    fun backupRoundTripKeepsAllFields() {
        val backup = BookshelfBackup(
            exportedAt = 1724300000000L,
            books = listOf(
                BookBackup(
                    book = BookEntity(
                        detailUrl = "u",
                        ruleId = "r",
                        title = "t",
                        author = "a",
                        cover = "c"
                    ),
                    chapters = listOf(
                        ChapterEntity(id = 5, bookUrl = "u", title = "ch", url = "cu", indexNum = 1, isRead = true)
                    )
                )
            )
        )

        val parsed = parseBookshelfBackup(backup.toJsonString())

        assertEquals(backup, parsed)
    }
}

private class FakeBookshelfDb(
    books: List<BookEntity> = emptyList(),
    initialChapters: List<Pair<String, List<ChapterEntity>>> = emptyList()
) {
    val storedBooks = LinkedHashMap<String, BookEntity>().apply {
        books.forEach { put(it.detailUrl, it) }
    }
    val chapters: MutableMap<String, MutableList<ChapterEntity>> = LinkedHashMap<String, MutableList<ChapterEntity>>().apply {
        initialChapters.forEach { (url, list) -> put(url, list.toMutableList()) }
    }

    val bookDao = object : BookDao {
        override suspend fun addToBookshelf(book: BookEntity) {
            storedBooks[book.detailUrl] = book
        }

        override fun getAllBooksFlow(): Flow<List<BookEntity>> = flowOf(storedBooks.values.toList())

        override fun getBookFlow(url: String): Flow<BookEntity?> = flowOf(storedBooks[url])

        override suspend fun getBookByUrl(url: String): BookEntity? = storedBooks[url]

        override suspend fun getBooksByUrls(urls: List<String>): List<BookEntity> =
            urls.mapNotNull { storedBooks[it] }

        override suspend fun removeFromBookshelf(book: BookEntity) {
            storedBooks.remove(book.detailUrl)
        }

        override suspend fun deleteBookByUrl(url: String) {
            storedBooks.remove(url)
        }

        override suspend fun updateReadProgress(
            bookUrl: String,
            chapterIndex: Int,
            pageIndex: Int,
            chapterTitle: String,
            chapterUrl: String,
            updateTime: Long
        ) {
            storedBooks[bookUrl]?.let { current ->
                storedBooks[bookUrl] = current.copy(
                    lastReadChapterIndex = chapterIndex,
                    lastReadPageIndex = pageIndex,
                    lastReadChapterTitle = chapterTitle,
                    lastReadChapterUrl = chapterUrl
                )
            }
        }

        override fun getChaptersFlow(bookUrl: String): Flow<List<ChapterEntity>> =
            flowOf(this@FakeBookshelfDb.chapters[bookUrl].orEmpty())

        override suspend fun markAsRead(bookUrl: String, chapterUrl: String) = Unit

        override suspend fun insertChaptersIgnore(chapters: List<ChapterEntity>): List<Long> =
            chapters.map { chapter ->
                this@FakeBookshelfDb.chapters.getOrPut(chapter.bookUrl) { mutableListOf() }
                    .add(chapter)
                chapter.id
            }

        override suspend fun updateChapterMetadata(bookUrl: String, chapterUrl: String, name: String, index: Int) = Unit
    }

    val chapterDao = object : ChapterDao {
        override suspend fun insertChapters(chapters: List<ChapterEntity>) {
            chapters.forEach { chapter ->
                this@FakeBookshelfDb.chapters.getOrPut(chapter.bookUrl) { mutableListOf() }.add(chapter)
            }
        }

        override suspend fun getChaptersByBookUrl(bookUrl: String): List<ChapterEntity> =
            this@FakeBookshelfDb.chapters[bookUrl].orEmpty()

        override fun getChaptersFlow(bookUrl: String): Flow<List<ChapterEntity>> =
            flowOf(this@FakeBookshelfDb.chapters[bookUrl].orEmpty())

        override suspend fun getChaptersCount(bookUrl: String): Int =
            this@FakeBookshelfDb.chapters[bookUrl]?.size ?: 0

        override suspend fun updateChapterReadStatus(bookUrl: String, chapterIndex: Int, isRead: Boolean) = Unit

        override suspend fun deleteChaptersByBookUrl(bookUrl: String) {
            this@FakeBookshelfDb.chapters.remove(bookUrl)
        }

        override suspend fun getChapterByUrl(bookUrl: String, chapterUrl: String): ChapterEntity? =
            this@FakeBookshelfDb.chapters[bookUrl]?.firstOrNull { it.url == chapterUrl }
    }
}
