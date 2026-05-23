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
        App()
    }
}