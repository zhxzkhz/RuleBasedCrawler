package com.zhhz.spider.network

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 网页快照缓存键回归测试。
 *
 * 键结构：method|url|postBody|cacheSalt（规则指纹）。
 * - salt 是"改完规则仍读到旧页面"问题的修复核心：规则保存（updateTime 变化）→ salt 变 → 键变 → 旧快照自动失效；
 * - POST body 参与键，保证同 URL 不同负载的搜索/登录不会互相串页面。
 */
class FileSnapshotInterceptorTest {

    private fun get(url: String) = Request.Builder().url(url).get().build()

    private fun post(url: String, body: String) =
        Request.Builder().url(url).post(body.toRequestBody()).build()

    @Test
    fun getCacheKeyIsStableForIdenticalRequests() {
        assertEquals(
            get("https://example.com/search?q=1").generateCacheKey(),
            get("https://example.com/search?q=1").generateCacheKey()
        )
    }

    @Test
    fun getCacheKeySeparatesUrls() {
        assertNotEquals(
            get("https://example.com/search?q=1").generateCacheKey(),
            get("https://example.com/search?q=2").generateCacheKey()
        )
    }

    @Test
    fun postBodyParticipatesInCacheKey() {
        assertNotEquals(
            post("https://example.com/login", "user=a").generateCacheKey(),
            post("https://example.com/login", "user=b").generateCacheKey()
        )
    }

    @Test
    fun cacheSaltInvalidatesKeyWithoutUrlChange() {
        val request = get("https://example.com/catalog")
        val v1 = request.generateCacheKey(cacheSalt = "rule-1|1000")
        val v2 = request.generateCacheKey(cacheSalt = "rule-1|1001") // 规则保存后 updateTime 变化

        assertNotEquals(v1, v2)
        assertEquals(v1, request.generateCacheKey(cacheSalt = "rule-1|1000"))
    }

    @Test
    fun cacheSaltSeparatesRulesSharingSameUrl() {
        // 换源/镜像站对同一 URL 各自持有一份快照
        assertNotEquals(
            get("https://cdn.example.com/page").generateCacheKey(cacheSalt = "rule-a|1"),
            get("https://cdn.example.com/page").generateCacheKey(cacheSalt = "rule-b|1")
        )
    }

    // ---------- 规则保存后的快照回收 ----------
    // 快照键含规则指纹：保存后旧键文件成永久孤儿，clearCache 整体回收（内容可再生）

    @Test
    fun clearCacheRemovesSnapshotsButKeepsFormatMarker() {
        val dir = java.nio.file.Files.createTempDirectory("snapshot-test").toFile()
        try {
            // 模拟既有缓存状态：格式标记 + 两份快照
            java.io.File(dir, ".snapshot-v2").createNewFile()
            java.io.File(dir, "abc.txt").writeText("旧快照")
            java.io.File(dir, "def.txt").writeText("另一份旧快照")

            FileSnapshotInterceptor(dir).clearCache()

            val remaining = dir.listFiles()!!.map { it.name }.sorted()
            // 快照全删，格式标记保留（避免下次请求触发全目录重复清理）
            assertEquals(listOf(".snapshot-v2"), remaining)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearCacheIsSafeOnEmptyOrMissingDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("snapshot-empty").toFile()
        try {
            FileSnapshotInterceptor(dir).clearCache() // 空目录不抛异常
            assertEquals(0, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
