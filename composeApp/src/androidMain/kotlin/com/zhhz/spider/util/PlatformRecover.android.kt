package com.zhhz.spider.util

import android.os.Looper
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

actual fun platformRecover(thread: Thread, throwable: Throwable) {
    // 主线程异常被吞掉后消息循环已停止，重启 Looper 防止 UI 冻结，
    // 让用户能看到错误提示框并继续操作
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
