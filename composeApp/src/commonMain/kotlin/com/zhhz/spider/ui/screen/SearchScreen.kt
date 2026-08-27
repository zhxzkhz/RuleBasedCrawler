package com.zhhz.spider.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.network.toDomain
import com.zhhz.spider.ui.widget.SearchResultItem
import com.zhhz.spider.ui.widget.SearchRuleSelector
import com.zhhz.spider.ui.widget.SearchTopBar
import com.zhhz.spider.viewModel.SearchUiEffect
import com.zhhz.spider.viewModel.SearchUiIntent
import com.zhhz.spider.viewModel.SearchViewModel
import kotlinx.coroutines.launch

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    )
            ) {
                // 将 TopBar 的输入和点击事件，转化为 Intent 发送给 ViewModel
                SearchTopBar(
                    keyword = uiState.keyword,
                    placeholder = if (uiState.isIdSearch) "输入书籍 ID" else "搜索书名、作者...",
                    onKeywordChange = {
                        viewModel.processIntent(SearchUiIntent.UpdateKeyword(it))
                    },
                    onSearchClick = {
                        viewModel.processIntent(SearchUiIntent.ExecuteSearch)
                    },
                    onBackClick = onNavigateBack
                )

                SearchRuleSelector(
                    rules = uiState.availableRules,
                    selectedRuleId = uiState.selectedRuleId,
                    isPreciseSearch = uiState.isPreciseSearch,
                    isIdSearch = uiState.isIdSearch,
                    onRuleSelected = {
                        viewModel.processIntent(SearchUiIntent.SelectRule(it))
                    },
                    onPreciseSearchChange = {
                        viewModel.processIntent(SearchUiIntent.SetPreciseSearch(it))
                    },
                    onIdSearchChange = {
                        viewModel.processIntent(SearchUiIntent.SetIdSearch(it))
                    }
                )

                // 💡 搜索历史：空关键词时展示，点击直接重搜
                if (uiState.searchHistory.isNotEmpty() && uiState.keyword.isBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "搜索历史",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.processIntent(SearchUiIntent.ClearSearchHistory) },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("清除", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.searchHistory, key = { it }) { keyword ->
                            AssistChip(
                                onClick = {
                                    viewModel.processIntent(SearchUiIntent.UpdateKeyword(keyword))
                                    viewModel.processIntent(SearchUiIntent.ExecuteSearch)
                                },
                                label = { Text(keyword, maxLines = 1) }
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = uiState.isSearchOngoing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
        ) {
            // 根据状态渲染不同 UI 逻辑统一
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.searchResults.isNotEmpty()) {
                val listState = rememberLazyListState()

                // 触底自动翻页：距底部不足 3 项时触发 LoadMore（ViewModel 内部有防重入守卫）
                LaunchedEffect(listState, uiState.searchResults.size) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
                    }.collect { (lastVisibleIndex, totalItemsCount) ->
                        if (totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 3) {
                            viewModel.processIntent(SearchUiIntent.LoadMore)
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.searchResults, key = { "${it.title}|${it.author}" }) { book -> // book 此时是 SearchBook
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

                    // 底部加载更多指示器
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                uiState.isLoadMore -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                !uiState.hasMore -> Text(
                                    "没有更多了",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else if (uiState.hasSearched) {
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
