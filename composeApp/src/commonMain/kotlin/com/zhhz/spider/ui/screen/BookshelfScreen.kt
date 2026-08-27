package com.zhhz.spider.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.db.BookGroupEntity
import com.zhhz.spider.rememberBookExportDirectoryAction
import com.zhhz.spider.viewModel.unreadChapterCount
import com.zhhz.spider.rememberRuleFileActions
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onGoToSearch: () -> Unit,
    onOpenExplore: () -> Unit,
    onNavigateToDetail: (DetailRoute) -> Unit,
    onOpenRuleManagement: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showExportOptionDialog by remember { mutableStateOf(false) }
    var showTaskCenter by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 💡 自定义分组弹窗状态：管理分组 / 为哪些书设置分组（null=关闭）
    var showManageGroups by remember { mutableStateOf(false) }
    var assignGroupTarget by remember { mutableStateOf<List<String>?>(null) }

    // 💡 阅读历史弹层
    var showHistory by remember { mutableStateOf(false) }

    // 💡 导出书架选项弹窗（自选是否含阅读历史/进度）
    var showShelfExportDialog by remember { mutableStateOf(false) }

    // 💡 导入书架选项弹窗（分组策略：合并 / 移入现有分组）
    var showImportOptionsDialog by remember { mutableStateOf(false) }
    var pendingOnlyCached by remember { mutableStateOf(false) }
    var pendingConcurrencyLimit by remember { mutableIntStateOf(4) }
    var pendingDelayMs by remember { mutableLongStateOf(0L) }
    val exportDirectoryAction = rememberBookExportDirectoryAction { result ->
        result.onSuccess { directory ->
            viewModel.processIntent(
                BookshelfUiIntent.ExportSelectedBooks(
                    destinationDirectory = directory,
                    onlyCached = pendingOnlyCached,
                    concurrencyLimit = pendingConcurrencyLimit,
                    delayMs = pendingDelayMs
                )
            )
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("选择导出目录失败: ${it.message}") }
        }
    }

    // 书架备份 JSON 的导入/导出（复用规则文件的系统文件选择器）
    var pendingImportGroupMode by remember { mutableStateOf(com.zhhz.spider.viewModel.BookGroupImportMode.MERGE) }
    var pendingImportTargetGroupId by remember { mutableStateOf<Long?>(null) }
    val ruleFileActions = rememberRuleFileActions(
        onOpenResult = { result ->
            result.onSuccess { json ->
                viewModel.processIntent(
                    BookshelfUiIntent.ImportBooks(
                        jsonContent = json,
                        groupMode = pendingImportGroupMode,
                        targetGroupId = pendingImportTargetGroupId
                    )
                )
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("读取书架备份失败: ${it.message}") }
            }
        },
        onSaveResult = { result ->
            result.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("书架备份导出成功") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("保存书架备份失败: ${it.message}") }
            }
        }
    )

    // 副作用监听 (Toast 和 跳页)
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is BookshelfUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is BookshelfUiEffect.NavigateToDetail -> {
                    onNavigateToDetail(DetailRoute(
                        title = effect.book.title,
                        author = effect.book.author,
                        cover = effect.book.cover,
                        detailUrl = effect.book.url,
                        ruleId = effect.book.ruleId
                    ))
                }
                is BookshelfUiEffect.SaveBookshelfBackup -> {
                    ruleFileActions.saveJsonFile(effect.fileName, effect.content)
                }
            }
        }
    }

    // 💡 追更检测：进入书架时节流自动刷新（12 小时内不重复检查）
    LaunchedEffect(uiState.books.isNotEmpty()) {
        val hasBooks = uiState.books.any { it.inLibrary }
        if (!hasBooks || uiState.isRefreshing) return@LaunchedEffect
        val last = viewModel.loadDownloadSettings().lastUpdateCheckAt
        val stale = System.currentTimeMillis() - last > 12 * 60 * 60 * 1000L
        if (stale) {
            viewModel.processIntent(BookshelfUiIntent.RefreshBooks)
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
                onOpenExplore = onOpenExplore,
                onOpenTaskCenter = { showTaskCenter = true },
                onOpenSettings = { showSettings = true },
                onOpenRuleManagement = onOpenRuleManagement,
                onImportBookshelf = { showImportOptionsDialog = true },
                onOpenHistory = { showHistory = true },
            )
        },
        // 💡 多选模式下的底部操作栏
        bottomBar = {
            if (uiState.isSelectionMode && uiState.selectedBooks.isNotEmpty()) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant, // 💡 给个底色方便看清
                    tonalElevation = 8.dp // 增加阴影，使其浮在内容上方
                ) {
                    // 💡 操作项较多（删除/下载/导出ZIP/导出书架），窄屏下可横向滑动避免显示不全
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.processIntent(BookshelfUiIntent.DeleteSelectedBooks) }
                        ) {
                            Icon(painterResource(Res.drawable.close_24px), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("移出书架 (${uiState.selectedBooks.size})", color = MaterialTheme.colorScheme.error)
                        }

                        TextButton(onClick = {
                            viewModel.processIntent(BookshelfUiIntent.DownloadSelectedBooks)
                        }) {
                            Icon(painterResource(Res.drawable.download_24px), contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("批量下载")
                        }

                        TextButton(
                            onClick = { assignGroupTarget = uiState.selectedBooks.toList() }
                        ) {
                            Icon(painterResource(Res.drawable.bookmark_add_24px), contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("分组")
                        }

                        TextButton(
                            onClick = { showExportOptionDialog = true }
                        ) {
                            Icon(painterResource(Res.drawable.share_24px), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导出ZIP", color = MaterialTheme.colorScheme.primary)
                        }

                        TextButton(
                            onClick = { showShelfExportDialog = true }
                        ) {
                            Icon(painterResource(Res.drawable.save_24px), contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导出书架", color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                // 悬浮按钮也带悬停提示
                val fabTooltipState = rememberTooltipState()
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("找书") } },
                    state = fabTooltipState
                ) {
                    FloatingActionButton(onClick = onGoToSearch) {
                        Icon(painterResource(Res.drawable.add_24px), "找书")
                    }
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
                Column(modifier = Modifier.fillMaxSize()) {

                    // 💡 自定义分组筛选行（位掩码多选归属；末尾"+"进入管理）
                    if (!uiState.isSelectionMode) {
                        val groupRowState = rememberLazyListState()
                        LazyRow(
                            state = groupRowState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(Modifier.desktopDragScroll(groupRowState)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = "group_all") {
                                FilterChip(
                                    selected = uiState.selectedGroupMask == 0L,
                                    onClick = { viewModel.processIntent(BookshelfUiIntent.SelectGroup(0L)) },
                                    label = { Text("全部") }
                                )
                            }
                            items(uiState.groups, key = { it.id }) { group ->
                                FilterChip(
                                    selected = uiState.selectedGroupMask == group.mask,
                                    onClick = {
                                        viewModel.processIntent(
                                            BookshelfUiIntent.SelectGroup(
                                                if (uiState.selectedGroupMask == group.mask) 0L else group.mask
                                            )
                                        )
                                    },
                                    label = { Text(group.name, maxLines = 1) }
                                )
                            }
                            item(key = "group_manage") {
                                FilterChip(
                                    selected = false,
                                    onClick = { showManageGroups = true },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painterResource(Res.drawable.add_24px),
                                                contentDescription = "管理分组",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text("分组")
                                        }
                                    }
                                )
                            }

                            // 💡 有更新筛选（配合未读红点的追更闭环）
                            item(key = "only_updated") {
                                val updatedCount = uiState.books.count { it.inLibrary && it.unreadChapterCount() > 0 }
                                FilterChip(
                                    selected = uiState.onlyUpdated,
                                    enabled = updatedCount > 0 || uiState.onlyUpdated,
                                    onClick = {
                                        viewModel.processIntent(BookshelfUiIntent.ToggleOnlyUpdated)
                                    },
                                    label = {
                                        Text(
                                            if (updatedCount > 0) "有更新 · $updatedCount" else "有更新",
                                            maxLines = 1
                                        )
                                    }
                                )
                            }

                            // 💡 进度筛选
                            item(key = "read_filter") {
                                var readFilterMenuOpen by remember { mutableStateOf(false) }
                                Box {
                                    FilterChip(
                                        selected = uiState.readFilter != "all",
                                        onClick = { readFilterMenuOpen = true },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(readFilterLabel(uiState.readFilter), maxLines = 1)
                                            }
                                        }
                                    )
                                    DropdownMenu(
                                        expanded = readFilterMenuOpen,
                                        onDismissRequest = { readFilterMenuOpen = false }
                                    ) {
                                        listOf("all" to "全部进度", "unread" to "尚未阅读", "reading" to "正在阅读").forEach { (value, label) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        label + if (uiState.readFilter == value) " ✓" else "",
                                                        fontWeight = if (uiState.readFilter == value) FontWeight.SemiBold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    readFilterMenuOpen = false
                                                    viewModel.processIntent(BookshelfUiIntent.SetReadFilter(value))
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 💡 排序与视图模式入口（偏好持久化）
                            item(key = "shelf_sort") {
                                var sortMenuOpen by remember { mutableStateOf(false) }
                                Box {
                                    FilterChip(
                                        selected = false,
                                        onClick = { sortMenuOpen = true },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    painterResource(if (uiState.viewMode == "list") Res.drawable.view_list_24px else Res.drawable.view_module_24px),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("⇅ ${shelfSortLabel(uiState.sortMode)}", maxLines = 1)
                                            }
                                        }
                                    )
                                    DropdownMenu(
                                        expanded = sortMenuOpen,
                                        onDismissRequest = { sortMenuOpen = false }
                                    ) {
                                        Text(
                                            "排序",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        listOf("recent" to "最近阅读", "created" to "加入时间", "title" to "书名").forEach { (value, label) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        label + if (uiState.sortMode == value) " ✓" else "",
                                                        fontWeight = if (uiState.sortMode == value) FontWeight.SemiBold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    sortMenuOpen = false
                                                    viewModel.processIntent(BookshelfUiIntent.SetShelfSort(value))
                                                }
                                            )
                                        }
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                        Text(
                                            "视图",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        DropdownMenuItem(
                                            text = { Text("网格模式" + if (uiState.viewMode == "grid") " ✓" else "") },
                                            leadingIcon = { Icon(painterResource(Res.drawable.view_module_24px), null) },
                                            onClick = {
                                                sortMenuOpen = false
                                                viewModel.processIntent(BookshelfUiIntent.SetShelfViewMode("grid"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("列表模式" + if (uiState.viewMode == "list") " ✓" else "") },
                                            leadingIcon = { Icon(painterResource(Res.drawable.view_list_24px), null) },
                                            onClick = {
                                                sortMenuOpen = false
                                                viewModel.processIntent(BookshelfUiIntent.SetShelfViewMode("list"))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val displayBooks = uiState.filteredBooks

                    if (displayBooks.isEmpty()) {
                        Text(
                            if (uiState.selectedGroupMask != 0L) "该分组下暂无书籍" else "该分类下暂无书籍",
                            fontSize = 13.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp)
                        )
                    } else if (uiState.viewMode == "list") {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(displayBooks, key = { it.url }) { book ->
                                BookItemRow(
                                    book = book,
                                    downloadTask = uiState.downloadTasks[book.url],
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = uiState.selectedBooks.contains(book.url),
                                    onClick = { viewModel.processIntent(BookshelfUiIntent.BookClicked(book)) },
                                    onLongClick = {
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
                                    },
                                    onAssignGroups = { assignGroupTarget = listOf(book.url) }
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 105.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(displayBooks, key = { it.url }) { book ->
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
                            onAssignGroups = { assignGroupTarget = listOf(book.url) },
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
        }
     }

// 💡 书架页末尾的选项弹窗
    if (showExportOptionDialog) {
        ExportOptionDialog(
            onExport = { onlyCached, concurrencyLimit, delayMs ->
                showExportOptionDialog = false
                pendingOnlyCached = onlyCached
                pendingConcurrencyLimit = concurrencyLimit
                pendingDelayMs = delayMs
                exportDirectoryAction.chooseDirectory()
            },
            onDismiss = { showExportOptionDialog = false }
        )
    }

    // 💡 进度条大弹窗
    if (uiState.isExporting) {
        ExportAlertDialog(uiState, viewModel)
    }

    // 💡 任务中心：批量下载 + 导出 的统一进度查看与管理
    if (showTaskCenter) {
        ModalBottomSheet(onDismissRequest = { showTaskCenter = false }) {
            TaskCenterContent(
                uiState = uiState,
                viewModel = viewModel,
                onDismiss = { showTaskCenter = false },
                onPauseDownload = { viewModel.processIntent(BookshelfUiIntent.PauseDownload(it)) },
                onResumeDownload = { task ->
                    val book = uiState.books.find { it.url == task.bookUrl }
                    viewModel.processIntent(
                        BookshelfUiIntent.StartDownload(
                            bookUrl = task.bookUrl,
                            ruleId = book?.ruleId ?: "",
                            bookTitle = task.bookTitle
                        )
                    )
                },
                onCancelDownload = { viewModel.processIntent(BookshelfUiIntent.CancelDownload(it)) },
                onCancelExport = { viewModel.processIntent(BookshelfUiIntent.CancelExport) }
            )
        }
    }

    // 💡 设置：下载并发等全局参数
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            DownloadSettingsContent(
                viewModel = viewModel,
                onDismiss = { showSettings = false }
            )
        }
    }

    // 💡 导入书架：先选分组策略，再打开文件选择器
    if (showImportOptionsDialog) {
        ImportOptionsDialog(
            groups = uiState.groups,
            onConfirm = { mode, targetGroupId ->
                showImportOptionsDialog = false
                pendingImportGroupMode = mode
                pendingImportTargetGroupId = targetGroupId
                ruleFileActions.openJsonFile()
            },
            onDismiss = { showImportOptionsDialog = false }
        )
    }

    // 💡 导入进行中：阻塞式进度弹窗（防止大备份看起来像卡死）
    if (uiState.isImporting) {
        AlertDialog(
            onDismissRequest = { /* 导入中不可关闭 */ },
            title = { Text("正在导入书架…", fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("正在解析备份并写入数据库", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }

    // 💡 导出书架选项弹窗
    if (showShelfExportDialog) {
        ShelfExportOptionsDialog(
            selectedCount = uiState.selectedBooks.size,
            onConfirm = { includeProgress ->
                showShelfExportDialog = false
                viewModel.processIntent(
                    BookshelfUiIntent.ExportSelectedAsJson(includeProgress)
                )
            },
            onDismiss = { showShelfExportDialog = false }
        )
    }

    // 💡 阅读历史弹层
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            HistoryContent(
                books = uiState.historyBooks,
                viewModel = viewModel,
                onBookClick = { book ->
                    showHistory = false
                    viewModel.processIntent(BookshelfUiIntent.BookClicked(book))
                }
            )
        }
    }
    // 💡 自定义分组管理弹窗（新建/重命名/删除）
    if (showManageGroups) {
        ManageGroupsDialog(
            groups = uiState.groups,
            onCreate = { name, isAutoUpdate -> viewModel.processIntent(BookshelfUiIntent.CreateGroup(name, isAutoUpdate)) },
            onUpdate = { id, name, isAutoUpdate -> viewModel.processIntent(BookshelfUiIntent.UpdateGroup(id, name, isAutoUpdate)) },
            onDelete = { viewModel.processIntent(BookshelfUiIntent.DeleteGroup(it)) },
            onDismiss = { showManageGroups = false }
        )
    }

    // 💡 为单本书 / 批量选中书籍设置分组归属
    assignGroupTarget?.let { targetUrls ->
        AssignGroupsDialog(
            targetUrls = targetUrls,
            books = uiState.books,
            groups = uiState.groups,
            onToggleAll = { checked ->
                // 合并掩码：一次写库完成全部分组的置位/清位
                val combinedMask = uiState.groups.fold(0L) { acc, g -> acc or g.mask }
                if (combinedMask != 0L) {
                    viewModel.processIntent(
                        BookshelfUiIntent.SetBooksInGroup(targetUrls, combinedMask, checked)
                    )
                }
            },
            onToggle = { mask, checked ->
                viewModel.processIntent(BookshelfUiIntent.SetBooksInGroup(targetUrls, mask, checked))
            },
            onDismiss = { assignGroupTarget = null }
        )
    }

}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.ERROR -> "失败"
    DownloadStatus.COMPLETED -> "已完成"
    else -> "等待中"
}

private fun shelfSortLabel(mode: String): String = when (mode) {
    "created" -> "加入时间"
    "title" -> "书名"
    else -> "最近阅读"
}

private fun readFilterLabel(filter: String): String = when (filter) {
    "unread" -> "尚未阅读"
    "reading" -> "正在阅读"
    else -> "全部进度"
}

@Composable
private fun DownloadSettingsContent(
    viewModel: BookshelfViewModel,
    onDismiss: () -> Unit
) {
    val bounds = com.zhhz.spider.settings.SettingsRepository.DOWNLOAD_BOOKS_RANGE
    val imageBounds = com.zhhz.spider.settings.SettingsRepository.DOWNLOAD_IMAGES_RANGE

    // 以当前生效值为初始值（从持久化设置读取，文件极小）
    val initial = remember { viewModel.loadDownloadSettings() }
    var books by remember { mutableStateOf(initial.downloadBooks) }
    var images by remember { mutableStateOf(initial.downloadImages) }
    
    // 💡 未分组书籍更新偏好（直接绑定 ViewModel 实时生效，不需要点保存按钮）
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Text("下载并发", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            "同时下载的书本数与全局图片数，保存后立即生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        StepperRow(
            label = "同时下载书本数",
            value = books,
            range = bounds,
            onChange = { books = it }
        )
        StepperRow(
            label = "同时下载图片数",
            value = images,
            range = imageBounds,
            onChange = { images = it }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.processIntent(BookshelfUiIntent.SaveDownloadSettings(books, images))
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存下载设置")
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        Text("追更检测", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable { 
                viewModel.processIntent(BookshelfUiIntent.SetUpdateUngroupedBooks(!uiState.updateUngroupedBooks))
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自动更新未分组的书籍", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = uiState.updateUngroupedBooks,
                onCheckedChange = { viewModel.processIntent(BookshelfUiIntent.SetUpdateUngroupedBooks(it)) }
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { if (value > range.first) onChange(value - 1) },
            enabled = value > range.first
        ) { Text("−", fontSize = 18.sp) }
        Text(
            "$value",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        TextButton(
            onClick = { if (value < range.last) onChange(value + 1) },
            enabled = value < range.last
        ) { Text("+", fontSize = 18.sp) }
    }
}

@Composable
private fun TaskCenterContent(
    uiState: BookshelfUiState,
    viewModel: BookshelfViewModel,
    onDismiss: () -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (DownloadTask) -> Unit,
    onCancelDownload: (String) -> Unit,
    onCancelExport: () -> Unit,
    onRetryFailed: (bookUrl: String) -> Unit = { viewModel.processIntent(BookshelfUiIntent.RetryFailedChapters(it)) }
) {
    val activeCount = uiState.downloadTasks.values.count {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
    } + if (uiState.isExporting) 1 else 0

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {

        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("任务中心", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.weight(1f))
            val hasUnfinished = uiState.downloadTasks.values.any { it.status != DownloadStatus.COMPLETED }
            if (hasUnfinished) {
                TextButton(onClick = { viewModel.processIntent(BookshelfUiIntent.ClearUnfinishedDownloads) }) {
                    Text("清除未完成", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            if (activeCount > 0) {
                Text("进行中 $activeCount", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // ── 导出任务 ──
        if (uiState.isExporting) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在导出", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancelExport) { Text("取消", color = MaterialTheme.colorScheme.error) }
                    }
                    Text(uiState.exportBookTitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { uiState.exportProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(uiState.exportChapterProgress, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── 下载任务列表 ──
        val tasks = uiState.downloadTasks.values
            .sortedWith(compareByDescending<DownloadTask> { it.status == DownloadStatus.DOWNLOADING }
                .thenByDescending { it.status == DownloadStatus.PENDING })

        if (tasks.isEmpty() && !uiState.isExporting) {
            Text(
                "暂无任务",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(tasks, key = { it.bookUrl }) { task ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    task.bookTitle,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    statusLabel(task.status),
                                    fontSize = 12.sp,
                                    color = when (task.status) {
                                        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                                        DownloadStatus.ERROR -> MaterialTheme.colorScheme.error
                                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            if (task.totalChapters > 0) {
                                LinearProgressIndicator(
                                    progress = { task.progress },
                                    modifier = Modifier.fillMaxWidth(),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${task.downloadedChapters}/${task.totalChapters} 章" + if (task.failedUrls.isNotEmpty()) "（失败 ${task.failedUrls.size}）" else "",
                                    fontSize = 11.sp,
                                    color = if (task.failedUrls.isNotEmpty() && task.status == DownloadStatus.COMPLETED)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                when (task.status) {
                                    DownloadStatus.DOWNLOADING -> TextButton(onClick = { onPauseDownload(task.bookUrl) }) {
                                        Text("暂停", fontSize = 12.sp)
                                    }
                                    DownloadStatus.PAUSED, DownloadStatus.ERROR ->
                                        TextButton(onClick = { onResumeDownload(task) }) {
                                            Text(if (task.status == DownloadStatus.ERROR) "重试" else "继续", fontSize = 12.sp)
                                        }
                                    DownloadStatus.COMPLETED -> {
                                        if (task.failedUrls.isNotEmpty()) {
                                            TextButton(
                                                onClick = { onRetryFailed(task.bookUrl) },
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("重试失败", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                                TextButton(onClick = { onCancelDownload(task.bookUrl) }) {
                                    Text("取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
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
    onExport: (onlyCached: Boolean, concurrencyLimit: Int, delayMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    // 本地临时状态，用于记录用户选中的配置
    var concurrencyLimit by remember { mutableStateOf(4) }
    var delayMs by remember { mutableStateOf(0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                    onExport(false, concurrencyLimit, delayMs)
                }
            ) {
                Text("导出所有章节")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    onExport(true, 4, 0L)
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
    onOpenExplore: () -> Unit,
    onOpenTaskCenter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRuleManagement: () -> Unit,
    onImportBookshelf: () -> Unit,
    onOpenHistory: () -> Unit = {}
) {
    Column {
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
            // 高频操作保留为图标（搜索/刷新/历史），低频操作收进「⋮」更多菜单；全部带悬停提示
            TopAppBar(
                title = {
                    Column {
                        Text("我的书架", fontWeight = FontWeight.ExtraBold)
                        if (uiState.isRefreshing) {
                            Text(
                                text = "正在更新 ${uiState.refreshCompletedCount}/${uiState.refreshTotalCount}：${uiState.refreshBookTitle}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    TooltippedIconButton("搜索新书", onClick = onGoToSearch) {
                        Icon(painterResource(Res.drawable.search_24px), null)
                    }
                    TooltippedIconButton(
                        tooltip = "更新章节目录",
                        enabled = !uiState.isRefreshing,
                        onClick = onRefresh
                    ) {
                        Icon(painterResource(Res.drawable.refresh_24px), null)
                    }
                    TooltippedIconButton("阅读历史", onClick = onOpenHistory) {
                        Icon(painterResource(Res.drawable.history_24px), null)
                    }

                    // ── 更多菜单 ──
                    var moreMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TooltippedIconButton(
                            tooltip = "更多",
                            onClick = { moreMenuExpanded = true }
                        ) {
                            Icon(painterResource(Res.drawable.more_vert_24px), null)
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("任务中心") },
                                leadingIcon = { Icon(painterResource(Res.drawable.cloud_download_24px), null) },
                                onClick = {
                                    moreMenuExpanded = false
                                    onOpenTaskCenter()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入书架") },
                                leadingIcon = { Icon(painterResource(Res.drawable.login_24px), null) },
                                onClick = {
                                    moreMenuExpanded = false
                                    onImportBookshelf()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("榜单") },
                                leadingIcon = { Icon(painterResource(Res.drawable.list_24px), null) },
                                onClick = {
                                    moreMenuExpanded = false
                                    onOpenExplore()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("规则管理") },
                                leadingIcon = { Icon(painterResource(Res.drawable.code_blocks_24px), null) },
                                onClick = {
                                    moreMenuExpanded = false
                                    onOpenRuleManagement()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("下载设置") },
                                leadingIcon = { Icon(painterResource(Res.drawable.settings_24px), null) },
                                onClick = {
                                    moreMenuExpanded = false
                                    onOpenSettings()
                                }
                            )
                        }
                    }
                }
            )
        }
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                progress = {
                    if (uiState.refreshTotalCount == 0) 0f
                    else uiState.refreshCompletedCount.toFloat() / uiState.refreshTotalCount
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 💡 带悬停提示（Tooltip）的图标按钮：
 * PC 端鼠标悬停显示文字说明，触屏端长按显示；解决"一排图标不知道是什么"的问题
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltippedIconButton(
    tooltip: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = tooltipState,
        enableUserInput = enabled
    ) {
        IconButton(onClick = onClick, enabled = enabled, content = content)
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
    onToggleDownload: () -> Unit, // 开始/暂停下载
    onAssignGroups: () -> Unit = {} // 打开"设置分组"弹窗
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

                // 2. 💡 未读章节数角标（追更提示）
                val unreadCount = if (book.totalChapters > 0) {
                    (book.totalChapters - book.lastReadChapterIndex - 1).coerceAtLeast(0)
                } else 0
                if (unreadCount > 0 && book.lastReadChapterIndex >= 0) {
                    Surface(
                        color = Color(0xFFE53935),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(bottomEnd = 8.dp, topStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else "$unreadCount",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // 3. 💡 全新未读徽章（从未打开过）
                if (book.lastReadChapterIndex == -1) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(bottomStart = 8.dp, topEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = "NEW",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

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
                } else {
                    // 4. 分组入口角标（非多选模式时显示）
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(30.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .clickable { onAssignGroups() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(Res.drawable.bookmark_add_24px),
                            contentDescription = "设置分组",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
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

/**
 * 💡 分组管理弹窗：新建 / 重命名 / 删除自定义分组。
 * 布局上把「列表」与「新建」分成两个区块；新建按钮用主色填充并内嵌在输入行末尾，
 * 与右下角唯一的中性「完成」按钮形成明确层级，避免误点。
 */
@Composable
fun ManageGroupsDialog(
    groups: List<BookGroupEntity>,
    onCreate: (String, Boolean) -> Unit,
    onUpdate: (Long, String, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newIsAutoUpdate by remember { mutableStateOf(false) }

    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingIsAutoUpdate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理分组", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // ── 区块一：已有分组 ──
                Text(
                    "已有分组（${groups.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                if (groups.isEmpty()) {
                    Text(
                        "还没有分组",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        groups.forEach { group ->
                            if (editingId == group.id) {
                                // 编辑行
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = editingName,
                                            onValueChange = { editingName = it },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                if (editingName.isNotBlank()) onUpdate(group.id, editingName, editingIsAutoUpdate)
                                                editingId = null
                                            },
                                            enabled = editingName.isNotBlank()
                                        ) { Text("保存") }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = editingIsAutoUpdate,
                                            onCheckedChange = { editingIsAutoUpdate = it == true }
                                        )
                                        Text("允许自动更新（追更检测）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (group.isAutoUpdate) "支持自动更新" else "不自动更新",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (group.isAutoUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    IconButton(onClick = {
                                        editingId = group.id
                                        editingName = group.name
                                        editingIsAutoUpdate = group.isAutoUpdate
                                    }) {
                                        Icon(painterResource(Res.drawable.edit_24px), "编辑", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDelete(group.id) }) {
                                        Icon(
                                            painterResource(Res.drawable.delete_24px),
                                            "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // ── 区块二：新建分组 ──
                Text(
                    "新建分组",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("最多20字") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        // 主色填充按钮：与中性文字按钮明确区分
                        Button(
                            onClick = {
                                onCreate(newName, newIsAutoUpdate)
                                newName = ""
                                newIsAutoUpdate = false
                            },
                            enabled = newName.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(painterResource(Res.drawable.add_24px), null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { newIsAutoUpdate = !newIsAutoUpdate }) {
                        Checkbox(
                            checked = newIsAutoUpdate,
                            onCheckedChange = { newIsAutoUpdate = it == true }
                        )
                        Text("允许自动更新（刷新时包含该组）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            // 唯一的对话框级动作：关闭弹窗
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 💡 为书籍设置分组归属弹窗（单书或批量选中）。
 * 单书勾选状态=该书归属；批量勾选状态=所有选中书籍共同拥有的分组（交集），
 * 勾选即写入（位掩码置位/清位）；每行右侧显示命中数，支持一键全选/清空。
 */
@Composable
fun AssignGroupsDialog(
    targetUrls: List<String>,
    books: List<com.zhhz.spider.network.Book>,
    groups: List<BookGroupEntity>,
    onToggleAll: (checked: Boolean) -> Unit,
    onToggle: (mask: Long, checked: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val targets = remember(targetUrls, books) { books.filter { it.url in targetUrls } }
    val checkedMasks: Set<Long> = remember(targets, groups) {
        if (targets.isEmpty()) emptySet()
        else groups.filter { g -> targets.all { it.groupFlags and g.mask != 0L } }.map { it.mask }.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (targetUrls.size == 1) "设置分组" else "为 ${targetUrls.size} 本书设置分组", fontWeight = FontWeight.Bold) },
        text = {
            if (groups.isEmpty()) {
                Text(
                    "暂无分组\n请先点击书架上方「+ 分组」创建",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                )
            } else {
                Column {
                    // 快捷操作行：全部加入 / 全部移出
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (targetUrls.size > 1) "勾选对全部选中书本生效" else "勾选即保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(
                                onClick = { onToggleAll(true) },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("全选", fontSize = 12.sp) }
                            TextButton(
                                onClick = { onToggleAll(false) },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("清空", fontSize = 12.sp) }
                        }
                    }

                    Column(
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    ) {
                        groups.forEach { group ->
                            val checked = group.mask in checkedMasks
                            val hitCount = targets.count { com.zhhz.spider.db.BookGroupFlags.contains(it.groupFlags, group.mask) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(group.mask, !checked) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggle(group.mask, it == true) }
                                )
                                Text(
                                    group.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                // 命中数：n / 已选总数
                                Text(
                                    "$hitCount / ${targets.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 💡 导入书架选项弹窗：选择备份中分组数据的处理策略
 * - 合并导入：自动创建本机不存在的分组，书籍保留原归属（推荐）
 * - 移动到现有分组：不创建新分组，导入的书全部归入所选的一个本机分组
 */
@Composable
private fun ImportOptionsDialog(
    groups: List<BookGroupEntity>,
    onConfirm: (mode: com.zhhz.spider.viewModel.BookGroupImportMode, targetGroupId: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(com.zhhz.spider.viewModel.BookGroupImportMode.MERGE) }
    var targetGroupId by remember { mutableStateOf<Long?>(null) }
    val canConfirm = mode == com.zhhz.spider.viewModel.BookGroupImportMode.MERGE || targetGroupId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入书架", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        mode = com.zhhz.spider.viewModel.BookGroupImportMode.MERGE
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == com.zhhz.spider.viewModel.BookGroupImportMode.MERGE,
                        onClick = { mode = com.zhhz.spider.viewModel.BookGroupImportMode.MERGE }
                    )
                    Column {
                        Text("合并分组", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "自动创建备份中本机没有的分组，保留原有归属",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = groups.isNotEmpty()) {
                        mode = com.zhhz.spider.viewModel.BookGroupImportMode.MOVE_TO_EXISTING
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == com.zhhz.spider.viewModel.BookGroupImportMode.MOVE_TO_EXISTING,
                        onClick = { if (groups.isNotEmpty()) mode = com.zhhz.spider.viewModel.BookGroupImportMode.MOVE_TO_EXISTING },
                        enabled = groups.isNotEmpty()
                    )
                    Column {
                        Text("移动到现有分组", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (groups.isEmpty()) "本机暂无分组，不可选"
                            else "不创建新分组，导入的书全部归入所选分组",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 目标分组选择 chips（仅"移动到现有分组"时显示）
                if (groups.isNotEmpty() && mode == com.zhhz.spider.viewModel.BookGroupImportMode.MOVE_TO_EXISTING) {
                    Spacer(Modifier.height(4.dp))
                    val groupRowState = rememberLazyListState()
                    LazyRow(
                        state = groupRowState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                    ) {
                        items(groups, key = { it.id }) { group ->
                            FilterChip(
                                selected = targetGroupId == group.id,
                                onClick = { targetGroupId = group.id },
                                label = { Text(group.name, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(mode, targetGroupId) },
                enabled = canConfirm
            ) { Text("选择文件") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 💡 导出书架选项弹窗：自选是否包含阅读进度/已读标记
 */
@Composable
private fun ShelfExportOptionsDialog(
    selectedCount: Int,
    onConfirm: (includeProgress: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var includeProgress by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出书架", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "已选择 $selectedCount 本书籍，请选择备份内容：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { includeProgress = !includeProgress },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeProgress, onCheckedChange = { includeProgress = it == true })
                    Column {
                        Text("包含阅读记录", fontSize = 14.sp)
                        Text(
                            "读到第几章、章节已读标记",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(includeProgress) }) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 💡 阅读历史弹层内容：按最后阅读时间倒序列出看过的书（含已移出书架的）。
 */
@Composable
private fun HistoryContent(
    books: List<Book>,
    viewModel: BookshelfViewModel,
    onBookClick: (Book) -> Unit
) {
    // 彻底删除确认弹窗目标
    var purgeTarget by remember { mutableStateOf<Book?>(null) }
    if (purgeTarget != null) {
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("彻底删除？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将删除《${purgeTarget!!.title}》的全部记录、目录与缓存，且不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.processIntent(BookshelfUiIntent.PurgeHistoryBook(purgeTarget!!.url))
                    purgeTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { purgeTarget = null }) { Text("取消") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("阅读历史", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("${books.size} 条", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (books.isEmpty()) {
            Text(
                "暂无阅读记录",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(books, key = { it.url }) { book ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBookClick(book) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 缩略封面
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 60.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            BookCover(url = book.cover, modifier = Modifier.fillMaxSize())
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = book.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = book.lastReadChapterTitle.ifBlank { "尚未开始" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!book.inLibrary) {
                                Text(
                                    text = "已移出书架",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = formatReadTime(book.updateTime),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!book.inLibrary) {
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = { viewModel.processIntent(BookshelfUiIntent.RestoreToShelf(book.url)) },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("加回书架", fontSize = 12.sp)
                            }
                        }

                        // 彻底删除（带确认）
                        IconButton(
                            onClick = { purgeTarget = book },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painterResource(Res.drawable.delete_24px),
                                contentDescription = "彻底删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 相对时间展示：x 分钟前 / x 小时前 / x 天前 / 具体日期 */
private fun formatReadTime(time: Long): String {
    if (time <= 0L) return ""
    val diff = System.currentTimeMillis() - time
    val minute = 60_000L
    val hour = 3_600_000L
    val day = 86_400_000L
    return when {
        diff < hour -> "${(diff / minute).coerceAtLeast(1)} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < day * 30 -> "${diff / day} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(time))
    }
}

/**
 * 💡 桌面端辅助：让 LazyRow 支持"鼠标按住拖拽"与"滚轮横向滚动"。
 * 手机端触摸滚动由 LazyList 原生处理，此修饰符直接跳过。
 */
@Composable
fun Modifier.desktopDragScroll(state: androidx.compose.foundation.lazy.LazyListState): Modifier {
    if (!com.zhhz.spider.isDesktopPlatform()) return this
    return this
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    // 滚轮：垂直滚轮转为横向滚动
                    if (event.type == PointerEventType.Scroll) {
                        val scrollDelta = event.changes.firstOrNull()?.scrollDelta
                        if (scrollDelta != null && scrollDelta.y != 0f) {
                            state.dispatchRawDelta(scrollDelta.y * 24f)
                            event.changes.firstOrNull()?.consume()
                        }
                    }
                }
            }
        }
        .pointerInput(state) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                state.dispatchRawDelta(-dragAmount.x)
            }
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookItemRow(
    book: Book,
    downloadTask: DownloadTask?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleDownload: () -> Unit,
    onAssignGroups: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 80.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                BookCover(url = book.cover, modifier = Modifier.fillMaxSize())
                if (downloadTask != null) {
                    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f))) {
                        IconButton(onClick = onToggleDownload, modifier = Modifier.align(Alignment.Center).size(32.dp)) {
                            val icon = when (downloadTask.status) {
                                DownloadStatus.DOWNLOADING -> painterResource(Res.drawable.pause_24px)
                                DownloadStatus.PAUSED, DownloadStatus.ERROR -> painterResource(Res.drawable.play_arrow_24px)
                                DownloadStatus.COMPLETED -> painterResource(Res.drawable.check_24px)
                                else -> painterResource(Res.drawable.cloud_download_24px)
                            }
                            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                val unreadCount = if (book.totalChapters > 0) (book.totalChapters - book.lastReadChapterIndex - 1).coerceAtLeast(0) else 0
                if (unreadCount > 0 && book.lastReadChapterIndex >= 0) {
                    Surface(
                        color = Color(0xFFE53935),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(bottomEnd = 6.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else "$unreadCount",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                
                if (book.lastReadChapterIndex == -1) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(bottomStart = 4.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = "NEW",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                val readProgress = if (book.lastReadChapterIndex >= 0) "读至: ${book.lastReadChapterTitle}" else "尚未开始"
                Text(readProgress, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val updateTimeText = if (book.updateTime > 0) "最近阅读: " + formatReadTime(book.updateTime) else ""
                Text(updateTimeText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                
                if (downloadTask != null && (downloadTask.status == DownloadStatus.DOWNLOADING || downloadTask.status == DownloadStatus.PAUSED)) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { downloadTask.progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = null)
            } else {
                IconButton(onClick = onAssignGroups) {
                    Icon(painterResource(Res.drawable.bookmark_add_24px), "设置分组", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
