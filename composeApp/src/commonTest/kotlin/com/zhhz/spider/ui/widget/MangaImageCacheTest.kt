package com.zhhz.spider.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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
}
