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
    // 桌面端完全使用纯 Compose 实现的仿真翻页引擎
    KmpNovelPagedView(
        uiState = uiState,
        onProgressUpdate = onProgressUpdate,
        onToggleMenu = onToggleMenu,
        onNext = onNext,
        onPrev = onPrev
    )
}
