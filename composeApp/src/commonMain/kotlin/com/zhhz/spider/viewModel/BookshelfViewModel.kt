package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.db.BookEntity
import com.zhhz.spider.manager.DownloadManager
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.network.Book
import com.zhhz.spider.repository.BookRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import com.zhhz.spider.util.BookPackager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class BookshelfViewModel(
    private val repository: BookRepository, // 后续建议替换为 BookRepository
    private val sessionRepository: SessionRepository,
    private val downloadManager: DownloadManager,
    private val bookPackager: BookPackager
) : BaseViewModel<BookshelfUiState, BookshelfUiIntent, BookshelfUiEffect>(
    initialState = BookshelfUiState()
) {


    init {
        // 初始化时发送意图：观察书籍变化
        processIntent(BookshelfUiIntent.ObserveBooks)
        // 💡 新增：观察下载任务流，并将其合并到 UiState 中供界面显示
        viewModelScope.launch {
            downloadManager.downloadTasks.collectLatest { tasks ->
                updateState { copy(downloadTasks = tasks) }
            }
        }
    }

    private var exportJob: Job? = null

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

            is BookshelfUiIntent.ExportSelectedBooks -> handleExportSelectedBooks(intent.onlyCached, intent.concurrencyLimit, intent.delayMs)

            is BookshelfUiIntent.CancelExport -> {
                // 💡 掐断打包协程！底层 .use 块会自动释放文件，catch 块会自动删除临时 zip
                exportJob?.cancel()
                updateState { copy(isExporting = false) }
                sendEffect(BookshelfUiEffect.ShowToast("已取消导出"))
            }

            is BookshelfUiIntent.DownloadSelectedBooks -> handleDownloadSelectedBooks()

            is BookshelfUiIntent.RefreshBooks -> {  }//handleRefreshBooks()

            is BookshelfUiIntent.ToggleSelectionMode -> updateState { copy(isSelectionMode = !isSelectionMode, selectedBooks = emptySet()) }
            is BookshelfUiIntent.ToggleSelectBook -> handleToggleSelectBook(intent.bookUrl)
            is BookshelfUiIntent.SelectAll -> handleSelectAll()
            is BookshelfUiIntent.DeleteSelectedBooks -> {
                handleDeleteSelectedBooks()
            } //handleDeleteSelectedBooks()
        }
    }

    private fun handleDeleteSelectedBooks() {
        val state = uiState.value
        val selectedUrls = state.selectedBooks
        viewModelScope.launch {
            // 1. 遍历选中的书籍
            selectedUrls.forEach { url ->
                // 从我们当前的书本列表中，找到对应的书本实体（为了拿到 ruleId 和书名）
                val book = state.books.find { it.url == url }
                if (book != null) {
                    try {
                        repository.deleteData(book)
                        updateState { copy(books = state.books.filter { it.url != url }) }
                    } catch (e: Exception) {
                        sendEffect(BookshelfUiEffect.ShowToast("删除失败: ${e.message}"))
                    }
                }
            }
            updateState {
                copy(selectedBooks = emptySet())
                copy(isSelectionMode = false)
            }
            sendEffect(BookshelfUiEffect.ShowToast("删除成功"))
        }

        }

    // 💡 重点：一键直达阅读器的逻辑
    private fun handleBookClicked(book: Book) {
        if (uiState.value.isSelectionMode) {
            // 如果在多选模式下，点击书本是勾选/取消勾选，而不是跳页
            handleToggleSelectBook(book.url)
            return
        }

        // 💡 为了防丢状态：在跳进阅读器前，先把书装进内存会话，自动触发底层的 forkContext()
        viewModelScope.launch {
            sessionRepository.saveData(book)

            sendEffect(
                BookshelfUiEffect.NavigateToReader(
                    bookUrl = book.url,
                    ruleId = book.ruleId,
                    bookTitle = book.title
                )
            )
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

    private fun handleDeleteBook(book: Book) {
        viewModelScope.launch {
            try {
                repository.deleteData(book)
                sendEffect(BookshelfUiEffect.ShowToast("删除成功"))
            } catch (e: Exception) {
                sendEffect(BookshelfUiEffect.ShowToast("删除失败: ${e.message}"))
            }
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

    // 💡 批量下载核心实现
    private fun handleDownloadSelectedBooks() {
        val state = uiState.value
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

    // 💡 核心实现：批量导出为 ZIP 包
    private fun handleExportSelectedBooks(onlyCached: Boolean, concurrencyLimit: Int, delayMs: Long) {
        val state = uiState.value
        val selectedUrls = state.selectedBooks
        if (selectedUrls.isEmpty()) return

        // 💡 捕获 Job 引用
        exportJob = viewModelScope.launch {
            try {
                val userHome = System.getProperty("user.home") ?: ""
                val desktopPath = "$userHome/Desktop"
                var successCount = 0

                selectedUrls.forEach { url ->
                    val book = state.books.find { it.url == url }
                    if (book != null) {
                        val safeTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val destinationZipPath = "$desktopPath/$safeTitle.zip"

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
                            destinationZipPath = destinationZipPath,
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
                sendEffect(BookshelfUiEffect.ShowToast("成功将 $successCount 本书打包导出至桌面！"))

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
    val isSelectionMode: Boolean = false,    // 是否处于多选管理模式 (如批量删除/下载)
    val selectedBooks: Set<String> = emptySet(), // 当前选中的书本 URLs (用于批量操作)

    // 💡 核心新增：专门用于控制导出进度条的 UI 状态
    val isExporting: Boolean = false,     // 💡 是否正在导出（控制弹窗显示）
    val exportProgress: Float = 0f,       // 💡 进度比例：从 0.0f 到 1.0f
    val exportBookTitle: String = "",     // 💡 当前正在导出的书名
    val exportChapterProgress: String = "" // 💡 文本显示进度，如 "50 / 120 章"

) : UiState

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

    data object DownloadSelectedBooks : BookshelfUiIntent()

    // 💡 新增：批量将选中书籍打包导出为 ZIP 的意图
    data class ExportSelectedBooks(val onlyCached: Boolean,
                                   val concurrencyLimit: Int,
                                   val delayMs: Long) : BookshelfUiIntent()

    // 💡 2. 新增：用户点击取消导出的意图
    data object CancelExport : BookshelfUiIntent()

    // --- 批量与刷新操作 ---
    /** 用户下拉刷新：触发所有书本的后台更新检测 */
    data object RefreshBooks : BookshelfUiIntent()

    /** 开启/关闭多选编辑模式 */
    data object ToggleSelectionMode : BookshelfUiIntent()

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

    /** 💡 导航跳转：空降直达阅读器进行断点续读！*/
    data class NavigateToReader(
        val bookUrl: String,
        val ruleId: String,
        val bookTitle: String,
        // 这里传 -1，让阅读器自行去库里捞精确断点进度
        val chapterIndex: Int = -1
    ) : BookshelfUiEffect()
}