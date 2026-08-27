package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.db.BookGroupEntity
import com.zhhz.spider.manager.DownloadManager
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.network.Book
import com.zhhz.spider.repository.BookRepository
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.DetailRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import com.zhhz.spider.util.BookPackager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class BookshelfViewModel(
    private val repository: BookRepository, // 后续建议替换为 BookRepository
    private val sessionRepository: SessionRepository,
    private val detailRepository: DetailRepository,
    private val catalogRepository: CatalogRepository,
    private val downloadManager: DownloadManager,
    private val bookPackager: BookPackager,
    private val cleanupManager: com.zhhz.spider.manager.BookCleanupManager,
    private val settingsRepository: com.zhhz.spider.settings.SettingsRepository
) : BaseViewModel<BookshelfUiState, BookshelfUiIntent, BookshelfUiEffect>(
    initialState = BookshelfUiState()
) {


    init {
        // 初始化时发送意图：观察书籍变化
        processIntent(BookshelfUiIntent.ObserveBooks)
        // 恢复上次的排序与视图偏好等
        runCatching {
            val savedSettings = settingsRepository.loadBlocking()
            val savedSort = savedSettings.shelfSort
            val savedViewMode = savedSettings.shelfViewMode
            updateState {
                copy(
                    sortMode = if (savedSort in setOf("recent", "created", "title")) savedSort else "recent",
                    viewMode = if (savedViewMode == "list") "list" else "grid",
                    updateUngroupedBooks = savedSettings.updateUngroupedBooks
                )
            }
        }
        // 观察自定义分组定义变化
        processIntent(BookshelfUiIntent.ObserveGroups)
        // 💡 新增：观察下载任务流，并将其合并到 UiState 中供界面显示
        viewModelScope.launch {
            downloadManager.downloadTasks.collectLatest { tasks ->
                updateState { copy(downloadTasks = tasks) }
            }
        }
    }

    private var exportJob: Job? = null
    private var refreshJob: Job? = null

    override fun handleIntent(intent: BookshelfUiIntent) {
        when (intent) {
            is BookshelfUiIntent.ObserveBooks -> handleObserveBooks()
            is BookshelfUiIntent.BookClicked -> handleBookClicked(intent.book)

            is BookshelfUiIntent.DeleteBook -> handleDeleteBook(intent.book)
            is BookshelfUiIntent.PinBook -> { /* 更新数据库中书本的 order 字段 */ }

            is BookshelfUiIntent.StartDownload -> {
                viewModelScope.launch { downloadManager.startDownload(intent.bookUrl, intent.ruleId, intent.bookTitle) }
            }
            is BookshelfUiIntent.PauseDownload -> {
                viewModelScope.launch { downloadManager.pauseDownload(intent.bookUrl) }
            }
            is BookshelfUiIntent.CancelDownload -> {
                viewModelScope.launch { downloadManager.cancelDownload(intent.bookUrl) }
            }
            is BookshelfUiIntent.ClearUnfinishedDownloads -> {
                viewModelScope.launch { downloadManager.cancelUnfinished() }
            }
            is BookshelfUiIntent.SaveDownloadSettings -> {
                viewModelScope.launch {
                    settingsRepository.save(
                        com.zhhz.spider.settings.SettingsRepository.AppSettings(
                            downloadBooks = intent.books,
                            downloadImages = intent.images
                        )
                    )
                    downloadManager.updateConcurrency(intent.books, intent.images)
                    sendEffect(BookshelfUiEffect.ShowToast("下载并发已保存：${intent.books} 本 / ${intent.images} 张图"))
                }
            }

            is BookshelfUiIntent.ExportSelectedBooks -> handleExportSelectedBooks(
                intent.destinationDirectory,
                intent.onlyCached,
                intent.concurrencyLimit,
                intent.delayMs
            )

            is BookshelfUiIntent.CancelExport -> {
                // 💡 掐断打包协程！底层 .use 块会自动释放文件，catch 块会自动删除临时 zip
                exportJob?.cancel()
                updateState { copy(isExporting = false) }
                sendEffect(BookshelfUiEffect.ShowToast("已取消导出"))
            }

            is BookshelfUiIntent.DownloadSelectedBooks -> handleDownloadSelectedBooks()

            is BookshelfUiIntent.ExportSelectedAsJson -> handleExportSelectedAsJson(intent.includeProgress)
            is BookshelfUiIntent.ImportBooks -> handleImportBooks(
                intent.jsonContent,
                intent.groupMode,
                intent.targetGroupId
            )

            is BookshelfUiIntent.RefreshBooks -> handleRefreshBooks()

            // ---------------- 自定义分组 ----------------
            is BookshelfUiIntent.ObserveGroups -> handleObserveGroups()
            is BookshelfUiIntent.SelectGroup -> updateState { copy(selectedGroupMask = intent.mask) }
            is BookshelfUiIntent.SetShelfSort -> {
                updateState { copy(sortMode = intent.mode) }
                // 排序偏好持久化，下次启动保持
                viewModelScope.launch {
                    runCatching {
                        settingsRepository.save(settingsRepository.loadBlocking().copy(shelfSort = intent.mode))
                    }
                }
            }
            is BookshelfUiIntent.SetShelfViewMode -> {
                updateState { copy(viewMode = intent.mode) }
                viewModelScope.launch {
                    runCatching {
                        settingsRepository.save(settingsRepository.loadBlocking().copy(shelfViewMode = intent.mode))
                    }
                }
            }
            is BookshelfUiIntent.SetReadFilter -> updateState { copy(readFilter = intent.filter) }
            is BookshelfUiIntent.ToggleOnlyUpdated -> updateState { copy(onlyUpdated = !onlyUpdated) }
            is BookshelfUiIntent.SetUpdateUngroupedBooks -> {
                updateState { copy(updateUngroupedBooks = intent.enabled) }
                viewModelScope.launch {
                    runCatching {
                        settingsRepository.save(settingsRepository.loadBlocking().copy(updateUngroupedBooks = intent.enabled))
                    }
                }
            }
            is BookshelfUiIntent.CreateGroup -> {
                viewModelScope.launch {
                    try {
                        val group = repository.createGroup(intent.name, intent.isAutoUpdate)
                        sendEffect(BookshelfUiEffect.ShowToast("已创建分组「${group.name}」"))
                    } catch (e: Exception) {
                        sendEffect(BookshelfUiEffect.ShowToast(e.message ?: "创建失败"))
                    }
                }
            }
            is BookshelfUiIntent.UpdateGroup -> {
                viewModelScope.launch {
                    try {
                        repository.updateGroup(intent.groupId, intent.newName, intent.isAutoUpdate)
                        sendEffect(BookshelfUiEffect.ShowToast("已保存修改"))
                    } catch (e: Exception) {
                        sendEffect(BookshelfUiEffect.ShowToast(e.message ?: "修改失败"))
                    }
                }
            }
            is BookshelfUiIntent.DeleteGroup -> handleDeleteGroup(intent.groupId)
            is BookshelfUiIntent.SetBooksInGroup -> {
                viewModelScope.launch {
                    repository.setBooksInGroup(intent.urls, intent.mask, intent.checked)
                    val groupName = uiState.value.groups.find { it.mask == intent.mask }?.name ?: "分组"
                    sendEffect(
                        BookshelfUiEffect.ShowToast(
                            if (intent.checked) "已将 ${intent.urls.size} 本加入「$groupName」"
                            else "已将 ${intent.urls.size} 本移出「$groupName」"
                        )
                    )
                }
            }
            is BookshelfUiIntent.RestoreToShelf -> handleRestoreToShelf(intent.bookUrl)
            is BookshelfUiIntent.PurgeHistoryBook -> handlePurgeHistoryBook(intent.bookUrl)
            is BookshelfUiIntent.RetryFailedChapters -> handleRetryFailedChapters(intent.bookUrl)

            is BookshelfUiIntent.ToggleSelectionMode -> updateState { copy(isSelectionMode = !isSelectionMode, selectedBooks = emptySet()) }
            is BookshelfUiIntent.ToggleSelectBook -> handleToggleSelectBook(intent.bookUrl)
            is BookshelfUiIntent.SelectAll -> handleSelectAll()
            is BookshelfUiIntent.DeleteSelectedBooks -> {
                handleDeleteSelectedBooks()
            } //handleDeleteSelectedBooks()
        }
    }

    private fun handleDeleteSelectedBooks() {
        val selectedUrls = uiState.value.selectedBooks.toList()
        if (selectedUrls.isEmpty()) return

        viewModelScope.launch {
            var failCount = 0
            selectedUrls.forEach { url ->
                val book = uiState.value.books.find { it.url == url } ?: return@forEach
                try {
                    // 💡 软移除：清图片缓存+停下载，保留章节与进度（inLibrary=0）
                    cleanupManager.cleanup(url, clearTextCache = false)
                    repository.removeFromShelf(listOf(url))
                } catch (e: Exception) {
                    failCount++
                    sendEffect(BookshelfUiEffect.ShowToast("《${book.title}》移出失败: ${e.message}"))
                }
            }

            updateState {
                copy(
                    books = uiState.value.books.map { book ->
                        if (book.url in selectedUrls) book.copy(inLibrary = false) else book
                    },
                    selectedBooks = emptySet(),
                    isSelectionMode = false
                )
            }
            sendEffect(
                BookshelfUiEffect.ShowToast(
                    if (failCount == 0) "已移出 ${selectedUrls.size} 本（阅读历史中可找回）"
                    else "操作完成，$failCount 本失败"
                )
            )
        }
    }

    // 💡 重点：一键直达阅读器的逻辑
    private fun handleBookClicked(book: Book) {
        if (uiState.value.isSelectionMode) {
            // 如果在多选模式下，点击书本是勾选/取消勾选，而不是跳页
            handleToggleSelectBook(book.url)
            return
        }

        // 💡 为了防丢状态：跳转详情前，先把书装进内存会话，自动触发底层的 forkContext()
        viewModelScope.launch {
            sessionRepository.saveData(book)

            sendEffect(BookshelfUiEffect.NavigateToDetail(book = book))
        }
    }

    private fun handleObserveBooks() {
        viewModelScope.launch {
            // 收集数据流，并通过统一的 updateState 方法更新状态
            repository.loadData().collectLatest { bookList ->
                updateState { copy(books = bookList, isLoading = false) }
            }
        }
    }

    private fun handleObserveGroups() {
        viewModelScope.launch {
            repository.observeGroups().collectLatest { groups ->
                // 分组被外部（如导入）变更后，若当前筛选的分组已消失则回到"全部"
                val validMask = groups.fold(0L) { acc, g -> acc or g.mask }
                updateState {
                    copy(
                        groups = groups,
                        selectedGroupMask = if (selectedGroupMask and validMask != 0L || selectedGroupMask == 0L) selectedGroupMask else 0L
                    )
                }
            }
        }
    }

    private fun handleDeleteGroup(groupId: Long) {        val target = uiState.value.groups.find { it.id == groupId } ?: return
        viewModelScope.launch {
            try {
                repository.deleteGroup(groupId)
                updateState {
                    copy(selectedGroupMask = if (selectedGroupMask and target.mask != 0L) 0L else selectedGroupMask)
                }
                sendEffect(BookshelfUiEffect.ShowToast("已删除分组「${target.name}」"))
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("删除失败: ${e.message}"))
            }
        }
    }

    private fun handleDeleteBook(book: Book) {
        viewModelScope.launch {
            try {
                // 💡 软移除：清掉图片磁盘缓存并停掉下载任务，
                // 保留章节目录与阅读进度（inLibrary=0），可在阅读历史中找回
                cleanupManager.cleanup(book.url, clearTextCache = false)
                repository.removeFromShelf(listOf(book.url))
                sendEffect(BookshelfUiEffect.ShowToast("已移出书架（阅读历史中可找回）"))
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("移出失败: ${e.message}"))
            }
        }
    }

    /** 从阅读历史把书加回书架 */
    private fun handleRestoreToShelf(bookUrl: String) {
        viewModelScope.launch {
            try {
                repository.restoreToShelf(listOf(bookUrl))
                sendEffect(BookshelfUiEffect.ShowToast("已加回书架"))
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("加回失败: ${e.message}"))
            }
        }
    }

    /**
     * 彻底删除（阅读历史中的书）：物理删除书籍行 + 全部缓存（含文本缓存），
     * 章节目录随外键级联清除，不可恢复。
     */
    private fun handlePurgeHistoryBook(bookUrl: String) {
        val book = uiState.value.books.find { it.url == bookUrl } ?: return
        viewModelScope.launch {
            try {
                cleanupManager.cleanup(book.url, clearTextCache = true)
                repository.deleteData(book)
                sendEffect(BookshelfUiEffect.ShowToast("已彻底删除《${book.title}》"))
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("删除失败: ${e.message}"))
            }
        }
    }

    /** 读取当前下载并发设置（供设置界面初始化显示） */
    fun loadDownloadSettings(): com.zhhz.spider.settings.SettingsRepository.AppSettings =
        settingsRepository.loadBlocking()

    private fun handleRefreshBooks() {
        if (refreshJob?.isActive == true) return

        // 💡 过滤出允许自动更新的书籍：
        // 只要该书所属的任意一个分组允许自动更新，或者该书没有分组，就进行更新。
        // （你可以根据实际需求调整：比如只有设置了自动更新的书才更新，或者未分组的书不更新。
        // 这里以"未分组的书也更新，但设置了且全部分组都不允许更新的书不更新"为策略）
        val groupsConfig = uiState.value.groups
        val autoUpdateMasks = groupsConfig.filter { it.isAutoUpdate }.fold(0L) { acc, g -> acc or g.mask }
        val isUpdateUngrouped = uiState.value.updateUngroupedBooks

        val books = uiState.value.books.filter { book ->
            if (!book.inLibrary) false
            else if (book.groupFlags == 0L) isUpdateUngrouped // 根据全局设置决定未分组书是否更新
            else (book.groupFlags and autoUpdateMasks) != 0L // 只要命中一个自动更新分组就更新
        }

        if (books.isEmpty()) {
            sendEffect(BookshelfUiEffect.ShowToast("没有需要更新的书籍（被分组设置过滤）"))
            return
        }

        updateState {
            copy(
                isRefreshing = true,
                refreshCompletedCount = 0,
                refreshTotalCount = books.size,
                refreshBookTitle = "准备刷新目录"
            )
        }

        refreshJob = viewModelScope.launch {
            try {
                val semaphore = Semaphore(permits = 3)
                val results = supervisorScope {
                    books.map { book ->
                        async {
                            semaphore.withPermit { refreshBookCatalog(book) }
                        }
                    }.awaitAll()
                }

                val successCount = results.count { it.errorMessage == null }
                val failureCount = results.size - successCount
                val updatedBookCount = results.count { it.addedChapterCount > 0 }
                val addedChapterCount = results.sumOf { it.addedChapterCount }
                val message = buildString {
                    append("目录刷新完成：成功 $successCount 本")
                    if (updatedBookCount > 0) append("，$updatedBookCount 本新增 $addedChapterCount 章")
                    if (failureCount > 0) append("，失败 $failureCount 本")
                }
                sendEffect(BookshelfUiEffect.ShowToast(message))

                // 记录本次追更检查时间（供节流自动刷新使用）
                runCatching {
                    settingsRepository.save(
                        settingsRepository.loadBlocking().copy(lastUpdateCheckAt = System.currentTimeMillis())
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                updateState {
                    copy(
                        isRefreshing = false,
                        refreshBookTitle = ""
                    )
                }
            }
        }
    }

    private suspend fun refreshBookCatalog(book: Book): CatalogRefreshResult {
        updateState { copy(refreshBookTitle = book.title) }
        return try {
            val localChapters = catalogRepository.loadData(book.url)
            val detail = detailRepository.fetchData(book.url, book.ruleId)
            val chapters = catalogRepository.fetchData(
                catalogUrl = detail.catalogUrl,
                ruleId = book.ruleId,
                bookUrl = book.url
            )
            check(chapters.isNotEmpty()) { "获取到的章节目录为空" }
            catalogRepository.saveData(book.url, chapters)
            CatalogRefreshResult(
                addedChapterCount = (chapters.size - localChapters.size).coerceAtLeast(0)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CatalogRefreshResult(errorMessage = e.message ?: "未知错误")
        } finally {
            updateState { copy(refreshCompletedCount = refreshCompletedCount + 1) }
        }
    }

    // 💡 1. 单选/反选某本书的逻辑
    private fun handleToggleSelectBook(bookUrl: String) {
        updateState {
            val currentSelected = selectedBooks

            // 利用 Kotlin 集合的 + 和 - 操作符，安全地返回一个新的不可变 Set
            val updatedSelected = if (currentSelected.contains(bookUrl)) {
                currentSelected - bookUrl // 已选中，则移出集合
            } else {
                currentSelected + bookUrl // 未选中，则加入集合
            }

            copy(selectedBooks = updatedSelected)
        }
    }

    // 💡 2. 额外补全：一键“全选/取消全选”的极致体验逻辑！
    private fun handleSelectAll() {
        val state = uiState.value
        val allBookUrls = state.books.map { it.url }.toSet()

        updateState {
            // 如果当前选中的数量已经等于总数，说明已经是“全选”状态，此时点击则“取消全选（清空）”
            val updatedSelected = if (selectedBooks.size == allBookUrls.size) {
                emptySet()
            } else {
                allBookUrls // 否则，一键全选所有书籍
            }
            copy(selectedBooks = updatedSelected)
        }
    }

    // 💡 核心实现：将选中书籍导出为书架备份 JSON（可自选是否含阅读进度）
    private fun handleExportSelectedAsJson(includeProgress: Boolean) {
        val selectedUrls = uiState.value.selectedBooks
        if (selectedUrls.isEmpty()) return

        viewModelScope.launch {
            try {
                val content = repository.exportBackupJson(selectedUrls, includeProgress)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                sendEffect(BookshelfUiEffect.SaveBookshelfBackup("bookshelf_backup_$timestamp.json", content))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("导出书架失败: ${e.message}"))
            }
        }
    }

    // 💡 核心实现：从备份 JSON 导入书籍（分组策略由导入弹窗决定）
    private fun handleImportBooks(
        jsonContent: String,
        groupMode: BookGroupImportMode = BookGroupImportMode.MERGE,
        targetGroupId: Long? = null
    ) {
        if (uiState.value.isImporting) return
        updateState { copy(isImporting = true) }

        viewModelScope.launch {
            try {
                val result = repository.importBackupJson(jsonContent, groupMode, targetGroupId)
                val modeHint = when (groupMode) {
                    BookGroupImportMode.MERGE -> ""
                    BookGroupImportMode.MOVE_TO_EXISTING -> "，已归入指定分组"
                }
                sendEffect(BookshelfUiEffect.ShowToast("导入完成：${result.toMessage()}$modeHint"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("导入书架失败: ${e.message}"))
            } finally {
                updateState { copy(isImporting = false) }
            }
        }
    }

    // 💡 批量下载核心实现
    private fun handleDownloadSelectedBooks() {        val state = uiState.value
        val selectedUrls = state.selectedBooks // 拿到当前所有被勾选的书籍 URL 集合

        if (selectedUrls.isEmpty()) return

        viewModelScope.launch {
            // 1. 遍历选中的书籍
            selectedUrls.forEach { url ->
                // 从我们当前的书本列表中，找到对应的书本实体（为了拿到 ruleId 和书名）
                val book = state.books.find { it.url == url }
                if (book != null) {
                    // 2. 💡 调度！无脑丢给全局下载管理器去后台跑协程物理下载（文字和漫画通吃）
                    downloadManager.startDownload(
                        bookUrl = book.url,
                        ruleId = book.ruleId,
                        bookTitle = book.title
                    )
                }
            }

            // 3. 💡 极致的交互体验：启动下载后，自动退出多选模式，并清空所有勾选
            updateState {
                copy(isSelectionMode = false, selectedBooks = emptySet())
            }

            // 4. 弹出 Toast 提示
            sendEffect(BookshelfUiEffect.ShowToast("已将 ${selectedUrls.size} 本书加入后台下载队列"))
        }
    }

    /** 重试上一轮下载失败的章节 */
    private fun handleRetryFailedChapters(bookUrl: String) {
        val task = uiState.value.downloadTasks[bookUrl] ?: return
        val failedUrls = task.failedUrls
        if (failedUrls.isEmpty()) return
        val book = uiState.value.books.find { it.url == bookUrl }

        viewModelScope.launch {
            try {
                downloadManager.downloadChapters(
                    bookUrl = bookUrl,
                    ruleId = book?.ruleId ?: "",
                    bookTitle = task.bookTitle,
                    chapterUrls = failedUrls
                )
                sendEffect(BookshelfUiEffect.ShowToast("已重新排队 ${failedUrls.size} 个失败章节"))
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("重试失败: ${e.message}"))
            }
        }
    }

    // 💡 核心实现：批量导出为 ZIP 包
    private fun handleExportSelectedBooks(
        destinationDirectory: String,
        onlyCached: Boolean,
        concurrencyLimit: Int,
        delayMs: Long
    ) {
        val state = uiState.value
        val selectedUrls = state.selectedBooks
        if (selectedUrls.isEmpty()) return

        // 💡 捕获 Job 引用
        exportJob = viewModelScope.launch {
            try {
                var successCount = 0

                selectedUrls.forEach { url ->
                    val book = state.books.find { it.url == url }
                    if (book != null) {
                        updateState {
                            copy(
                                isExporting = true,
                                exportProgress = 0f,
                                exportBookTitle = book.title,
                                exportChapterProgress = "正在准备数据..."
                            )
                        }

                        // 💡 传入限制参数执行
                        val success = bookPackager.packageBookToZip(
                            bookUrl = book.url,
                            destinationDirectory = destinationDirectory,
                            onlyCached = onlyCached,
                            concurrencyLimit = concurrencyLimit,
                            delayMs = delayMs
                        ) { current, total ->
                            updateState {
                                copy(
                                    exportProgress = current.toFloat() / total,
                                    exportChapterProgress = "已完成 $current / $total 章" + if (onlyCached) " (仅已缓存)" else " (正在全本下载)"
                                )
                            }
                        }
                        if (success) successCount++
                    }
                }

                updateState { copy(isExporting = false, isSelectionMode = false, selectedBooks = emptySet()) }
                sendEffect(BookshelfUiEffect.ShowToast("成功导出 $successCount 本漫画 ZIP"))

            } catch (e: Exception) {
                updateState { copy(isExporting = false) }
                if (e !is CancellationException) { // 如果不是主动取消，才弹报错
                    sendEffect(BookshelfUiEffect.ShowToast("导出异常: ${e.message}"))
                }
            }
        }
    }

}


// ==========================================
// 1. 书架状态 (UiState)
// ==========================================
data class BookshelfUiState(
    // 核心数据：书架里的书籍列表
    val books: List<Book> = emptyList(),

    // 💡 融合刚才设计的全局下载管理器状态
    // Key 为 bookUrl，Value 为对应的下载任务进度
    val downloadTasks: Map<String, DownloadTask> = emptyMap(),

    // 页面级状态
    val isLoading: Boolean = true,           // 首次冷启动加载中
    val isRefreshing: Boolean = false,       // 下拉刷新检测全本更新中
    val refreshCompletedCount: Int = 0,
    val refreshTotalCount: Int = 0,
    val refreshBookTitle: String = "",
    val isSelectionMode: Boolean = false,    // 是否处于多选管理模式 (如批量删除/下载)
    val selectedBooks: Set<String> = emptySet(), // 当前选中的书本 URLs (用于批量操作)

    // 💡 自定义分组：定义列表 + 当前筛选的位掩码（0=全部）
    val groups: List<BookGroupEntity> = emptyList(),
    val selectedGroupMask: Long = 0L,

    // 💡 书架排序与视图模式
    val sortMode: String = "recent",
    val onlyUpdated: Boolean = false,
    val viewMode: String = "grid",
    val readFilter: String = "all", // all(全部) / unread(未读) / reading(在看)

    // 💡 核心新增：专门用于控制导出进度条的 UI 状态
    val isExporting: Boolean = false,     // 💡 是否正在导出（控制弹窗显示）
    val exportProgress: Float = 0f,       // 💡 进度比例：从 0.0f 到 1.0f
    val exportBookTitle: String = "",     // 💡 当前正在导出的书名
    val exportChapterProgress: String = "", // 💡 文本显示进度，如 "50 / 120 章"

    // 💡 是否正在导入书架备份（大文件防"卡死"感）
    val isImporting: Boolean = false,

    // 💡 全局设置：未分组的书籍是否参与自动更新
    val updateUngroupedBooks: Boolean = true
) : UiState {
    /** 主书架列表：仅在架书籍，叠加分组/有更新筛选与排序 */
    val filteredBooks: List<Book>
        get() {
            var result = books.filter { it.inLibrary }
            // 分组位掩码相交即命中，一本书可属多个分组
            if (selectedGroupMask != 0L) {
                result = result.filter { it.groupFlags and selectedGroupMask != 0L }
            }
            if (onlyUpdated) {
                result = result.filter { it.unreadChapterCount() > 0 }
            }
            if (readFilter == "unread") {
                result = result.filter { it.lastReadChapterIndex == -1 }
            } else if (readFilter == "reading") {
                result = result.filter { it.lastReadChapterIndex >= 0 }
            }
            return when (sortMode) {
                "created" -> result.sortedByDescending { it.createTime }
                "title" -> result.sortedBy { it.title }
                else -> result.sortedByDescending { it.updateTime }
            }
        }

    /** 阅读历史：全部书籍按最后阅读时间倒序（含已移出书架的书） */
    val historyBooks: List<Book>
        get() = books.sortedByDescending { it.updateTime }
}

/** 未读章节数（totalChapters 未知时返回 0） */
fun Book.unreadChapterCount(): Int =
    if (totalChapters > 0) (totalChapters - lastReadChapterIndex - 1).coerceAtLeast(0) else 0

private data class CatalogRefreshResult(
    val addedChapterCount: Int = 0,
    val errorMessage: String? = null
)

/** 备份导入时分组数据的处理策略 */
enum class BookGroupImportMode {
    /** 合并：自动创建备份中本机不存在的分组定义，书籍保留原分组归属 */
    MERGE,
    /** 移动到现有分组：不创建任何新分组，导入的书全部归入指定的一个本机已有分组 */
    MOVE_TO_EXISTING
}

// ==========================================
// 2. 用户意图 (UiIntent)
// ==========================================
sealed class BookshelfUiIntent : UiIntent {

    // --- 基础加载与互动 ---
    /** 启动观察本地数据库的书架变动流 (通常在 init 中只发一次) */
    data object ObserveBooks : BookshelfUiIntent()

    /** 用户点击某一本书进行阅读 */
    data class BookClicked(val book: Book) : BookshelfUiIntent()

    // --- 单书管理操作 ---
    /** 从书架中移出某本书 */
    data class DeleteBook(val book: Book) : BookshelfUiIntent()

    /** (可选) 将书本置顶排序 */
    data class PinBook(val book: Book) : BookshelfUiIntent()

    // --- 离线下载操作 ---
    /** 开始/继续下载本书 */
    data class StartDownload(val bookUrl: String, val ruleId: String, val bookTitle: String) : BookshelfUiIntent()

    /** 暂停下载本书 */
    data class PauseDownload(val bookUrl: String) : BookshelfUiIntent()

    /** 取消下载本书（终止并移除任务记录，区别于暂停） */
    data class CancelDownload(val bookUrl: String) : BookshelfUiIntent()

    /** 清除所有未完成的下载任务（保留已完成） */
    data object ClearUnfinishedDownloads : BookshelfUiIntent()

    /** 保存下载并发设置（书本数/图片数），立即生效并持久化 */
    data class SaveDownloadSettings(val books: Int, val images: Int) : BookshelfUiIntent()

    data object DownloadSelectedBooks : BookshelfUiIntent()

    // 💡 新增：将选中书籍导出为书架备份 JSON（弹窗自选是否含阅读进度）
    data class ExportSelectedAsJson(
        val includeProgress: Boolean = true
    ) : BookshelfUiIntent()

    // 💡 新增：从备份 JSON 导入书籍到书架
    data class ImportBooks(
        val jsonContent: String,
        val groupMode: BookGroupImportMode = BookGroupImportMode.MERGE,
        val targetGroupId: Long? = null
    ) : BookshelfUiIntent()

    // 💡 新增：批量将选中书籍打包导出为 ZIP 的意图
    data class ExportSelectedBooks(val destinationDirectory: String,
                                   val onlyCached: Boolean,
                                   val concurrencyLimit: Int,
                                   val delayMs: Long) : BookshelfUiIntent()

    // 💡 2. 新增：用户点击取消导出的意图
    data object CancelExport : BookshelfUiIntent()

    // --- 批量与刷新操作 ---
    /** 用户下拉刷新：触发所有书本的后台更新检测 */
    data object RefreshBooks : BookshelfUiIntent()

    /** 开启/关闭多选编辑模式 */
    data object ToggleSelectionMode : BookshelfUiIntent()

    // ---------------- 自定义分组 ----------------

    /** 启动观察分组定义流 (init 中只发一次) */
    data object ObserveGroups : BookshelfUiIntent()

    /** 切换分组筛选（0 = 全部） */
    data class SelectGroup(val mask: Long) : BookshelfUiIntent()

    /** 创建自定义分组（自动分配 2^n 掩码） */
    data class CreateGroup(val name: String, val isAutoUpdate: Boolean) : BookshelfUiIntent()

    /** 修改分组（含名称与是否自动更新） */
    data class UpdateGroup(val groupId: Long, val newName: String, val isAutoUpdate: Boolean) : BookshelfUiIntent()

    /** 删除分组（书籍归属位同步清除） */
    data class DeleteGroup(val groupId: Long) : BookshelfUiIntent()

    /** 设置一批书的分组归属（checked=true 加入 / false 移出），支持单书与批量 */
    data class SetBooksInGroup(val urls: List<String>, val mask: Long, val checked: Boolean) : BookshelfUiIntent()

    /** 从阅读历史把书加回书架 */
    data class RestoreToShelf(val bookUrl: String) : BookshelfUiIntent()

    /** 从阅读历史彻底删除（物理删除+清全部缓存，不可恢复） */
    data class PurgeHistoryBook(val bookUrl: String) : BookshelfUiIntent()

    /** 重试某本书上一轮下载失败的章节 */
    data class RetryFailedChapters(val bookUrl: String) : BookshelfUiIntent()

    // ---------------- 书架排序/筛选/视图 ----------------

    /** 书架排序：recent / created / title（持久化） */
    data class SetShelfSort(val mode: String) : BookshelfUiIntent()

    /** 书架视图：grid / list（持久化） */
    data class SetShelfViewMode(val mode: String) : BookshelfUiIntent()

    /** 仅显示有未读章节的书 */
    data object ToggleOnlyUpdated : BookshelfUiIntent()

    /** 修改未分组书籍的自动更新偏好 */
    data class SetUpdateUngroupedBooks(val enabled: Boolean) : BookshelfUiIntent()

    /** 阅读进度筛选：all / unread / reading */
    data class SetReadFilter(val filter: String) : BookshelfUiIntent()

    /** 在多选模式下，选中/取消选中某本书 */
    data class ToggleSelectBook(val bookUrl: String) : BookshelfUiIntent()

    data object SelectAll: BookshelfUiIntent()

    /** 删除所有选中的书籍 */
    data object DeleteSelectedBooks : BookshelfUiIntent()
}

// ==========================================
// 3. 副作用 (UiEffect)
// ==========================================
sealed class BookshelfUiEffect : UiEffect {
    /** 弹出提示 */
    data class ShowToast(val message: String) : BookshelfUiEffect()

    /** 💡 书架备份 JSON 已生成，交给平台文件选择器保存 */
    data class SaveBookshelfBackup(val fileName: String, val content: String) : BookshelfUiEffect()

    /** 💡 导航跳转：进入书籍详情页（可查看目录、换源、续读）*/
    data class NavigateToDetail(val book: Book) : BookshelfUiEffect()
}
