package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.ExploreTab
import com.zhhz.spider.repository.ExploreRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.applyExploreSort
import com.zhhz.spider.rule.supportsExplore
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ExploreViewModel(
    private val exploreRepository: ExploreRepository,
    private val ruleRepository: RuleRepository
) : BaseViewModel<ExploreUiState, ExploreUiIntent, ExploreUiEffect>(
    initialState = ExploreUiState()
) {

    init {
        processIntent(ExploreUiIntent.LoadRules)
    }

    override fun handleIntent(intent: ExploreUiIntent) {
        when (intent) {
            is ExploreUiIntent.LoadRules -> handleLoadRules()
            is ExploreUiIntent.SelectRule -> selectRule(intent.ruleId)
            is ExploreUiIntent.SelectTab -> selectTabInternal(uiState.value.selectedRuleId, intent.tab)
            is ExploreUiIntent.SelectSort -> handleSelectSort(intent.sortParam)
            is ExploreUiIntent.LoadMore -> handleLoadMore()
        }
    }

    private fun handleLoadRules() {
        viewModelScope.launch {
            try {
                val rules = ruleRepository.getEnabledRules().filter { it.supportsExplore() }
                // 💡 只加载书源列表，不自动选中/请求任何书源的分类；
                // 用户点击展开某书源时才按需发起请求
                updateState { copy(rules = rules, isLoadingRules = false) }
            } catch (e: Exception) {
                updateState { copy(isLoadingRules = false) }
                sendEffect(ExploreUiEffect.ShowToast(e.message ?: "加载书源失败"))
            }
        }
    }

    private fun selectRule(ruleId: String) {
        if (uiState.value.selectedRuleId == ruleId) return
        updateState { copy(selectedRuleId = ruleId) }

        // 💡 每个书源一份独立状态：之前打开过的源直接恢复它上次的分类与书籍列表，不重复请求
        if (uiState.value.sourceStates.containsKey(ruleId)) return

        updateState {
            copy(sourceStates = sourceStates + (ruleId to ExploreSourceState(isLoadingTabs = true)))
        }

        viewModelScope.launch {
            try {
                val tabs = exploreRepository.fetchTabs(ruleId)
                check(tabs.isNotEmpty()) { "未解析到任何分类标签" }
                applySourceState(ruleId) { it.copy(tabs = tabs, isLoadingTabs = false) }
                // 💡 分类加载完成后，自动加载第一个分类的书籍列表
                tabs.firstOrNull()?.let { selectTabInternal(ruleId, it) }
            } catch (e: Exception) {
                applySourceState(ruleId) {
                    it.copy(isLoadingTabs = false, errorMessage = e.message ?: "加载分类失败")
                }
                sendEffect(ExploreUiEffect.ShowToast(e.message ?: "加载分类失败"))
            }
        }
    }

    /** 用当前排序参数拼出实际请求地址 */
    private fun buildUrl(state: ExploreSourceState): String =
        applyExploreSort(state.selectedTabUrl, state.selectedSortParam)

    private fun selectTabInternal(ruleId: String, tab: ExploreTab) {
        val state = uiState.value.sourceStates[ruleId] ?: return
        if (state.selectedTabUrl == tab.url && (state.books.isNotEmpty() || state.isLoadingBooks)) return

        // 切换分类时排序重置为默认
        applySourceState(ruleId) {
            it.copy(
                selectedTabUrl = tab.url,
                books = emptyList(),
                isLoadingBooks = true,
                errorMessage = null,
                page = 1,
                hasMore = true,
                selectedSortParam = ""
            )
        }

        loadBooksInternal(ruleId)
    }

    private fun handleSelectSort(sortParam: String) {
        val ruleId = uiState.value.selectedRuleId
        val state = uiState.value.sourceStates[ruleId] ?: return
        if (state.selectedSortParam == sortParam) return

        applySourceState(ruleId) {
            it.copy(
                selectedSortParam = sortParam,
                books = emptyList(),
                isLoadingBooks = true,
                errorMessage = null,
                page = 1,
                hasMore = true
            )
        }

        loadBooksInternal(ruleId)
    }

    private fun loadBooksInternal(ruleId: String) {
        val state = uiState.value.sourceStates[ruleId] ?: return
        val requestUrl = buildUrl(state)

        viewModelScope.launch {
            try {
                val books = exploreRepository.fetchBooks(ruleId, requestUrl, page = 1)
                // 用户已切换书源/分类/排序时，丢弃过期的响应
                if (uiState.value.selectedRuleId != ruleId) return@launch
                val latest = uiState.value.sourceStates[ruleId] ?: return@launch
                if (buildUrl(latest) != requestUrl) return@launch
                applySourceState(ruleId) {
                    it.copy(books = books, isLoadingBooks = false, hasMore = books.isNotEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (uiState.value.selectedRuleId != ruleId) return@launch
                applySourceState(ruleId) {
                    it.copy(isLoadingBooks = false, errorMessage = e.message ?: "加载榜单失败")
                }
            }
        }
    }

    /**
     * 榜单结果翻页。
     * 💡 是否还有更多的判断：新页去重后没有任何新书（含返回空列表、或不支持翻页的规则
     * 原样重复返回第一页两种情况），即判定已到底，不再继续请求。
     */
    private fun handleLoadMore() {
        val ruleId = uiState.value.selectedRuleId
        val state = uiState.value.sourceStates[ruleId] ?: return
        if (state.isLoadingBooks || !state.hasMore || state.books.isEmpty()) return

        val nextPage = state.page + 1
        val requestUrl = buildUrl(state)
        val knownUrls = state.books.mapTo(mutableSetOf()) { it.url }

        applySourceState(ruleId) { it.copy(isLoadingBooks = true) }

        viewModelScope.launch {
            try {
                val newBooks = exploreRepository.fetchBooks(ruleId, requestUrl, page = nextPage)
                if (uiState.value.selectedRuleId != ruleId) return@launch
                val latest = uiState.value.sourceStates[ruleId] ?: return@launch
                if (buildUrl(latest) != requestUrl) return@launch

                val freshBooks = newBooks.filter { it.url !in knownUrls }
                applySourceState(ruleId) {
                    it.copy(
                        books = it.books + freshBooks,
                        page = nextPage,
                        isLoadingBooks = false,
                        hasMore = freshBooks.isNotEmpty()
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (uiState.value.selectedRuleId != ruleId) return@launch
                applySourceState(ruleId) { it.copy(isLoadingBooks = false, hasMore = false) }
            }
        }
    }

    private fun applySourceState(ruleId: String, transform: (ExploreSourceState) -> ExploreSourceState) {
        updateState {
            val current = sourceStates[ruleId] ?: return@updateState this
            copy(sourceStates = sourceStates + (ruleId to transform(current)))
        }
    }
}

/** 单个书源的发现页状态：分类、选中分类、书籍列表各自独立 */
data class ExploreSourceState(
    val tabs: List<ExploreTab> = emptyList(),
    val selectedTabUrl: String = "",
    val books: List<Book> = emptyList(),
    val isLoadingTabs: Boolean = false,
    val isLoadingBooks: Boolean = false,
    val errorMessage: String? = null,
    // 翻页状态：当前页码与是否还有更多
    val page: Int = 1,
    val hasMore: Boolean = true,
    // 当前排序参数（对应 explore.sorts 中某项的 param）
    val selectedSortParam: String = ""
)

data class ExploreUiState(
    // 支持发现/榜单的启用书源列表
    val rules: List<SourceRule> = emptyList(),
    val selectedRuleId: String = "",
    val isLoadingRules: Boolean = true,
    // Key 为 ruleId，每个书源独立维护自己的分类和列表
    val sourceStates: Map<String, ExploreSourceState> = emptyMap()
) : UiState {
    val currentState: ExploreSourceState? get() = sourceStates[selectedRuleId]
}

sealed class ExploreUiIntent : UiIntent {
    data object LoadRules : ExploreUiIntent()
    data class SelectRule(val ruleId: String) : ExploreUiIntent()
    data class SelectTab(val tab: ExploreTab) : ExploreUiIntent()
    data class SelectSort(val sortParam: String) : ExploreUiIntent()
    data object LoadMore : ExploreUiIntent()
}

sealed class ExploreUiEffect : UiEffect {
    data class ShowToast(val message: String) : ExploreUiEffect()
}
