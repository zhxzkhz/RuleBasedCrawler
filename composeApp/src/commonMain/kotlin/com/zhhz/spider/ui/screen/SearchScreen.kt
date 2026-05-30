package com.zhhz.spider.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.network.toDomain
import com.zhhz.spider.ui.widget.SearchResultItem
import com.zhhz.spider.ui.widget.SearchTopBar
import com.zhhz.spider.viewModel.SearchUiEffect
import com.zhhz.spider.viewModel.SearchUiIntent
import com.zhhz.spider.viewModel.SearchViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel, // 统一使用 Koin 注入
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (DetailRoute) -> Unit // 传入书本 url 或 id
) {
    // 1. 统一收集 UI 状态
    val uiState by viewModel.uiState.collectAsState()

    // 2. 统一处理副作用 (如 Toast, 跳转)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is SearchUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()

                    // 2. 开启子协程去挂起显示，这样不会阻塞 collect 继续接收后续的 Effect
                    launch {
                        snackbarHostState.showSnackbar(message = effect.message)
                    }
                }
                is SearchUiEffect.NavigateToDetail -> {
                    // 现在的 effect 里面只有基础类型了，绝对不会再报 NavType 错误
                    onNavigateToDetail(effect.route)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 将 TopBar 的输入和点击事件，转化为 Intent 发送给 ViewModel
            SearchTopBar(
                keyword = uiState.keyword,
                onKeywordChange = {
                    viewModel.processIntent(SearchUiIntent.UpdateKeyword(it))
                },
                onSearchClick = {
                    viewModel.processIntent(SearchUiIntent.ExecuteSearch)
                },
                onBackClick = onNavigateBack
            )

            // 💡 新增：利用 AnimatedVisibility 做一个平滑显示的进度条
            AnimatedVisibility(visible = uiState.isSearchOngoing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 根据状态渲染不同 UI 逻辑统一
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.searchResults.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.searchResults) { book -> // book 此时是 SearchBook
                        SearchResultItem(
                            book = book,
                            onClick = {
                                // 由于一本书有多个源，点击进入详情时，我们默认取第一个源的 detailUrl 进行跳转。
                                // （后期如果你想做“换源”功能，可以在 DetailScreen 里把所有的 sources 传过去提供下拉框选择）
                                viewModel.processIntent(SearchUiIntent.BookClicked(book))
                            },
                            onAddBookshelfClick = {
                                // 统一发送意图到 ViewModel，把整本 SearchBook 传过去
                                viewModel.processIntent(SearchUiIntent.AddToBookshelf(book.toDomain()))
                            }
                        )
                    }
                }
            } else if (uiState.keyword.isNotBlank()) {
                // 搜索结果为空的占位图
                Text(
                    text = "暂无数据",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}