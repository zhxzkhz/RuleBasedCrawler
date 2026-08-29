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
    // 💡 这里保留了安卓端的专属接口！
    // 目前为了跨平台统一，默认先跑 KmpNovelPagedView。
    // 如果你发现跨平台 Compose 版本在手机上预期不达标，
    // 你可以随时在这里把代码替换成调用你 E 盘里原生的 `ReadView(...)` ！
    
    KmpNovelPagedView(
        uiState = uiState,
        onProgressUpdate = onProgressUpdate,
        onToggleMenu = onToggleMenu,
        onNext = onNext,
        onPrev = onPrev
    )
}
