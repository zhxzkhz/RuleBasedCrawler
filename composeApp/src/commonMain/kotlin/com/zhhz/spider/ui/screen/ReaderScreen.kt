package com.zhhz.spider.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.zhhz.spider.constant.BookType
import com.zhhz.spider.ui.widget.LoadingBox
import com.zhhz.spider.ui.widget.MangaReaderView
import com.zhhz.spider.ui.widget.NovelReaderView
import com.zhhz.spider.viewModel.ReaderUiEffect
import com.zhhz.spider.viewModel.ReaderUiIntent
import com.zhhz.spider.viewModel.ReaderViewModel
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    // 💡 从导航路由传进来的核心凭证
    bookUrl: String,
    chapterIndex: Int,
    ruleId: String,
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // 💡 核心补全：页面进入时，必须发送 Init 意图，否则 ViewModel 不知道读哪一章
    LaunchedEffect(chapterIndex, ruleId) {
        viewModel.processIntent(ReaderUiIntent.Init(bookUrl, chapterIndex, ruleId))
    }

    // 统一处理副作用 (Toast、返回等)
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ReaderUiEffect.NavigateBack -> onNavigateBack()
                is ReaderUiEffect.ShowToast -> { /* 显示 Snackbar */ }
            }
        }
    }

    // 根据状态控制菜单显示
    Scaffold(
        topBar = {
            if (uiState.isMenuVisible) {
                ReaderTopBar(
                    title = uiState.title,
                    onBack = { viewModel.processIntent(ReaderUiIntent.NavigateBack) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.isLoading) {
                // 统一的加载占位
                LoadingBox(Modifier.align(Alignment.Center))
            } else {
                // 💡 之前提到的逻辑分发：根据书本类型渲染不同视图
                when (uiState.bookType) {
                    BookType.text -> {
                        NovelReaderView(
                            uiState = uiState,
                            onToggleMenu = { viewModel.processIntent(ReaderUiIntent.ToggleMenu) },
                            onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                            onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) }
                        )
                    }
                    BookType.image -> {
                        MangaReaderView(
                            ruleId = ruleId,
                            uiState = uiState,
                            onProgressUpdate = { index,progress ->
                                viewModel.processIntent(ReaderUiIntent.UpdateReadProgress(index,progress))
                            },
                            onToggleMenu = { viewModel.processIntent(ReaderUiIntent.ToggleMenu) },
                            onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                            onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) }
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(title:String, onBack: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.8f), contentColor = Color.White) {
        TopAppBar(
            title = { Text(text = title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back_24px), null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}