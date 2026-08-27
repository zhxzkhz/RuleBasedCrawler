package com.zhhz.spider

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "RuleBasedCrawler",

        state = WindowState(width = 920.dp, height = 760.dp,position = WindowPosition.Aligned(Alignment.Center))
    ) {
        // 安全剪贴板：Windows 下剪贴板被其它程序占用时不再崩溃退出
        WithSafeClipboard {
            App()
        }
    }
}