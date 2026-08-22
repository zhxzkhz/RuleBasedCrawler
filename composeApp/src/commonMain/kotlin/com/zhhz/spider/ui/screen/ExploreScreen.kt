package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.network.Book
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.viewModel.ExploreUiEffect
import com.zhhz.spider.viewModel.ExploreUiIntent
import com.zhhz.spider.viewModel.ExploreViewModel
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (DetailRoute) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var ruleMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ExploreUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    val selectedRule = uiState.rules.find { it.id == uiState.selectedRuleId }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("榜单", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(Res.drawable.arrow_back_24px), "返回")
                    }
                },
                actions = {
                    // 书源切换器：只在配置了发现规则的书源之间切换
                    Box {
                        TextButton(onClick = { if (uiState.rules.isNotEmpty()) ruleMenuOpen = true }) {
                            Text(
                                text = selectedRule?.name?.ifBlank { "未命名书源" } ?: "选择书源",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 150.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = ruleMenuOpen,
                            onDismissRequest = { ruleMenuOpen = false }
                        ) {
                            uiState.rules.forEach { rule ->
                                DropdownMenuItem(
                                    text = { Text(rule.name.ifBlank { "未命名书源" }) },
                                    onClick = {
                                        ruleMenuOpen = false
                                        viewModel.processIntent(ExploreUiIntent.SelectRule(rule.id))
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 分类/榜单标签行
            if (uiState.tabs.isNotEmpty()) {
                SecondaryScrollableTabRow(
                    selectedTabIndex = uiState.tabs.indexOfFirst { it.url == uiState.selectedTabUrl }
                        .coerceAtLeast(0),
                    edgePadding = 16.dp
                ) {
                    uiState.tabs.forEach { tab ->
                        Tab(
                            selected = tab.url == uiState.selectedTabUrl,
                            onClick = { viewModel.processIntent(ExploreUiIntent.SelectTab(tab)) },
                            text = {
                                Text(
                                    tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoadingRules || uiState.isLoadingTabs -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    !uiState.isLoadingBooks && uiState.books.isEmpty() -> {
                        Text(
                            text = when {
                                uiState.rules.isEmpty() -> "没有已启用且配置了发现/榜单规则的书源"
                                else -> "该分类暂无内容，试试其他分类或刷新书源规则"
                            },
                            color = Color.Gray,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp)
                        )
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 105.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(uiState.books, key = { it.url }) { book ->
                                ExploreBookCard(book = book, onClick = {
                                    onNavigateToDetail(DetailRoute(
                                        title = book.title,
                                        author = book.author,
                                        cover = book.cover,
                                        detailUrl = book.url,
                                        ruleId = book.ruleId
                                    ))
                                })
                            }
                        }
                    }
                }

                // 榜单加载中：保留旧列表避免闪烁，叠加小进度条
                if (uiState.isLoadingBooks && uiState.books.isEmpty() && !uiState.isLoadingRules && !uiState.isLoadingTabs) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreBookCard(book: Book, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(6.dp),
            elevation = CardDefaults.cardElevation(3.dp),
            modifier = Modifier.aspectRatio(0.72f).fillMaxWidth(),
            onClick = onClick
        ) {
            BookCover(url = book.cover)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            maxLines = 2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            overflow = TextOverflow.Ellipsis
        )
        if (book.author.isNotBlank()) {
            Text(
                text = book.author,
                fontSize = 10.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
