package com.zhhz.spider.ui.widget.novel

import androidx.compose.runtime.Composable
import com.zhhz.spider.viewModel.ReaderUiState

@Composable
actual fun NovelReaderEngine(
    uiState: ReaderUiState,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    KmpNovelPagedView(
        uiState = uiState,
        onProgressUpdate = onProgressUpdate,
        onToggleMenu = onToggleMenu,
        onNext = onNext,
        onPrev = onPrev
    )
}

@Composable
actual fun AndroidNovelReader(
    uiState: ReaderUiState,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    // 桌面端无原生阅读器，回退到 KMP 实现
    KmpNovelPagedView(
        uiState = uiState,
        onProgressUpdate = onProgressUpdate,
        onToggleMenu = onToggleMenu,
        onNext = onNext,
        onPrev = onPrev
    )
}
