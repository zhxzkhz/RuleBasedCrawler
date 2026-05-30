package com.zhhz.spider.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.ReaderRoute
import com.zhhz.spider.model.DownloadStatus
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.network.Book
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.viewModel.BookshelfUiEffect
import com.zhhz.spider.viewModel.BookshelfUiIntent
import com.zhhz.spider.viewModel.BookshelfUiState
import com.zhhz.spider.viewModel.BookshelfViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*


@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onGoToSearch: () -> Unit,
    onNavigateToReader: (ReaderRoute) -> Unit,
    onOpenRule: (Boolean) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportOptionDialog by remember { mutableStateOf(false) }

    // 副作用监听 (Toast 和 跳页)
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is BookshelfUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is BookshelfUiEffect.NavigateToReader -> {
                    onNavigateToReader(ReaderRoute(
                        bookUrl = effect.bookUrl,
                        chapterIndex = -1, // 传入 -1 让它自主查库
                        chapterTitle = "", // 书架进入时，具体章节标题可以暂时为空
                        ruleId = effect.ruleId
                    ))
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BookshelfTopBar(
                uiState = uiState,
                onToggleSelectionMode = { viewModel.processIntent(BookshelfUiIntent.ToggleSelectionMode) },
                onSelectAll = {
                    // 这里可以在 ViewModel 中加一个专门的意图，此处略写伪代码
                    viewModel.processIntent(BookshelfUiIntent.SelectAll)
                },
                onRefresh = { viewModel.processIntent(BookshelfUiIntent.RefreshBooks) },
                onGoToSearch = onGoToSearch,
                onOpenRule = onOpenRule,
            )
        },
        // 💡 多选模式下的底部操作栏
        bottomBar = {
            if (uiState.isSelectionMode && uiState.selectedBooks.isNotEmpty()) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant, // 💡 给个底色方便看清
                    tonalElevation = 8.dp // 增加阴影，使其浮在内容上方
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(
                            onClick = { viewModel.processIntent(BookshelfUiIntent.DeleteSelectedBooks) }
                        ) {
                            Icon(painterResource(Res.drawable.download_24px), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("删除选中 (${uiState.selectedBooks.size})", color = MaterialTheme.colorScheme.error)
                        }

                        // 后续如果要加下载功能：

                        TextButton(onClick = {
                            viewModel.processIntent(BookshelfUiIntent.DownloadSelectedBooks)
                        }) {
                            Icon(painterResource(Res.drawable.download_24px), contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("批量下载")
                        }

                        // 核心新增：批量导出为 ZIP 按钮！
                        TextButton(
                            onClick = { showExportOptionDialog = true } // 👈 发送导出意图
                        ) {
                            Icon(painterResource(Res.drawable.share_24px), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导出ZIP", color = MaterialTheme.colorScheme.primary)
                        }

                    }
                }
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(onClick = onGoToSearch) {
                    Icon(painterResource(Res.drawable.add_24px), "找书")
                }
            }
        }
    ) { padding ->
        // 💡 书架内容区域
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (uiState.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (uiState.books.isEmpty()) {
                EmptyBookshelfPlaceholder() // 原有的空书架提示
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 105.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.books) { book ->
                        BookItemCard(
                            book = book,
                            // 💡 极速取出这本书当前的后台下载任务
                            downloadTask = uiState.downloadTasks[book.url],
                            isSelectionMode = uiState.isSelectionMode,
                            isSelected = uiState.selectedBooks.contains(book.url),
                            onClick = {
                                viewModel.processIntent(BookshelfUiIntent.BookClicked(book))
                            },
                            onLongClick = {
                                // 💡 长按进入管理模式，并立刻选中当前书本
                                if (!uiState.isSelectionMode) {
                                    viewModel.processIntent(BookshelfUiIntent.ToggleSelectionMode)
                                    viewModel.processIntent(BookshelfUiIntent.ToggleSelectBook(book.url))
                                }
                            },
                            onToggleDownload = {
                                val task = uiState.downloadTasks[book.url]
                                if (task == null || task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.ERROR) {
                                    viewModel.processIntent(BookshelfUiIntent.StartDownload(book.url, book.ruleId, book.title))
                                } else if (task.status == DownloadStatus.DOWNLOADING) {
                                    viewModel.processIntent(BookshelfUiIntent.PauseDownload(book.url))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

// 💡 书架页末尾的选项弹窗
    if (showExportOptionDialog) {
        ExportOptionDialog(viewModel = viewModel){
            showExportOptionDialog = false
        }
    }

    // 💡 进度条大弹窗
    if (uiState.isExporting) {
        ExportAlertDialog(uiState, viewModel)
    }

}

@Composable
private fun ExportAlertDialog(
    uiState: BookshelfUiState,
    viewModel: BookshelfViewModel
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("正在导出书籍...", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "正在打包：《${uiState.exportBookTitle}》",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                LinearProgressIndicator(
                    progress = { uiState.exportProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )

                Text(uiState.exportChapterProgress, style = MaterialTheme.typography.bodySmall)
            }
        },
        // 💡 核心新增：在确认按钮位，放一个“取消导出”按钮！
        confirmButton = {
            TextButton(
                onClick = {
                    // 发送取消意图，ViewModel 掐断协程，底层删除半成品 ZIP，完美闭环！
                    viewModel.processIntent(BookshelfUiIntent.CancelExport)
                }
            ) {
                Text("取消导出", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun ExportOptionDialog(
    viewModel: BookshelfViewModel,
    hideExportOptionDialog: () -> Unit
) {
    // 本地临时状态，用于记录用户选中的配置
    var concurrencyLimit by remember { mutableStateOf(4) }
    var delayMs by remember { mutableStateOf(0L) }

    AlertDialog(
        onDismissRequest = { hideExportOptionDialog() },
        title = { Text("导出选项配置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("请选择导出的内容范围和限制参数，合理的配置能有效防止 IP 被书源网站封锁。")

                // 1. 并发线程配置
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("并发下载线程数：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 8).forEach { thread ->
                            FilterChip(
                                selected = concurrencyLimit == thread,
                                onClick = { concurrencyLimit = thread },
                                label = { Text("$thread 线程") }
                            )
                        }
                    }
                }

                // 2. 请求延迟配置
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("每张图片下载延迟：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0L to "无延迟", 200L to "200ms", 500L to "500ms").forEach { (ms, text) ->
                            FilterChip(
                                selected = delayMs == ms,
                                onClick = { delayMs = ms },
                                label = { Text(text) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    hideExportOptionDialog()
                    // 💡 全本下载导出：传入限流参数！
                    viewModel.processIntent(BookshelfUiIntent.ExportSelectedBooks(
                        onlyCached = false,
                        concurrencyLimit = concurrencyLimit,
                        delayMs = delayMs
                    ))
                }
            ) {
                Text("导出所有章节")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    hideExportOptionDialog()
                    // 💡 仅缓存导出：由于不走网络，并发数和延迟直接给默认值即可
                    viewModel.processIntent(BookshelfUiIntent.ExportSelectedBooks(
                        onlyCached = true,
                        concurrencyLimit = 4,
                        delayMs = 0L
                    ))
                }
            ) {
                Text("仅导出已缓存章节")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookshelfTopBar(
    uiState: BookshelfUiState,
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit, // 全选/取消全选
    onRefresh: () -> Unit,
    onGoToSearch: () -> Unit,
    onOpenRule: (Boolean) -> Unit
) {
    if (uiState.isSelectionMode) {
        // 💡 模式 B：多选管理模式
        TopAppBar(
            title = { Text("已选择 ${uiState.selectedBooks.size} 本书") },
            navigationIcon = {
                IconButton(onClick = onToggleSelectionMode) { Icon(painterResource(Res.drawable.close_24px), "退出管理") }
            },
            actions = {
                val isAllSelected = uiState.selectedBooks.size == uiState.books.size && uiState.books.isNotEmpty()
                TextButton(onClick = onSelectAll) { Text(if (isAllSelected) "取消全选" else "全选") }
            }
        )
    } else {
        // 💡 模式 A：正常浏览模式
        TopAppBar(
            title = { Text("我的书架", fontWeight = FontWeight.ExtraBold) },
            actions = {
                IconButton(onClick = {
                    onOpenRule(true)
                }) { Icon(painterResource(Res.drawable.code_blocks_24px), "编辑规则") }
                IconButton(onClick = onRefresh) { Icon(painterResource(Res.drawable.refresh_24px), "检测更新") }
                IconButton(onClick = onGoToSearch) { Icon(painterResource(Res.drawable.search_24px), "搜索新书") }
            }
        )
    }
}

@Composable
fun BookItemCard(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showMenu = true }
                )
            }
    ) {
        // 封面图（带优雅圆角和阴影）
        Card(
            shape = RoundedCornerShape(6.dp),
            elevation = CardDefaults.cardElevation(3.dp),
            modifier = Modifier.aspectRatio(0.72f).fillMaxWidth()
        ) {
            BookCover(url = book.cover)
        }

        Spacer(Modifier.height(8.dp))

        // 书名
        Text(
            text = book.title,
            maxLines = 2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            overflow = TextOverflow.Ellipsis
        )

        // 阅读进度
        Text(
            text = book.lastReadChapterTitle,
            fontSize = 10.sp,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 长按弹出的管理菜单
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("从书架删除", color = Color.Red) },
                onClick = {
                    onDelete()
                    showMenu = false
                },
                leadingIcon = { Icon(painterResource(Res.drawable.close_24px), null, tint = Color.Red) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookItemCard(
    book: Book,
    downloadTask: DownloadTask?, // 💡 注入这本书对应的下载任务
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleDownload: () -> Unit // 开始/暂停下载
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick // 长按触发管理
            )
    ) {
        Column {
            Box {
                // 1. 书籍封面
                BookCover(url = book.cover, modifier = Modifier.fillMaxWidth().aspectRatio(0.7f))

                // 2. 💡 离线下载蒙层与进度条
                if (downloadTask != null) {
                    Box(
                        modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        // 居中显示下载状态按钮 (下载中 / 已暂停 / 出错)
                        IconButton(
                            onClick = onToggleDownload,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            val icon = when (downloadTask.status) {
                                DownloadStatus.DOWNLOADING -> painterResource(Res.drawable.pause_24px) // 正在下，点它就是暂停
                                DownloadStatus.PAUSED, DownloadStatus.ERROR -> painterResource(Res.drawable.play_arrow_24px) // 暂停了，点它就是继续
                                DownloadStatus.COMPLETED -> painterResource(Res.drawable.check_24px)
                                else -> painterResource(Res.drawable.cloud_download_24px)
                            }
                            Icon(icon, contentDescription = null, tint = Color.White)
                        }

                        // 底部显示进度条
                        if (downloadTask.status == DownloadStatus.DOWNLOADING || downloadTask.status == DownloadStatus.PAUSED) {
                            LinearProgressIndicator(
                                progress = { downloadTask.progress },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                // 3. 多选模式下的勾选框
                if (isSelectionMode) {
                    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null, // 点击事件由外层的 Card 统一处理
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    )
                }
            }

            // 4. 书名与阅读进度
            Text(
                text = book.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            // (可选) 显示有没有未读新章节小红点
        }
    }
}

@Composable
fun EmptyBookshelfPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center // 居中显示
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1. 图标：使用内置的书库图标
            Icon(
                painter = painterResource(Res.drawable.auto_stories_24px), // 需要导入 material-icons-extended
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.LightGray // 使用淡灰色，不抢眼
            )

            Spacer(Modifier.height(16.dp))

            // 2. 主提示文字
            Text(
                text = "书架空空如也",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )

            Spacer(Modifier.height(8.dp))

            // 3. 引导说明
            Text(
                text = "通过【规则测试】抓取书籍后，点击“加入书架”即可在这里看到它们。",
                fontSize = 13.sp,
                color = Color.Gray.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}