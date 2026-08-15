package com.zhhz.spider.manager

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookSessionManagerTest {
    @Test
    fun switchingBookClearsPreviousCatalog() {
        val manager = BookSessionManager()
        manager.setCurrentBook(book("search-book"))
        manager.setCatalog(listOf(Chapter(0, "search chapter", "search chapter url")))

        manager.setCurrentBook(book("shelf-book"))

        assertEquals("shelf-book", manager.getCurrentBook()?.url)
        assertTrue(manager.getCatalog().isEmpty())
    }

    @Test
    fun updatingSameBookKeepsCatalog() {
        val manager = BookSessionManager()
        manager.setCurrentBook(book("same-book"))
        val catalog = listOf(Chapter(0, "chapter", "chapter url"))
        manager.setCatalog(catalog)

        manager.setCurrentBook(book("same-book").copy(title = "updated"))

        assertEquals(catalog, manager.getCatalog())
    }

    private fun book(url: String) = Book(
        url = url,
        title = url,
        author = "",
        cover = "",
        ruleId = "rule"
    )
}
