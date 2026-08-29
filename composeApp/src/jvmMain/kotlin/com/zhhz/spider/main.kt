package com.zhhz.spider

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

fun main() = application {
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