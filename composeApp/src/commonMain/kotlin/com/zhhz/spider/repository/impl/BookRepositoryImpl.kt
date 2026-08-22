package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.model.BookBackup
import com.zhhz.spider.model.BookshelfBackup
import com.zhhz.spider.model.BookshelfImportResult
import com.zhhz.spider.model.toJsonString
import com.zhhz.spider.model.parseBookshelfBackup
import com.zhhz.spider.network.Book
import com.zhhz.spider.repository.BookRepository
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.network.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// 书架功能的具体实现
class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) : BookRepository {

    override fun loadData(): Flow<List<Book>> {
        // Room/SQLDelight 的 Flow 默认已经在后台线程安全处理，直接返回即可
        return bookDao.getAllBooksFlow().map { bookEntities ->
            bookEntities.map {
                it.toDomain()
            }
        }
    }

    override suspend fun deleteData(book: Book) {
        withContext(Dispatchers.IO) {
            bookDao.removeFromBookshelf(book.toEntity())
        }
    }

    override suspend fun exportBackupJson(bookUrls: Collection<String>): String {
        val urls = bookUrls.toList()
        if (urls.isEmpty()) {
            return BookshelfBackup(exportedAt = System.currentTimeMillis()).toJsonString()
        }
        val books = withContext(Dispatchers.IO) { bookDao.getBooksByUrls(urls) }
        val backups = books.map { entity ->
            BookBackup(
                book = entity,
                chapters = chapterDao.getChaptersByBookUrl(entity.detailUrl)
            )
        }
        return BookshelfBackup(
            exportedAt = System.currentTimeMillis(),
            books = backups
        ).toJsonString()
    }

    override suspend fun importBackupJson(json: String): BookshelfImportResult = withContext(Dispatchers.IO) {
        val backup = parseBookshelfBackup(json)
        var addedCount = 0
        var skippedCount = 0
        backup.books.forEach { item ->
            val existing = bookDao.getBookByUrl(item.book.detailUrl)
            if (existing != null) {
                skippedCount++
                return@forEach
            }
            bookDao.addToBookshelf(item.book.copy(inLibrary = true))
            if (item.chapters.isNotEmpty()) {
                bookDao.insertChaptersIgnore(item.chapters.map { it.copy(id = 0) })
            }
            addedCount++
        }
        BookshelfImportResult(addedCount = addedCount, skippedCount = skippedCount)
    }
}
