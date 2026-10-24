package com.zhhz.spider.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SourceRateLimiter 回归测试：旧实现（读-等-写无原子性）在并发下会同时放行，
 * 这里用真实线程验证"同源串行、跨源并行"两个核心语义。
 */
class SourceRateLimiterTest {

    @Test
    fun concurrentAcquiresForSameSourceAreSerialized() {
        val limiter = SourceRateLimiter()
        val threads = 4
        val delayMs = 150L
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val start = System.currentTimeMillis()

        repeat(threads) {
            Thread {
                ready.countDown()
                limiter.acquire("source-a", delayMs)
                done.countDown()
            }.start()
        }

        assertTrue(done.await(10, TimeUnit.SECONDS), "限流获取超时，疑似死锁")
        // 首个请求立即放行，其余 3 个必须依次等待 150ms：总耗时 ≥ 3×150ms。
        // 旧实现下 4 个请求会同时放行，总耗时接近 0
        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            elapsed >= (threads - 1) * delayMs - 50,
            "并发请求未被串行化，总耗时仅 ${elapsed}ms"
        )
    }

    @Test
    fun differentSourcesDoNotBlockEachOther() {
        val limiter = SourceRateLimiter()
        val holderStarted = CountDownLatch(1)
        Thread {
            holderStarted.countDown()
            limiter.acquire("source-a", 800L)
        }.start()

        holderStarted.await()
        Thread.sleep(50) // 确保另一源已进入等待

        val start = System.currentTimeMillis()
        limiter.acquire("source-b", 800L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 500, "不同书源不应互相阻塞，耗时 ${elapsed}ms")
    }

    @Test
    fun zeroDelaySkipsWaiting() {
        val limiter = SourceRateLimiter()
        val start = System.currentTimeMillis()
        repeat(5) { limiter.acquire("source-c", 0L) }
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 200, "delay=0 不应产生等待，耗时 ${elapsed}ms")
    }
}
