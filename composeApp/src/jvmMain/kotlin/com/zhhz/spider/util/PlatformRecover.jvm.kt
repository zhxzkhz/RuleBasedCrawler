package com.zhhz.spider.util

actual fun platformRecover(thread: Thread, throwable: Throwable) {
    // 桌面端无需特殊恢复：非 UI 线程异常吞掉即可；
    // UI 线程异常由 Compose 窗口事件循环自行处理
}
