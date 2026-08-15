package com.zhhz.spider.rule

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NhentaiRuleFileTest {
    @Test
    fun ruleFileMatchesSourceRuleSchema() {
        val rule = loadRule()

        assertEquals("nhentai-net", rule.id)
        assertEquals(4, rule.type)
        assertTrue(rule.search.listSelector.steps.isNotEmpty())
        assertTrue(rule.catalog.chapterListSelector.steps.isNotEmpty())
        assertTrue(rule.content.contentSelector.steps.isNotEmpty())
    }

    @Test
    fun extractsGalleryAndOriginalImages() {
        val rule = loadRule()
        val context = mutableMapOf(
            "bookUrl" to "https://nhentai.net/g/123456/",
            "chapterUrl" to "https://nhentai.net/g/123456/"
        )
        val searchJson = """
            {"result":[{"id":123456,"english_title":"Example Gallery","thumbnail":"galleries/999/thumb.webp"}]}
        """.trimIndent()
        val detailJson = """
            {"pages":[{"path":"galleries/999/1.webp"},{"path":"galleries/999/2.png"}]}
        """.trimIndent()

        val gallery = rule.search.getList(searchJson, context).single()
        assertEquals("Example Gallery", rule.search.getName(gallery, context))
        assertEquals("https://nhentai.net/api/v2/galleries/123456", rule.search.getDetailUrl(gallery, context))
        assertEquals(
            listOf(
                "https://i.nhentai.net/galleries/999/1.webp",
                "https://i.nhentai.net/galleries/999/2.png"
            ),
            rule.content.getContent(detailJson, context).map(Any::toString)
        )
        val chapter = rule.catalog.getChapters(detailJson, context).single()
        assertEquals("全集", rule.catalog.getChapterName(chapter, context))
        assertEquals(context["bookUrl"], rule.catalog.getChapterUrl(chapter, context))
    }

    @Test
    fun liveApiWorksThroughLocalProxy() {
        val rule = loadRule()
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 10808)))
            .build()
        val context = mutableMapOf("page" to "1")
        val searchUrl = rule.search.getUrl("english", context)
        val searchJson = client.get(searchUrl)
        val gallery = rule.search.getList(searchJson, context).first()
        val detailUrl = rule.search.getDetailUrl(gallery, context)
        val detailJson = client.get(detailUrl)
        val imageUrl = rule.content.getContent(detailJson, context).first().toString()

        assertTrue(rule.detail.getBookName(detailJson, context).isNotBlank())
        assertTrue(imageUrl.startsWith("https://i.nhentai.net/galleries/"))
        val imageRequest = Request.Builder()
            .url(imageUrl)
            .header("Referer", "https://nhentai.net/")
            .build()
        client.newCall(imageRequest).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertTrue(response.body.contentLength() > 0)
        }
    }

    private fun loadRule(): SourceRule {
        val ruleFile = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "nhentai.rule.json") }
            .firstOrNull(File::isFile)
            ?: error("找不到 nhentai.rule.json")
        return ruleJson.decodeFromString(ruleFile.readText())
    }

    private fun OkHttpClient.get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}: $url" }
            response.body.string()
        }
    }

    private companion object {
        val ruleJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }
}
