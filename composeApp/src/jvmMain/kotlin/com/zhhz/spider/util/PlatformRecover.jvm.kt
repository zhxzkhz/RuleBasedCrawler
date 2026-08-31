package com.zhhz.spider.util

actual fun platformRecover(thread: Thread, throwable: Throwable) {
    // 桌面端异常由 WindowExceptionHandlerFactory 拦截系统弹窗，
    // WindowExceptionHandler 回调时 UI 线程仍可正常工作。
}

actual fun platformInstallExceptionBoundary(onError: (Throwable) -> Unit) {
    // AWT EventQueue 的 dispatchEvent 只捕获事件分发阶段异常，
    // Compose Desktop 的 WindowExceptionHandlerFactory 是正确拦截点，这里为空实现。
}
