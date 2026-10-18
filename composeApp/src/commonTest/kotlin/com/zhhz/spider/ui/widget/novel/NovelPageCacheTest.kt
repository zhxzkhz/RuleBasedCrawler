package com.zhhz.spider.ui.widget.novel

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class NovelPageCacheTest {

    private val config = ReadBookConfig(
        textSize = 18,
        lineSpacing = 10,
        paragraphSpacing = 5,
        margin = 40f,
        fontFamily = "serif"
    )

    @AfterTest
    fun resetCache() {
        NovelPageCache.clear()
    }

    @Test
    fun putThenGetReturnsSameInstance() {
        val pages = listOf(TextPage())
        val key = novelPageCacheKey("sample", 1080, 1920, config)
        NovelPageCache.put(key, pages)
        assertSame(pages, NovelPageCache.get(key))
    }

    @Test
    fun differentConfigInvalidatesCacheEntry() {
        val key = novelPageCacheKey("sample", 1080, 1920, config)
        NovelPageCache.put(key, listOf(TextPage()))
        val other = config.copy(textSize = 22)
        assertNull(NovelPageCache.get(novelPageCacheKey("sample", 1080, 1920, other)))
    }

    @Test
    fun differentTextDoesNotCollide() {
        val key = novelPageCacheKey("alpha", 1080, 1920, config)
        NovelPageCache.put(key, listOf(TextPage()))
        assertNotNull(NovelPageCache.get(key))
        assertNull(NovelPageCache.get(novelPageCacheKey("beta", 1080, 1920, config)))
    }

    @Test
    fun differentSizeDoesNotCollide() {
        val key = novelPageCacheKey("alpha", 1080, 1920, config)
        NovelPageCache.put(key, listOf(TextPage()))
        assertNotNull(NovelPageCache.get(key))
        assertNull(NovelPageCache.get(novelPageCacheKey("alpha", 720, 1920, config)))
    }

    @Test
    fun lruEvictsOldestEntry() {
        val capacity = 16
        // 填满 capacity 项
        repeat(capacity) { i ->
            NovelPageCache.put(
                novelPageCacheKey("text-$i", 1080, 1920, config),
                listOf(TextPage())
            )
        }
        assertEquals(capacity, NovelPageCache.size())
        val oldestKey = novelPageCacheKey("text-0", 1080, 1920, config)
        assertNotNull(NovelPageCache.get(oldestKey))

        // 再插入一项后，最早的 text-0 应当被淘汰，容量仍为 capacity
        NovelPageCache.put(novelPageCacheKey("newer", 1080, 1920, config), listOf(TextPage()))
        assertEquals(capacity, NovelPageCache.size())
        assertNull(NovelPageCache.get(oldestKey))
        assertNotNull(NovelPageCache.get(novelPageCacheKey("newer", 1080, 1920, config)))
    }
}
