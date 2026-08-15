package com.zhhz.spider.manager

import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.repository.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContextSessionManagerTest {

    @Test
    fun getContextClonesRuleContextIntoBookContext() = runTest {
        val manager = ContextSessionManager()
        val ruleContext = manager.getContext("rule-a")
        ruleContext["token"] = "source-token"
        ruleContext["page"] = "1"

        val bookContext = manager.getContext("book-a", "rule-a")

        assertEquals("source-token", bookContext["token"])
        assertEquals("1", bookContext["page"])

        bookContext["token"] = "book-token"

        assertEquals("source-token", ruleContext["token"])
        assertEquals("book-token", manager.getContext("book-a")["token"])
    }

    @Test
    fun forkContextDoesNotOverwriteExistingBookContext() = runTest {
        val manager = ContextSessionManager()
        manager.getContext("rule-a")["token"] = "source-token"
        manager.getContext("book-a")["token"] = "existing-book-token"

        manager.forkContext(fromKey = "rule-a", bookUrl = "book-a")

        assertEquals("existing-book-token", manager.getContext("book-a")["token"])
    }

    @Test
    fun getContextCreatesEmptyContextWhenRuleContextDoesNotExist() = runTest {
        val manager = ContextSessionManager()

        val bookContext = manager.getContext("book-a", "missing-rule")

        assertNull(bookContext["token"])
        bookContext["bookUrl"] = "book-a"
        assertEquals("book-a", manager.getContext("book-a")["bookUrl"])
    }

    @Test
    fun activeContextUsesCurrentBookUrlWithoutAvailableSources() = runTest {
        val manager = ContextSessionManager()
        val repository = TestSessionRepository(
            Book("book-a", "Book A", "", "", "rule-a")
        )

        val context = manager.getActiveContext(repository, "rule-a")
        context["marker"] = "book context"

        assertEquals("book context", manager.getContext("book-a")["marker"])
        assertNull(manager.getContext("rule-a")["marker"])
    }

    private class TestSessionRepository(private var book: Book?) : SessionRepository {
        private var catalog = emptyList<Chapter>()
        override suspend fun saveData(book: Book) { this.book = book }
        override fun loadData(): Book? = book
        override fun clearData() { book = null }
        override fun saveCatalog(chapters: List<Chapter>) { catalog = chapters }
        override fun loadCatalog(): List<Chapter> = catalog
    }
}
