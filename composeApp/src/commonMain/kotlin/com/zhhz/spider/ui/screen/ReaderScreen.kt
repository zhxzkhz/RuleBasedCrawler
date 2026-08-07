package com.zhhz.spider.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhhz.spider.constant.BookType
import com.zhhz.spider.ui.widget.LoadingBox
import com.zhhz.spider.ui.widget.MangaReaderView
import com.zhhz.spider.ui.widget.NovelReaderView
import com.zhhz.spider.viewModel.ReaderUiEffect
import com.zhhz.spider.viewModel.ReaderUiIntent
import com.zhhz.spider.viewModel.ReaderViewModel
import kotlinx.coroutines.launch
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
    onNavigateBack: () -> Unit,
    onNavigateToCatalog: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(chapterIndex, ruleId) {
        viewModel.processIntent(ReaderUiIntent.Init(bookUrl, chapterIndex, ruleId))
    }

    // 统一处理副作用 (Toast、返回等)
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ReaderUiEffect.NavigateBack -> onNavigateBack()
                is ReaderUiEffect.NavigateToCatalogPage -> onNavigateToCatalog(effect.bookUrl, effect.ruleId)
                is ReaderUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(effect.message) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isMenuVisible) {
                ReaderTopBar(
                    title = uiState.title,
                    subtitle = chapterProgressText(uiState.currentIndex, uiState.catalogList.size),
                    onBack = { viewModel.processIntent(ReaderUiIntent.NavigateBack) }
                )
            }
        },
        bottomBar = {
            if (uiState.isMenuVisible) {
                ReaderBottomBar(
                    hasPrev = uiState.hasPrev,
                    hasNext = uiState.hasNext,
                    currentIndex = uiState.currentIndex,
                    totalChapters = uiState.catalogList.size,
                    onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) },
                    onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                    onOpenCatalogPage = {
                        viewModel.processIntent(ReaderUiIntent.NavigateToCatalog)
                    },
                    onOpenSettings = {
                        viewModel.processIntent(ReaderUiIntent.ToggleSettingsPanel)
                    },
                    showReloadChapter = uiState.bookType == BookType.image,
                    onReloadChapter = {
                        viewModel.processIntent(ReaderUiIntent.ReloadCurrentMangaChapter)
                    }
                )
            } else if (uiState.isSettingsVisible) {
                ReaderSettingsPanel(
                    settings = uiState.settings,
                    onFontSizeChange = { delta -> viewModel.processIntent(ReaderUiIntent.ChangeFontSize(delta)) },
                    onChangeTheme = { bg, text -> viewModel.processIntent(ReaderUiIntent.ChangeTheme(bg, text)) },
                    onToggleImmersive = { viewModel.processIntent(ReaderUiIntent.ToggleImmersiveMode) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.isLoading) {
                LoadingBox(Modifier.align(Alignment.Center))
            } else {
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
                            onProgressUpdate = { index, progress ->
                                viewModel.processIntent(ReaderUiIntent.UpdateReadProgress(index, progress))
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

@Composable
fun ReaderSettingsPanel(
    settings: com.zhhz.spider.viewModel.ReaderSettings,
    onFontSizeChange: (Int) -> Unit,
    onChangeTheme: (Long, Long) -> Unit,
    onToggleImmersive: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.9f),
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // 字号调节
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("字号", modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onFontSizeChange(-2) }) { Text("A-", color = Color.White, fontWeight = FontWeight.Bold) }
                    Text("${settings.fontSize}", modifier = Modifier.padding(horizontal = 16.dp))
                    IconButton(onClick = { onFontSizeChange(2) }) { Text("A+", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            // 背景主题
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                // 白昼 (默认)
                ThemeButton(0xFFFAF7F0, 0xFF333333, "默认", settings.backgroundColor, onChangeTheme)
                // 护眼绿
                ThemeButton(0xFFC8E6C9, 0xFF1B5E20, "护眼", settings.backgroundColor, onChangeTheme)
                // 夜间
                ThemeButton(0xFF1E1E1E, 0xFFAAAAAA, "夜间", settings.backgroundColor, onChangeTheme)
                // 羊皮纸
                ThemeButton(0xFFF3E5AB, 0xFF5D4037, "复古", settings.backgroundColor, onChangeTheme)
            }
        }
    }
}

@Composable
fun ThemeButton(bgColor: Long, textColor: Long, label: String, currentBg: Long, onClick: (Long, Long) -> Unit) {
    val isSelected = bgColor == currentBg
    Button(
        onClick = { onClick(bgColor, textColor) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(bgColor)),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(label, color = Color(textColor), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.8f), contentColor = Color.White) {
        TopAppBar(
            title = {
                Column {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
                    }
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back_24px), null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

// 💡 新增的底部菜单栏组件
@Composable
fun ReaderBottomBar(
    hasPrev: Boolean,
    hasNext: Boolean,
    currentIndex: Int,
    totalChapters: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenCatalogPage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    showReloadChapter: Boolean = false,
    onReloadChapter: () -> Unit = {}
) {
    val progress = if (totalChapters > 0) {
        ((currentIndex + 1).coerceIn(0, totalChapters)).toFloat() / totalChapters
    } else {
        0f
    }
    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.18f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars), // 适配全面屏底部小白条
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPrev, enabled = hasPrev) {
                    Text("上一章", color = if (hasPrev) Color.White else Color.Gray)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = onOpenCatalogPage) {
                        Text("目录", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        chapterProgressText(currentIndex, totalChapters),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = onOpenSettings) {
                        Text("设置", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }

                if (showReloadChapter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = onReloadChapter) {
                            Text("重载本章", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                TextButton(onClick = onNext, enabled = hasNext) {
                    Text("下一章", color = if (hasNext) Color.White else Color.Gray)
                }
            }
        }
    }
}

private fun chapterProgressText(currentIndex: Int, totalChapters: Int): String {
    return if (totalChapters > 0 && currentIndex >= 0) {
        "第 ${currentIndex + 1} / $totalChapters 章"
    } else {
        ""
    }
}
