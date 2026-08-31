package com.zhhz.spider

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import com.zhhz.spider.util.GlobalErrorHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalComposeUiApi::class)
class AppWindowExceptionHandlerFactory(
    private val defaultFactory: WindowExceptionHandlerFactory
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler {
        val fallback = defaultFactory.exceptionHandler(window)
        return WindowExceptionHandler { throwable ->
            // 用全局错误框替代 Compose Desktop 默认的系统错误弹窗
            // 默认实现会弹 Swing 对话框并退出进程，这里不调用 fallback.onException
            GlobalErrorHandler.report(throwable)
            logger.error(throwable) { "桌面窗体异常已拦截" }
        }
    }
}
