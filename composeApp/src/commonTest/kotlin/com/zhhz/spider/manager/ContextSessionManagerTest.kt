package com.zhhz.spider.manager

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
}
