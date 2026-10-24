package com.zhhz.spider.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 章节缓存文件名回归测试。
 *
 * 历史事故：导出侧曾单独使用 chapterUrl.toMd5()，与写入侧的复合键
 * ruleId|bookUrl|chapterUrl 不一致，导致"仅导出已缓存"整章被跳过。
 * 这里把复合键格式钉死：写入（ReaderRepositoryImpl）、清理（BookCleanupManager）、
 * 导出（BookPackager 双端）四处共用本函数，格式一变全部旧缓存立即失效。
 */
class HashUtilsTest {

    @Test
    fun toMd5ProducesKnownHexDigest() {
        // "abc" 的 MD5 是公开已知值，钉住算法本身不被悄悄替换
        assertEquals("900150983cd24fb0d6963f7d28e17f72", "abc".toMd5())
        assertEquals(32, "任意内容".toMd5().length)
        assertTrue("任意内容".toMd5().all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun chapterCacheFileNamePinsLegacyCompositeKeyFormat() {
        // 键内容必须与历史写入侧完全一致："ruleId|bookUrl|chapterUrl"，改动即导致既有缓存全灭
        val name = chapterCacheFileName("rule-1", "https://book.example/1", "https://book.example/1/c1")
        assertEquals("rule-1|https://book.example/1|https://book.example/1/c1".toMd5() + ".txt", name)
    }

    @Test
    fun chapterCacheFileNameSeparatesEveryComponent() {
        val base = chapterCacheFileName("rule-1", "book-1", "chapter-1")
        assertNotEquals(base, chapterCacheFileName("rule-2", "book-1", "chapter-1"))
        assertNotEquals(base, chapterCacheFileName("rule-1", "book-2", "chapter-1"))
        assertNotEquals(base, chapterCacheFileName("rule-1", "book-1", "chapter-2"))
    }

    @Test
    fun chapterCacheFileNameIsStableAndFilesystemSafe() {
        val ugly = chapterCacheFileName(
            ruleId = "规则 id/with:特殊<chars>",
            bookUrl = "https://host/a b?c=中文&x=1",
            chapterUrl = "https://host/a b/c d#frag"
        )
        // MD5 十六进制 + 后缀：任何输入都不会产生路径分隔符等危险字符
        assertTrue(ugly.matches(Regex("[0-9a-f]{32}\\.txt")), "文件名不安全: $ugly")
        assertEquals(ugly, chapterCacheFileName(
            ruleId = "规则 id/with:特殊<chars>",
            bookUrl = "https://host/a b?c=中文&x=1",
            chapterUrl = "https://host/a b/c d#frag"
        ))
    }
}
