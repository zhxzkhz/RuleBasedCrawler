package com.zhhz.spider.repository.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReaderRepositoryImplTest {

    @Test
    fun decodeImageHeadersAcceptsStringMapJson() {
        val headers = decodeImageHeaders(
            rawHeaders = """{"Referer":"https://example.com","User-Agent":"RuleBasedCrawler"}""",
            sourceName = "test-source",
            chapterUrl = "https://example.com/chapter/1"
        )

        assertEquals("https://example.com", headers["Referer"])
        assertEquals("RuleBasedCrawler", headers["User-Agent"])
    }

    @Test
    fun decodeImageHeadersRejectsBlankResult() {
        val error = assertFailsWith<IllegalArgumentException> {
            decodeImageHeaders(
                rawHeaders = "",
                sourceName = "test-source",
                chapterUrl = "https://example.com/chapter/1"
            )
        }

        assertTrue(error.message.orEmpty().contains("图片 Headers 解析失败"))
    }

    @Test
    fun decodeImageHeadersRejectsInvalidJson() {
        val error = assertFailsWith<IllegalArgumentException> {
            decodeImageHeaders(
                rawHeaders = "Referer=https://example.com",
                sourceName = "test-source",
                chapterUrl = "https://example.com/chapter/1"
            )
        }

        assertTrue(error.message.orEmpty().contains("必须返回 JSON 对象"))
    }

    @Test
    fun decodeImageHeadersRejectsNonStringValues() {
        val error = assertFailsWith<IllegalArgumentException> {
            decodeImageHeaders(
                rawHeaders = """{"Referer":123}""",
                sourceName = "test-source",
                chapterUrl = "https://example.com/chapter/1"
            )
        }

        assertTrue(error.message.orEmpty().contains("键和值都为字符串"))
    }
}
