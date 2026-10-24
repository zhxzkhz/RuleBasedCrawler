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
}
