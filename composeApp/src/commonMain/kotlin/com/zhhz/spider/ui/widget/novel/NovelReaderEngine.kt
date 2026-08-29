package com.zhhz.spider.ui.widget.novel

import androidx.compose.runtime.Composable
import com.zhhz.spider.viewModel.ReaderUiState

/**
 * 跨平台小说阅读器引擎接口。
 * 默认在 JVM (桌面端) 使用纯 Compose 实现的 KmpNovelPagedView（完美复刻了 ZhLayout 算法）。
 * 在 Android 端，预留了这个接口，你可以自由选择是使用 Compose 实现，
 * 还是随时切回你原生的 AndroidView + StaticLayout 实现。
 */
@Composable
expect fun NovelReaderEngine(
    uiState: ReaderUiState,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
)
