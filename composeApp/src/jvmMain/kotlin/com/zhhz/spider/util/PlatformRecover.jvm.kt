package com.zhhz.spider.util

import java.awt.EventQueue
import java.awt.Toolkit
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

actual fun platformRecover(thread: Thread, throwable: Throwable) {
    // AWT 事件线程由 platformInstallExceptionBoundary 负责隔离，
    // 这里不调用默认处理器，避免系统错误弹窗和进程退出。
}

actual fun platformInstallExceptionBoundary(onError: (Throwable) -> Unit) {
    // AWT 的 EventQueue 会把事件线程中的未捕获异常交给 dispatchEvent。
    // 安装自定义队列后吞掉异常，后续事件仍可继续分发。
    val queue = object : EventQueue() {
        override fun dispatchEvent(event: java.awt.AWTEvent) {
            try {
                super.dispatchEvent(event)
            } catch (throwable: Throwable) {
                logger.error(throwable) { "AWT/Compose 事件处理异常" }
                onError(throwable)
            }
        }
    }
    runCatching { Toolkit.getDefaultToolkit().systemEventQueue.push(queue) }
        .onFailure { logger.warn(it) { "安装桌面事件异常边界失败" } }
}
