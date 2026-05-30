package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.network.toRoute
import com.zhhz.spider.repository.SearchRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import okhttp3.Route

class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val sessionRepository: SessionRepository
) : BaseViewModel<SearchUiState, SearchUiIntent, SearchUiEffect>(
    initialState = SearchUiState()
) {

    // 保存当前的搜索任务，方便随时取消（防抖设计）
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null

    // 唯一的意图处理分发中心，严格统一！
    override fun handleIntent(intent: SearchUiIntent) {
        when (intent) {
            is SearchUiIntent.UpdateKeyword -> handleUpdateKeyword(intent.keyword)
            is SearchUiIntent.ExecuteSearch -> handleExecuteSearch()
            is SearchUiIntent.LoadMore -> handleLoadMore()
            is SearchUiIntent.AddToBookshelf -> handleAddToBookshelf(intent.book)
            is SearchUiIntent.BookClicked -> handleBookClicked(intent.book)
        }
    }

    private fun handleUpdateKeyword(keyword: String) {
        // 使用 updateState 原子更新输入框状态
        updateState { copy(keyword = keyword) }
    }

    // 逻辑一：全新搜索
    private fun handleExecuteSearch() {
        val currentKeyword = uiState.value.keyword
        if (currentKeyword.isBlank()) {
            sendEffect(SearchUiEffect.ShowToast("搜索关键字不能为空"))
            return
        }

        // 1. 如果上一次搜索还没完，强制中断它！
        searchJob?.cancel()

        // 2. 统一初始化状态
        updateState {
            copy(isLoading = true, isSearchOngoing = true, page = 1, hasMore = true, searchResults = emptyList())
        }

        searchJob = viewModelScope.launch {
            // 3. 收集持续不断的数据流
            searchRepository.fetchData(currentKeyword, 1)
                .catch { e ->
                    // 处理流发生崩溃的情况
                    updateState { copy(isLoading = false, isSearchOngoing = false) }
                    sendEffect(SearchUiEffect.ShowToast(e.message ?: "搜索失败"))
                }
                .collect { streamingResults ->
                    // 只要有任意一个书源的数据过来了，立刻更新 UI！
                    updateState {
                        copy(
                            searchResults = streamingResults,
                            isLoading = false, // 只要第一波数据到了，就关掉居中的 Loading 圈
                            hasMore = streamingResults.isNotEmpty()
                        )
                    }
                }

            // 4. (可选) 如果整个流收集完了（所有并发书源都跑完了），确保 loading 关闭
            updateState { copy(isLoading = false, isSearchOngoing = false) }

            if (uiState.value.searchResults.isEmpty()) {
                sendEffect(SearchUiEffect.ShowToast("未搜索到结果"))
            }
        }
    }

    // 逻辑二：加载更多
    private fun handleLoadMore() {
        val state = uiState.value
        if (state.isLoading || state.isLoadMore || !state.hasMore || state.keyword.isBlank()) return

        val nextPage = state.page + 1
        updateState { copy(isLoadMore = true) }

        // 重点：冻结住第一页已有的数据
        val currentList = state.searchResults

        loadMoreJob = viewModelScope.launch {
            searchRepository.fetchData(state.keyword, nextPage)
                .catch { e ->
                    updateState { copy(isLoadMore = false) }
                    sendEffect(SearchUiEffect.ShowToast("加载失败: ${e.message}"))
                }
                .collect { newStreamingResults ->
                    updateState {
                        copy(
                            // 拼装：第一页老数据 + 正在流式增长的第二页新数据
                            searchResults = currentList + newStreamingResults,
                            isLoadMore = false, // 关掉底部的加载圈
                            page = nextPage,
                            hasMore = newStreamingResults.isNotEmpty()
                        )
                    }
                }
            updateState { copy(isLoadMore = false) }
        }
    }

    private fun handleAddToBookshelf(book: Book) {
        viewModelScope.launch {
            try {
                searchRepository.saveData(book)
                sendEffect(SearchUiEffect.ShowToast("成功加入书架"))
            } catch (e: Exception) {
                sendEffect(SearchUiEffect.ShowToast("加入书架失败"))
            }
        }
    }

    private fun handleBookClicked(book: SearchBook) {
        val defaultSource = book.sources.firstOrNull()
        if (defaultSource != null) {
            val domainBook = Book(
                title = book.title,
                author = book.author,
                cover = book.cover,
                url = defaultSource.url,
                ruleId = defaultSource.ruleId,
                lastReadChapterIndex = 0,
                // 💡 核心修改：将所有的备用书源，完好无损地装入 Book 实体中！
                availableSources = book.sources
            )
            viewModelScope.launch {
                // 逻辑正常，发送跳转 Effect 给 UI
                sessionRepository.saveData(domainBook)
                sendEffect(SearchUiEffect.NavigateToDetail(book.toRoute()))
            }
        } else {
            // 数据异常，发送 Toast Effect 给 UI
            sendEffect(SearchUiEffect.ShowToast("该书源链接无效"))
        }
    }
}

// 统一的状态数据类
data class SearchUiState(
    // 1. 用户输入的搜索关键字
    val keyword: String = "",

    // 2. 搜索结果集（现在使用的是聚合后的 SearchBook，内部包含多个书源）
    val searchResults: List<SearchBook> = emptyList(),

    // 3. UI 状态：首屏/重新搜索时的居中 Loading 圈
    val isLoading: Boolean = false,

    // 4. UI 状态：上拉触底时的底部加载更多 Loading 圈
    val isLoadMore: Boolean = false,

    // 5. 顶部横向进度条：表示后台的流式并发请求仍在进行中（哪怕此时屏幕上已经有部分结果了）
    val isSearchOngoing: Boolean = false,

    // 6. 分页参数：当前成功加载到的页码
    val page: Int = 1,

    // 7. 分页参数：是否还有更多数据（决定滑到底部时是否触发加载更多）
    val hasMore: Boolean = true
) : UiState


// 统一的意图密封类
sealed class SearchUiIntent : UiIntent {
    data class UpdateKeyword(val keyword: String) : SearchUiIntent()
    data object ExecuteSearch : SearchUiIntent()
    data object LoadMore : SearchUiIntent()
    data class AddToBookshelf(val book: Book) : SearchUiIntent()

    data class BookClicked(val book: SearchBook) : SearchUiIntent()
}

// 统一的副作用密封类（用于页面跳转、Toast等）
sealed class SearchUiEffect : UiEffect {
    data class ShowToast(val message: String) : SearchUiEffect()
    // 页面跳转逻辑建议由 Effect 抛给 UI 层，让 Navigation 集中处理
    data class NavigateToDetail(val route: DetailRoute) : SearchUiEffect()
}
