package com.zhhz.spider.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaImageCacheTest {

    @Test
    fun memoryKeysSeparateReaderInputsAndTargetSizes() {
        val base = mangaImageMemoryCacheKey("rule-a", "book-a", "image", false, 1080)

        assertNotEquals(base, mangaImageMemoryCacheKey("rule-b", "book-a", "image", false, 1080))
        assertNotEquals(base, mangaImageMemoryCacheKey("rule-a", "book-b", "image", false, 1080))
        assertNotEquals(base, mangaImageMemoryCacheKey("rule-a", "book-a", "image", true, 1080))
        assertNotEquals(base, mangaImageMemoryCacheKey("rule-a", "book-a", "image", false, 720))
    }

    @Test
    fun validAspectRatioRejectsInvalidIntrinsicSizes() {
        assertEquals(2f, validImageAspectRatio(200f, 100f))
        assertNull(validImageAspectRatio(0f, 100f))
        assertNull(validImageAspectRatio(200f, 0f))
        assertNull(validImageAspectRatio(Float.NaN, 100f))
        assertNull(validImageAspectRatio(Float.POSITIVE_INFINITY, 100f))
    }

    @Test
    fun diskCacheKeySeparatesRulesAndNeverMatchesLegacyBareUrl() {
        // 同源同图稳定
        assertEquals(
            mangaImageDiskCacheKey("rule-a", "https://cdn/x/1.jpg"),
            mangaImageDiskCacheKey("rule-a", "https://cdn/x/1.jpg")
        )
        // 换源/镜像站对同一 URL 产出不同键——磁盘成品互不冒领
        assertNotEquals(
            mangaImageDiskCacheKey("rule-a", "https://cdn/x/1.jpg"),
            mangaImageDiskCacheKey("rule-b", "https://cdn/x/1.jpg")
        )
        // v2 前缀保证与历史裸 URL 键永不冲突（升级后旧条目只能等一次性清理回收）
        assertTrue(mangaImageDiskCacheKey("rule-a", "img").startsWith("v2|"))
        assertNotEquals("https://cdn/x/1.jpg", mangaImageDiskCacheKey("rule-a", "https://cdn/x/1.jpg"))
    }
}
