package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.ReaderRoute
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.ui.widget.ChapterListItem
import com.zhhz.spider.viewModel.DetailUiEffect
import com.zhhz.spider.viewModel.DetailUiIntent
import com.zhhz.spider.viewModel.DetailViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px
import rulebasedcrawler.composeapp.generated.resources.check_24px

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    detailUrl: String,
    ruleId: String,
    viewModel: DetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToReader: (ReaderRoute) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 💡 新增：用于控制“换源对话框”显示的本地状态
    var showSourceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(detailUrl, ruleId) {
        viewModel.processIntent(DetailUiIntent.LoadDetail(detailUrl, ruleId))
    }

    // 副作用监听
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is DetailUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is DetailUiEffect.NavigateToReader -> {
                    // 跳转到阅读器
                    onNavigateToReader(effect.route)
                }
                is DetailUiEffect.NavigateBack -> {
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("书籍详情", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.processIntent(DetailUiIntent.NavigateBack) }) {
                        Icon(painterResource(Res.drawable.arrow_back_24px), "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { padding ->
        // 💡 整体使用一个干净的 Column 容器
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f) // 占满剩余高度
                    .fillMaxWidth(),
                contentPadding = PaddingValues(20.dp)
            ) {
                // 1. 书籍基本信息头部
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        BookCover(
                            url = uiState.cover,
                            modifier = Modifier
                                .width(110.dp)
                                .height(150.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        Column(
                            modifier = Modifier.height(150.dp),
                            verticalArrangement = Arrangement.SpaceBetween // 均匀分布
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = uiState.title.ifBlank { "正在获取书名..." },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "作者：${uiState.author.ifBlank { "未知" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "状态：${uiState.status.ifBlank { "未知" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                            }

                            // 展示最新章节
                            if (uiState.latestChapterTitle.isNotBlank()) {
                                Text(
                                    text = "最新：${uiState.latestChapterTitle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }


                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        // 点击直接拉起换源对话框
                        onClick = { showSourceDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "当前书源",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = uiState.currentSource?.sourceName ?: "默认书源",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // 右侧气泡提示
                            Surface(
                                shape = RoundedCornerShape(100.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "共 ${uiState.availableSources.size} 个源 〉",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 2. 💡 黄金交互：并排的双按钮操作组（美观且标准）
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 开始阅读（主按钮）
                        Button(
                            onClick = { viewModel.processIntent(DetailUiIntent.GoToReader) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("开始阅读", fontWeight = FontWeight.Bold)
                        }

                        // 加入/移出书架（辅助按钮）
                        OutlinedButton(
                            onClick = { viewModel.processIntent(DetailUiIntent.ToggleBookshelf) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (uiState.isInBookshelf) "移出书架" else "加入书架",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

                // 3. 简介区域
                item {
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Text(
                        text = uiState.desc.ifBlank { "暂无简介信息" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

                // 4. 目录标题
                item {
                    Text(
                        text = "目录 (${uiState.chapters.size} 章)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

                // 5. 目录列表加载状态与渲染
                if (uiState.isCatalogLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    items(uiState.chapters) { chapter ->
                        ChapterListItem(
                            chapter = chapter,
                            onClick = { viewModel.processIntent(DetailUiIntent.ChapterClicked(chapter)) }
                        )
                    }
                }
            }
        }
    }

    if (showSourceDialog && uiState.availableSources.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = {
                Text(
                    text = "切换书源",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                // 限制对话框最大高度，防止书源过多时撑爆屏幕
                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    LazyColumn {
                        items(uiState.availableSources) { source ->
                            val isCurrent = source.ruleId == uiState.currentSource?.ruleId &&
                                    source.url == uiState.currentSource?.url

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        // 💡 发送换源意图给 ViewModel！
                                        viewModel.processIntent(DetailUiIntent.ChangeSource(source))
                                        showSourceDialog = false // 关闭弹窗
                                    }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = source.sourceName,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 16.sp
                                )
                                if (isCurrent) {
                                    Icon(
                                        painter = painterResource(Res.drawable.check_24px),
                                        contentDescription = "当前源",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

}