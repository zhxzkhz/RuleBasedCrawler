package com.zhhz.spider.util

import kotlinx.coroutines.delay

/**
 * 💡 KMP 通用的挂起函数重试工具
 */
suspend fun <T> retry(
    maxAttempts: Int,
    delayBaseMs: Long = 1000L,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block() // 尝试执行
        } catch (e: Exception) {
            attempt++
            if (attempt >= maxAttempts) {
                throw e // 重试次数耗尽，彻底抛出异常
            }
            // 指数退避延迟：1秒, 2秒, 3秒...
            delay(attempt * delayBaseMs)
        }
    }
}

