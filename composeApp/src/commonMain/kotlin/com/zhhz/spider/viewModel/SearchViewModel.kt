package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.network.SearchBookSource
import com.zhhz.spider.network.toRoute
import com.zhhz.spider.repository.DetailRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SearchRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.toDomain
import com.zhhz.spider.rule.supportsIdSearch
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val sessionRepository: SessionRepository,
    private val ruleRepository: RuleRepository,
    private val detailRepository: DetailRepository
) : BaseViewModel<SearchUiState, SearchUiIntent, SearchUiEffect>(
    initialState = SearchUiState()
) {

    // 保存当前的搜索任务，方便随时取消（防抖设计）
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            ruleRepository.loadData().collect { rules ->
                val availableRules = rules
                    .filter { it.isEnabled }
                    .map { entity ->
                        val rule = runCatching { entity.toDomain() }.getOrNull()
                        SearchRuleOption(
                            id = entity.id,
                            name = entity.name.ifBlank { entity.id },
                            type = rule?.type ?: 0,
                            supportsIdSearch = rule?.supportsIdSearch() == true
                        )
                    }
                updateState {
                    val validSelectedRuleId = selectedRuleId?.takeIf { selectedId ->
                        availableRules.any { it.id == selectedId }
                    }
                    val selectedSupportsIdSearch = availableRules
                        .firstOrNull { it.id == validSelectedRuleId }
                        ?.supportsIdSearch == true
                    copy(
                        availableRules = availableRules,
                        selectedRuleId = validSelectedRuleId,
                        isIdSearch = isIdSearch && selectedSupportsIdSearch
                    )
                }
            }
        }
    }

    // 唯一的意图处理分发中心，严格统一！
    override fun handleIntent(intent: SearchUiIntent) {
        when (intent) {
            is SearchUiIntent.UpdateKeyword -> handleUpdateKeyword(intent.keyword)
            is SearchUiIntent.SelectRule -> handleSelectRule(intent.ruleId)
            is SearchUiIntent.SetPreciseSearch -> updateState {
                copy(isPreciseSearch = intent.enabled, isIdSearch = if (intent.enabled) false else isIdSearch)
            }
            is SearchUiIntent.SetIdSearch -> handleSetIdSearch(intent.enabled)
            is SearchUiIntent.ExecuteSearch -> handleExecuteSearch()
            is SearchUiIntent.LoadMore -> handleLoadMore()
            is SearchUiIntent.AddToBookshelf -> handleAddToBookshelf(intent.book)
            is SearchUiIntent.BookClicked -> handleBookClicked(intent.book)
        }
    }

    private fun handleUpdateKeyword(keyword: String) {
        if (keyword == uiState.value.keyword) return
        searchJob?.cancel()
        loadMoreJob?.cancel()
        updateState {
            copy(
                keyword = keyword,
                allSearchResults = emptyList(),
                isLoading = false,
                isLoadMore = false,
                isSearchOngoing = false,
                page = 1,
                hasMore = true,
                searchedRuleId = null,
                hasSearched = false
            )
        }
    }

    private fun handleSelectRule(ruleId: String?) {
        val state = uiState.value
        if (ruleId == state.selectedRuleId) return
        if (ruleId != null && state.availableRules.none { it.id == ruleId }) return

        updateState {
            val supportsIdSearch = availableRules
                .firstOrNull { it.id == ruleId }
                ?.supportsIdSearch == true
            copy(
                selectedRuleId = ruleId,
                isIdSearch = isIdSearch && supportsIdSearch
            )
        }
    }

    private fun handleSetIdSearch(enabled: Boolean) {
        val state = uiState.value
        val canUseIdSearch = state.selectedRuleId != null && state.availableRules
            .firstOrNull { it.id == state.selectedRuleId }
            ?.supportsIdSearch == true
        if (enabled && !canUseIdSearch) {
            sendEffect(SearchUiEffect.ShowToast("请选择支持 ID 搜索的单个规则"))
            return
        }
        updateState {
            copy(
                isIdSearch = enabled,
                isPreciseSearch = if (enabled) false else isPreciseSearch
            )
        }
    }

    // 逻辑一：全新搜索
    private fun handleExecuteSearch() {
        val currentKeyword = uiState.value.keyword
        val selectedRuleId = uiState.value.selectedRuleId
        val useIdSearch = uiState.value.isIdSearch
        if (currentKeyword.isBlank()) {
            sendEffect(SearchUiEffect.ShowToast("搜索关键字不能为空"))
            return
        }

        // 1. 如果上一次搜索还没完，强制中断它！
        searchJob?.cancel()

        // 2. 统一初始化状态
        updateState {
            copy(
                isLoading = true,
                isSearchOngoing = true,
                page = 1,
                hasMore = true,
                allSearchResults = emptyList(),
                searchedRuleId = selectedRuleId,
                hasSearched = true
            )
        }

        searchJob = viewModelScope.launch {
            if (useIdSearch) {
                executeIdSearch(currentKeyword, selectedRuleId)
                return@launch
            }
            // 3. 收集持续不断的数据流
            searchRepository.fetchData(currentKeyword, 1, selectedRuleId)
                .catch { e ->
                    // 处理流发生崩溃的情况
                    updateState { copy(isLoading = false, isSearchOngoing = false) }
                    sendEffect(SearchUiEffect.ShowToast(e.message ?: "搜索失败"))
                }
                .collect { streamingResults ->
                    // 只要有任意一个书源的数据过来了，立刻更新 UI！
                    updateState {
                        copy(
                            allSearchResults = streamingResults,
                            isLoading = false, // 只要第一波数据到了，就关掉居中的 Loading 圈
                            hasMore = streamingResults.isNotEmpty()
                        )
                    }
                }

            // 4. (可选) 如果整个流收集完了（所有并发书源都跑完了），确保 loading 关闭
            updateState { copy(isLoading = false, isSearchOngoing = false) }

            if (uiState.value.allSearchResults.isEmpty()) {
                sendEffect(SearchUiEffect.ShowToast("未搜索到结果"))
            }
        }
    }

    private suspend fun executeIdSearch(keyword: String, ruleId: String?) {
        val rule = uiState.value.availableRules.firstOrNull { it.id == ruleId }
        if (rule == null || !rule.supportsIdSearch) {
            updateState { copy(isLoading = false, isSearchOngoing = false) }
            sendEffect(SearchUiEffect.ShowToast("当前规则不支持 ID 搜索"))
            return
        }

        try {
            val detail = detailRepository.fetchData(keyword, rule.id)
            check(detail.title.isNotBlank()) { "详情页未匹配到书籍" }
            val result = SearchBook(
                title = detail.title,
                author = detail.author,
                cover = detail.cover,
                type = rule.type,
                sources = listOf(
                    SearchBookSource(
                        ruleId = rule.id,
                        sourceName = rule.name,
                        url = keyword
                    )
                )
            )
            updateState {
                copy(
                    allSearchResults = listOf(result),
                    isLoading = false,
                    isSearchOngoing = false,
                    hasMore = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateState {
                copy(
                    allSearchResults = emptyList(),
                    isLoading = false,
                    isSearchOngoing = false,
                    hasMore = false
                )
            }
            sendEffect(SearchUiEffect.ShowToast(e.message ?: "ID 搜索失败"))
        }
    }

    // 逻辑二：加载更多
    private fun handleLoadMore() {
        val state = uiState.value
        if (state.isLoading || state.isLoadMore || !state.hasMore || state.keyword.isBlank()) return

        val nextPage = state.page + 1
        updateState { copy(isLoadMore = true) }

        // 重点：冻结住第一页已有的数据
        val currentList = state.allSearchResults

        loadMoreJob = viewModelScope.launch {
            searchRepository.fetchData(state.keyword, nextPage, state.searchedRuleId)
                .catch { e ->
                    updateState { copy(isLoadMore = false) }
                    sendEffect(SearchUiEffect.ShowToast("加载失败: ${e.message}"))
                }
                .collect { newStreamingResults ->
                    updateState {
                        copy(
                            // 拼装：第一页老数据 + 正在流式增长的第二页新数据
                            allSearchResults = currentList + newStreamingResults,
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

    // null 表示搜索全部启用规则，否则仅搜索指定规则
    val selectedRuleId: String? = null,

    val availableRules: List<SearchRuleOption> = emptyList(),

    // 仅显示书名或作者中包含当前搜索文本的结果
    val isPreciseSearch: Boolean = false,

    // 使用所选规则的详情页规则将输入作为 ID 直接匹配单本书
    val isIdSearch: Boolean = false,

    // 本次请求得到的完整结果，切换规则时保留，用于在内存中即时筛选
    val allSearchResults: List<SearchBook> = emptyList(),

    // 上一次实际发起请求时使用的规则；切换筛选项不会改变分页请求范围
    val searchedRuleId: String? = null,

    // 3. UI 状态：首屏/重新搜索时的居中 Loading 圈
    val isLoading: Boolean = false,

    // 4. UI 状态：上拉触底时的底部加载更多 Loading 圈
    val isLoadMore: Boolean = false,

    // 5. 顶部横向进度条：表示后台的流式并发请求仍在进行中（哪怕此时屏幕上已经有部分结果了）
    val isSearchOngoing: Boolean = false,

    // 6. 分页参数：当前成功加载到的页码
    val page: Int = 1,

    // 7. 分页参数：是否还有更多数据（决定滑到底部时是否触发加载更多）
    val hasMore: Boolean = true,

    // 区分“尚未搜索”和“搜索结果为空”，避免输入时过早显示空状态
    val hasSearched: Boolean = false
) : UiState {
    // 单规则筛选时同步裁剪来源，保证详情和加入书架使用的也是当前规则
    val searchResults: List<SearchBook>
        get() {
            val ruleFilteredResults = selectedRuleId?.let { ruleId ->
                allSearchResults.mapNotNull { book ->
                    val matchingSources = book.sources.filter { it.ruleId == ruleId }
                    book.takeIf { matchingSources.isNotEmpty() }?.copy(sources = matchingSources)
                }
            } ?: allSearchResults
            val searchText = keyword.trim()
            return if (isPreciseSearch && !isIdSearch && searchText.isNotEmpty()) {
                ruleFilteredResults.filter { book ->
                    book.title.contains(searchText, ignoreCase = true) ||
                        book.author.contains(searchText, ignoreCase = true)
                }
            } else {
                ruleFilteredResults
            }
        }
}

data class SearchRuleOption(
    val id: String,
    val name: String,
    val type: Int = 0,
    val supportsIdSearch: Boolean = false
)


// 统一的意图密封类
sealed class SearchUiIntent : UiIntent {
    data class UpdateKeyword(val keyword: String) : SearchUiIntent()
    data class SelectRule(val ruleId: String?) : SearchUiIntent()
    data class SetPreciseSearch(val enabled: Boolean) : SearchUiIntent()
    data class SetIdSearch(val enabled: Boolean) : SearchUiIntent()
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
