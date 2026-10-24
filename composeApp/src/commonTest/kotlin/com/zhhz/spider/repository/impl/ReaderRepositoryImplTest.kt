package com.zhhz.spider.repository.impl

import com.zhhz.spider.viewModel.MangaImage
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

    // ---------- 小说章节缓存 JSON 的编码/解码 ----------
    // 历史事故：导出侧曾把含插图的 CachedNovelChapter JSON 原样写进 TXT。
    // 读取端必须能无损还原 text + inlineImages，旧版纯文本缓存回退为原文。

    @Test
    fun decodeCachedNovelChapterRoundTripsTextAndInlineImages() {
        val cached = CachedNovelChapter(
            text = "前文▩https://cdn.example.com/1.jpg▩后文",
            inlineImages = listOf(
                MangaImage(
                    url = "https://cdn.example.com/1.jpg",
                    headers = mutableMapOf("Referer" to "https://example.com"),
                    ruleId = "rule-a"
                )
            )
        )
        val encoded = kotlinx.serialization.json.Json.encodeToString(cached)

        val decoded = decodeCachedNovelChapter(encoded)

        assertEquals(cached.text, decoded?.text)
        assertEquals(cached.inlineImages, decoded?.inlineImages)
    }

    @Test
    fun decodeCachedNovelChapterFallsBackToNullForLegacyPlainText() {
        // 旧版缓存是纯文本正文，不是 JSON —— 必须返回 null 让调用方按原文使用
        assertEquals(null, decodeCachedNovelChapter("第一行正文\n第二行正文"))
        assertEquals(null, decodeCachedNovelChapter(""))
    }

    @Test
    fun decodeCachedNovelChapterReturnsNullForCorruptedJson() {
        // JSON 对象但不是 CachedNovelChapter 结构（缺 inlineImages 字段）
        assertEquals(null, decodeCachedNovelChapter("""{"text":"只有文本"}"""))
        // 截断的 JSON
        assertEquals(null, decodeCachedNovelChapter("""{"text":"前文"""))
    }
}
