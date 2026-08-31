package com.zhhz.spider.util

import android.os.Looper
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

actual fun platformRecover(thread: Thread, throwable: Throwable) {
    // 主线程异常被吞掉后消息循环已停止，重启 Looper 防止 UI 冻结。
    if (thread == Looper.getMainLooper().thread) {
        while (true) {
            try {
                Looper.loop()
            } catch (e: Throwable) {
                logger.error(e) { "主线程消息循环重启后再次异常" }
                GlobalErrorHandler.report(e)
            }
        }
    }
}

actual fun platformInstallExceptionBoundary(onError: (Throwable) -> Unit) {
    // Android 的主线程异常由 platformRecover 负责恢复消息循环。
}
