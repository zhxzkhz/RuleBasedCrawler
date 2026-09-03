package com.zhhz.spider.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.rememberBookExportDirectoryAction
import com.zhhz.spider.viewModel.unreadChapterCount
import com.zhhz.spider.PlatformBackHandler
import com.zhhz.spider.rememberRuleFileActions
import com.zhhz.spider.model.DownloadStatus
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.viewModel.BookshelfUiEffect
import com.zhhz.spider.viewModel.BookshelfUiIntent
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
    var showManageGroups by remember { mutableStateOf(false) }
    var assignGroupTarget by remember { mutableStateOf<List<String>?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showShelfExportDialog by remember { mutableStateOf(false) }
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

    PlatformBackHandler(enabled = uiState.isSelectionMode) {
        viewModel.processIntent(BookshelfUiIntent.ToggleSelectionMode)
    }

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
                onSelectAll = { viewModel.processIntent(BookshelfUiIntent.SelectAll) },
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
        bottomBar = {
            if (uiState.isSelectionMode && uiState.selectedBooks.isNotEmpty()) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { viewModel.processIntent(BookshelfUiIntent.DeleteSelectedBooks) }) {
                            Icon(painterResource(Res.drawable.close_24px), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("移出书架 (${uiState.selectedBooks.size})", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { viewModel.processIntent(BookshelfUiIntent.DownloadSelectedBooks) }) {
                            Icon(painterResource(Res.drawable.download_24px), contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("批量下载")
                        }
                        TextButton(onClick = { assignGroupTarget = uiState.selectedBooks.toList() }) {
                            Icon(painterResource(Res.drawable.bookmark_add_24px), contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("分组")
                        }
                        TextButton(onClick = { showExportOptionDialog = true }) {
                            Icon(painterResource(Res.drawable.share_24px), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导出ZIP", color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { showShelfExportDialog = true }) {
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
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (uiState.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (uiState.books.isEmpty()) {
                EmptyBookshelfPlaceholder()
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!uiState.isSelectionMode) {
                        GroupFilterRow(viewModel = viewModel, uiState = uiState, showManageGroups = { showManageGroups = true })
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

    // 弹窗
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

    if (uiState.isExporting) {
        ExportAlertDialog(uiState, viewModel)
    }

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

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            DownloadSettingsContent(viewModel = viewModel, onDismiss = { showSettings = false })
        }
    }

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

    if (uiState.isImporting) {
        AlertDialog(
            onDismissRequest = { },
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

    if (showShelfExportDialog) {
        ShelfExportOptionsDialog(
            selectedCount = uiState.selectedBooks.size,
            onConfirm = { includeProgress ->
                showShelfExportDialog = false
                viewModel.processIntent(BookshelfUiIntent.ExportSelectedAsJson(includeProgress))
            },
            onDismiss = { showShelfExportDialog = false }
        )
    }

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

    if (showManageGroups) {
        ManageGroupsDialog(
            groups = uiState.groups,
            currentStartMask = uiState.startDefaultGroupMask,
            onCreate = { name, isAutoUpdate -> viewModel.processIntent(BookshelfUiIntent.CreateGroup(name, isAutoUpdate)) },
            onUpdate = { id, name, isAutoUpdate -> viewModel.processIntent(BookshelfUiIntent.UpdateGroup(id, name, isAutoUpdate)) },
            onDelete = { viewModel.processIntent(BookshelfUiIntent.DeleteGroup(it)) },
            onSelectStartMask = { mask -> viewModel.processIntent(BookshelfUiIntent.SetStartDefaultGroupMask(mask)) },
            onDismiss = { showManageGroups = false }
        )
    }

    assignGroupTarget?.let { targetUrls ->
        AssignGroupsDialog(
            targetUrls = targetUrls,
            books = uiState.books,
            groups = uiState.groups,
            onToggleAll = { checked ->
                val combinedMask = uiState.groups.fold(0L) { acc, g -> acc or g.mask }
                if (combinedMask != 0L) {
                    viewModel.processIntent(BookshelfUiIntent.SetBooksInGroup(targetUrls, combinedMask, checked))
                }
            },
            onToggle = { mask, checked ->
                viewModel.processIntent(BookshelfUiIntent.SetBooksInGroup(targetUrls, mask, checked))
            },
            onDismiss = { assignGroupTarget = null }
        )
    }
}

@Composable
private fun GroupFilterRow(
    viewModel: BookshelfViewModel,
    uiState: com.zhhz.spider.viewModel.BookshelfUiState,
    showManageGroups: () -> Unit
) {
    val groupRowState = rememberLazyListState()
    LazyRow(
        state = groupRowState,
        modifier = Modifier.fillMaxWidth().then(Modifier.desktopDragScroll(groupRowState)),
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
                onClick = showManageGroups,
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
        item(key = "only_updated") {
            val updatedCount = uiState.books.count { it.inLibrary && it.unreadChapterCount() > 0 }
            FilterChip(
                selected = uiState.onlyUpdated,
                enabled = updatedCount > 0 || uiState.onlyUpdated,
                onClick = { viewModel.processIntent(BookshelfUiIntent.ToggleOnlyUpdated) },
                label = {
                    Text(
                        if (updatedCount > 0) "有更新 · $updatedCount" else "有更新",
                        maxLines = 1
                    )
                }
            )
        }
        item(key = "read_filter") {
            var readFilterMenuOpen by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = uiState.readFilter != "all",
                    onClick = { readFilterMenuOpen = true },
                    label = { Text(readFilterLabel(uiState.readFilter), maxLines = 1) }
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
