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

    @Test
    fun extractNovelContentPreservesImagesAndPrefersLazyLoadingUrl() {
        val content = extractNovelContent(
            rawContent = listOf("<p>前文<img src='/placeholder.jpg' data-original='images/page.jpg'>后文</p>"),
            chapterUrl = "https://example.com/books/1/chapter/2",
            imageHeaders = mapOf("Referer" to "https://example.com/books/1"),
            ruleId = "rule-a"
        )

        assertEquals("前文▩https://example.com/books/1/chapter/images/page.jpg▩后文", content.text)
        assertEquals(1, content.inlineImages.size)
        assertEquals("https://example.com/books/1/chapter/images/page.jpg", content.inlineImages.single().url)
        assertEquals("https://example.com/books/1", content.inlineImages.single().headers["Referer"])
    }

    @Test
    fun extractNovelContentKeepsAllImagePositions() {
        val content = extractNovelContent(
            rawContent = listOf("甲<img data-src='https://cdn.example.com/1.jpg'>乙<img src='https://cdn.example.com/2.jpg'>丙"),
            chapterUrl = "https://example.com/chapter/1",
            imageHeaders = emptyMap(),
            ruleId = "rule-a"
        )

        assertEquals("甲▩https://cdn.example.com/1.jpg▩乙▩https://cdn.example.com/2.jpg▩丙", content.text)
        assertEquals(listOf("https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg"), content.inlineImages.map { it.url })
    }

    @Test
    fun extractNovelContentSupportsRulesThatDirectlyReturnImageUrls() {
        val content = extractNovelContent(
            rawContent = listOf("正文", "/images/illustration.webp", "结尾"),
            chapterUrl = "https://example.com/books/1/chapter/2",
            imageHeaders = emptyMap(),
            ruleId = "rule-a"
        )

        assertEquals("正文\n▩https://example.com/images/illustration.webp▩\n结尾", content.text)
        assertEquals("https://example.com/images/illustration.webp", content.inlineImages.single().url)
    }
}
