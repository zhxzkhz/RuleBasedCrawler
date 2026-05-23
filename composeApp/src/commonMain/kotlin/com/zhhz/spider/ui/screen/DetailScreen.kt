package com.zhhz.spider.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.BookDetail
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.ui.widget.ChapterListItem
import com.zhhz.spider.ui.widget.DetailLoadingPlaceholder
import com.zhhz.spider.viewModel.DetailUiState
import com.zhhz.spider.viewModel.DetailViewModel
import com.zhhz.spider.viewModel.Resource
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24dp
import rulebasedcrawler.composeapp.generated.resources.bookmark_add_24dp
import rulebasedcrawler.composeapp.generated.resources.bookmark_added_24dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onChapterClick: (Book) -> Unit
) {

    val isSyncing by viewModel.isSyncing.collectAsState()
    val currentBook by viewModel.bookInLibrary.collectAsState()
    val detailState by viewModel.detailState.collectAsState()
    val catalogState by viewModel.uiCatalogState.collectAsState()

    // 使用 Scaffold 提供标准的顶部导航和全局背景色
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书籍详情", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back_24dp), null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF7F7F7) // 全局浅灰底色，让白色卡片更突出
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // --- 1. 信息区 (白色卡片承载) ---
            item {
                Surface(
                    color = Color.White,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (val state = detailState) {
                        is DetailUiState.Loading -> DetailLoadingPlaceholder()
                        is DetailUiState.Error -> RetryView(state.message) { viewModel.loadBookDetail() }
                        is DetailUiState.Success -> {
                            BookHeaderSection(
                                detail = state.detail,
                                // 逻辑修正：这里应该是“是否在书架”
                                isRead = currentBook != null,
                                onAddClick = { viewModel.addToBookshelf(state.detail) }
                            )
                        }
                    }
                }
            }

            // --- 2. 简介区 (如果有简介规则) ---
            if (detailState is DetailUiState.Success) {
                val detail = (detailState as DetailUiState.Success).detail
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("简介", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = detail.desc.ifBlank { "暂无简介内容" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            // --- 3. 目录标题区 (使用 stickyHeader 悬停效果更专业) ---
            stickyHeader {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFF7F7F7)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("章节列表", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
            }

            when (val state = catalogState) {
                is Resource.Loading -> {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                is Resource.Error -> {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("目录加载失败: ${state.message}", color = Color.Red)
                            TextButton(onClick = { viewModel.loadCatalog() }) { Text("重试") }
                        }
                    }
                }
                is Resource.Success -> {
                    // 渲染列表
                    itemsIndexed(state.data) { index, chapter ->
                        ChapterListItem(
                            chapter = chapter,
                            // 直接使用 currentBook 的信息对比
                            isRead = (chapter.index == index),
                            onClick = {
                                // 传入当前阅读上下文
                                onChapterClick(viewModel.getBook().copy(lastReadChapterIndex = index))
                            }
                        )
                    }
                }
            }

            // 底部留白
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun RetryView(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color.Red, fontSize = 14.sp)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("重新加载信息") }
        }
    }
}

@Composable
fun BookHeaderSection(detail: BookDetail, isRead: Boolean, onAddClick: () -> Unit) {
    Row(Modifier.padding(16.dp).fillMaxWidth()) {
        Card(Modifier.size(100.dp, 140.dp), elevation = CardDefaults.cardElevation(4.dp)) {
            BookCover(url = detail.cover)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(detail.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(detail.author, color = Color.Gray, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Button(onClick = onAddClick) {
                if (isRead) {
                    Icon(painterResource(Res.drawable.bookmark_added_24dp), null)
                    Text(" 移除书架")
                } else {
                    Icon(painterResource(Res.drawable.bookmark_add_24dp), null)
                    Text(" 加入书架")
                }
            }
        }
    }
}