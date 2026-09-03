package com.zhhz.spider.viewModel

import com.zhhz.spider.db.BookGroupEntity
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.network.Book
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState

// ==========================================
// 1. 书架状态 (UiState)
// ==========================================
data class BookshelfUiState(
    val books: List<Book> = emptyList(),
    val downloadTasks: Map<String, DownloadTask> = emptyMap(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshCompletedCount: Int = 0,
    val refreshTotalCount: Int = 0,
    val refreshBookTitle: String = "",
    val isSelectionMode: Boolean = false,
    val selectedBooks: Set<String> = emptySet(),
    val groups: List<BookGroupEntity> = emptyList(),
    val selectedGroupMask: Long = 0L,
    val showStartGroupDialog: Boolean = false,
    val startDefaultGroupMask: Long = 0L,
    val sortMode: String = "recent",
    val onlyUpdated: Boolean = false,
    val viewMode: String = "grid",
    val readFilter: String = "all",
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportBookTitle: String = "",
    val exportChapterProgress: String = "",
    val isImporting: Boolean = false,
    val updateUngroupedBooks: Boolean = true,
    private val _cachedFilteredBooks: List<Book>? = null,
    private val _filteredBooksCacheKey: Long = 0L
) : UiState {
    val filteredBooks: List<Book>
        get() {
            val currentCacheKey = computeFilteredBooksCacheKey()
            if (_cachedFilteredBooks != null && _filteredBooksCacheKey == currentCacheKey) {
                return _cachedFilteredBooks
            }
            return computeFilteredBooks()
        }

    private fun computeFilteredBooksCacheKey(): Long {
        var result = books.hashCode().toLong()
        result = 31 * result + selectedGroupMask
        result = 31 * result + onlyUpdated.hashCode()
        result = 31 * result + readFilter.hashCode()
        result = 31 * result + sortMode.hashCode()
        return result
    }

    private fun computeFilteredBooks(): List<Book> {
        var result = books.filter { it.inLibrary }
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

    fun copyWithFilteredBooksCache(
        books: List<Book> = this.books,
        selectedGroupMask: Long = this.selectedGroupMask,
        onlyUpdated: Boolean = this.onlyUpdated,
        readFilter: String = this.readFilter,
        sortMode: String = this.sortMode,
        downloadTasks: Map<String, DownloadTask> = this.downloadTasks,
        isLoading: Boolean = this.isLoading,
        isRefreshing: Boolean = this.isRefreshing,
        refreshCompletedCount: Int = this.refreshCompletedCount,
        refreshTotalCount: Int = this.refreshTotalCount,
        refreshBookTitle: String = this.refreshBookTitle,
        isSelectionMode: Boolean = this.isSelectionMode,
        selectedBooks: Set<String> = this.selectedBooks,
        groups: List<BookGroupEntity> = this.groups,
        showStartGroupDialog: Boolean = this.showStartGroupDialog,
        viewMode: String = this.viewMode,
        isExporting: Boolean = this.isExporting,
        exportProgress: Float = this.exportProgress,
        exportBookTitle: String = this.exportBookTitle,
        exportChapterProgress: String = this.exportChapterProgress,
        isImporting: Boolean = this.isImporting,
        updateUngroupedBooks: Boolean = this.updateUngroupedBooks
    ): BookshelfUiState {
        val newState = BookshelfUiState(
            books = books,
            downloadTasks = downloadTasks,
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            refreshCompletedCount = refreshCompletedCount,
            refreshTotalCount = refreshTotalCount,
            refreshBookTitle = refreshBookTitle,
            isSelectionMode = isSelectionMode,
            selectedBooks = selectedBooks,
            groups = groups,
            selectedGroupMask = selectedGroupMask,
            showStartGroupDialog = showStartGroupDialog,
            sortMode = sortMode,
            onlyUpdated = onlyUpdated,
            viewMode = viewMode,
            readFilter = readFilter,
            isExporting = isExporting,
            exportProgress = exportProgress,
            exportBookTitle = exportBookTitle,
            exportChapterProgress = exportChapterProgress,
            isImporting = isImporting,
            updateUngroupedBooks = updateUngroupedBooks
        )
        val cacheKey = newState.computeFilteredBooksCacheKey()
        val cachedBooks = newState.computeFilteredBooks()
        return newState.copy(
            _cachedFilteredBooks = cachedBooks,
            _filteredBooksCacheKey = cacheKey
        )
    }

    val historyBooks: List<Book>
        get() = books.sortedByDescending { it.updateTime }
}

/** 未读章节数（totalChapters 未知时返回 0） */
fun Book.unreadChapterCount(): Int =
    if (totalChapters > 0) (totalChapters - lastReadChapterIndex - 1).coerceAtLeast(0) else 0

/** 备份导入时分组数据的处理策略 */
enum class BookGroupImportMode {
    MERGE,
    MOVE_TO_EXISTING
}

// ==========================================
// 2. 用户意图 (UiIntent)
// ==========================================
sealed class BookshelfUiIntent : UiIntent {
    data object ObserveBooks : BookshelfUiIntent()
    data class BookClicked(val book: Book) : BookshelfUiIntent()
    data class DeleteBook(val book: Book) : BookshelfUiIntent()
    data class PinBook(val book: Book) : BookshelfUiIntent()
    data class StartDownload(val bookUrl: String, val ruleId: String, val bookTitle: String) : BookshelfUiIntent()
    data class PauseDownload(val bookUrl: String) : BookshelfUiIntent()
    data class CancelDownload(val bookUrl: String) : BookshelfUiIntent()
    data object ClearUnfinishedDownloads : BookshelfUiIntent()
    data class SaveDownloadSettings(val books: Int, val images: Int) : BookshelfUiIntent()
    data object DownloadSelectedBooks : BookshelfUiIntent()
    data class ExportSelectedAsJson(val includeProgress: Boolean = true) : BookshelfUiIntent()
    data class ImportBooks(
        val jsonContent: String,
        val groupMode: BookGroupImportMode = BookGroupImportMode.MERGE,
        val targetGroupId: Long? = null
    ) : BookshelfUiIntent()
    data class ExportSelectedBooks(
        val destinationDirectory: String,
        val onlyCached: Boolean,
        val concurrencyLimit: Int,
        val delayMs: Long
    ) : BookshelfUiIntent()
    data object CancelExport : BookshelfUiIntent()
    data object RefreshBooks : BookshelfUiIntent()
    data object ToggleSelectionMode : BookshelfUiIntent()
    data object ObserveGroups : BookshelfUiIntent()
    data class SelectGroup(val mask: Long) : BookshelfUiIntent()
    data class ChooseStartGroup(val mask: Long) : BookshelfUiIntent()
    data object DismissStartGroupDialog : BookshelfUiIntent()
    data object OpenStartGroupDialog : BookshelfUiIntent()
    data class SetStartDefaultGroupMask(val mask: Long) : BookshelfUiIntent()
    data class CreateGroup(val name: String, val isAutoUpdate: Boolean) : BookshelfUiIntent()
    data class UpdateGroup(val groupId: Long, val newName: String, val isAutoUpdate: Boolean) : BookshelfUiIntent()
    data class DeleteGroup(val groupId: Long) : BookshelfUiIntent()
    data class SetBooksInGroup(val urls: List<String>, val mask: Long, val checked: Boolean) : BookshelfUiIntent()
    data class RestoreToShelf(val bookUrl: String) : BookshelfUiIntent()
    data class PurgeHistoryBook(val bookUrl: String) : BookshelfUiIntent()
    data class RetryFailedChapters(val bookUrl: String) : BookshelfUiIntent()
    data class SetShelfSort(val mode: String) : BookshelfUiIntent()
    data class SetShelfViewMode(val mode: String) : BookshelfUiIntent()
    data object ToggleOnlyUpdated : BookshelfUiIntent()
    data class SetUpdateUngroupedBooks(val enabled: Boolean) : BookshelfUiIntent()
    data class SetReadFilter(val filter: String) : BookshelfUiIntent()
    data class ToggleSelectBook(val bookUrl: String) : BookshelfUiIntent()
    data object SelectAll : BookshelfUiIntent()
    data object DeleteSelectedBooks : BookshelfUiIntent()
}

// ==========================================
// 3. 副作用 (UiEffect)
// ==========================================
sealed class BookshelfUiEffect : UiEffect {
    data class ShowToast(val message: String) : BookshelfUiEffect()
    data class SaveBookshelfBackup(val fileName: String, val content: String) : BookshelfUiEffect()
    data class NavigateToDetail(val book: Book) : BookshelfUiEffect()
}
