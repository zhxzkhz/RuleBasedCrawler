package com.zhhz.spider

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    // 尽早安装全局错误捕获，覆盖启动阶段
    com.zhhz.spider.util.GlobalErrorHandler.install()
    CompositionLocalProvider(
        LocalWindowExceptionHandlerFactory provides AppWindowExceptionHandlerFactory(
            DefaultWindowExceptionHandlerFactory
        )
    ) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "RuleBasedCrawler",
            state = WindowState(width = 920.dp, height = 760.dp, position = WindowPosition.Aligned(Alignment.Center)),
            onKeyEvent = {
                // 💡 桌面端全局返回键（Esc）分发
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    DesktopBackDispatcher.dispatch()
                } else false
            }
        ) {
            // 安全剪贴板：Windows 下剪贴板被其它程序占用时不再崩溃退出
            WithSafeClipboard {
                App()
            }
        }
    }
}
