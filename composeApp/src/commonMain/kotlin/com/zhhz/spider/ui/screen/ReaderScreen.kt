package com.zhhz.spider.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }


    // 💡 1. 声明抽屉状态和协程（控制目录侧滑菜单的开合）
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(chapterIndex, ruleId) {
        viewModel.processIntent(ReaderUiIntent.Init(bookUrl, chapterIndex, ruleId))
    }

    // 统一处理副作用 (Toast、返回等)
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ReaderUiEffect.NavigateBack -> onNavigateBack()
                is ReaderUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(effect.message) }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 💡 极致细节：只有当菜单呼出时，才允许手势划出目录。防止用户看漫画左滑时不小心把目录拉出来
        gesturesEnabled = uiState.isMenuVisible,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp) // 控制侧边目录的宽度
            ) {
                // 目录头部
                Text(
                    text = "目录 (${uiState.catalogList.size} 章)",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider()

                val catalogListState = rememberLazyListState()

                // 💡 自动寻路黑科技：每次打开抽屉，自动滚动到当前阅读的章节，并让它显示在屏幕中间偏上！
                LaunchedEffect(drawerState.isOpen, uiState.currentIndex) {
                    if (drawerState.isOpen && uiState.currentIndex >= 0) {
                        catalogListState.scrollToItem(maxOf(0, uiState.currentIndex - 3))
                    }
                }

                // 目录列表渲染
                LazyColumn(state = catalogListState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(uiState.catalogList) { index, chapter ->
                        val isSelected = index == uiState.currentIndex

                        TextButton(
                            onClick = {
                                // 💡 选中章节：关闭抽屉，发射意图让 ViewModel 去换章！
                                scope.launch { drawerState.close() }
                                viewModel.processIntent(ReaderUiIntent.ChangeChapter(index))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Text(
                                text = chapter.title,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    ) {
        // 根据状态控制菜单显示
        Scaffold(
            topBar = {
                if (uiState.isMenuVisible) {
                    ReaderTopBar(
                        title = uiState.title,
                        onBack = { viewModel.processIntent(ReaderUiIntent.NavigateBack) }
                    )
                }
            },
            bottomBar = {
                if (uiState.isMenuVisible) {
                    ReaderBottomBar(
                        hasPrev = uiState.hasPrev,
                        hasNext = uiState.hasNext,
                        onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) },
                        onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                        onOpenCatalog = {
                            // 💡 唤起左侧抽屉目录！
                            scope.launch { drawerState.open() }
                        }
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

// 💡 新增的底部菜单栏组件
@Composable
fun ReaderBottomBar(
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenCatalog: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars), // 适配全面屏底部小白条
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPrev, enabled = hasPrev) {
                Text("上一章", color = if (hasPrev) Color.White else Color.Gray)
            }

            // 目录唤起按钮
            TextButton(onClick = onOpenCatalog) {
                Text("目录", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }

            TextButton(onClick = onNext, enabled = hasNext) {
                Text("下一章", color = if (hasNext) Color.White else Color.Gray)
            }
        }
    }
}