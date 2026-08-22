package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.ExploreTab
import com.zhhz.spider.repository.ExploreRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.supportsExplore
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import kotlinx.coroutines.launch

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
            is ExploreUiIntent.SelectTab -> selectTab(intent.tab)
        }
    }

    private fun handleLoadRules() {
        viewModelScope.launch {
            try {
                val rules = ruleRepository.getEnabledRules().filter { it.supportsExplore() }
                updateState { copy(rules = rules, isLoadingRules = false) }
                if (rules.isNotEmpty()) {
                    selectRule(rules.first().id)
                }
            } catch (e: Exception) {
                updateState { copy(isLoadingRules = false) }
                sendEffect(ExploreUiEffect.ShowToast(e.message ?: "加载书源失败"))
            }
        }
    }

    private fun handleSelectRule(ruleId: String) = selectRule(ruleId)

    private fun selectRule(ruleId: String) {
        if (uiState.value.selectedRuleId == ruleId && uiState.value.tabs.isNotEmpty()) return

        updateState {
            copy(
                selectedRuleId = ruleId,
                tabs = emptyList(),
                selectedTabUrl = "",
                books = emptyList(),
                isLoadingTabs = true,
                isLoadingBooks = false
            )
        }

        viewModelScope.launch {
            try {
                val tabs = exploreRepository.fetchTabs(ruleId)
                check(tabs.isNotEmpty()) { "未解析到任何分类标签" }
                updateState { copy(tabs = tabs, isLoadingTabs = false) }
                // 默认加载第一个分类
                selectTab(tabs.first())
            } catch (e: Exception) {
                updateState { copy(isLoadingTabs = false, selectedTabUrl = "") }
                sendEffect(ExploreUiEffect.ShowToast(e.message ?: "加载分类失败"))
            }
        }
    }

    private fun selectTab(tab: ExploreTab) {
        val state = uiState.value
        if (state.selectedTabUrl == tab.url && state.books.isNotEmpty()) return

        updateState { copy(selectedTabUrl = tab.url, books = emptyList(), isLoadingBooks = true) }

        viewModelScope.launch {
            try {
                val books = exploreRepository.fetchBooks(state.selectedRuleId, tab.url)
                updateState { copy(books = books, isLoadingBooks = false) }
            } catch (e: Exception) {
                updateState { copy(isLoadingBooks = false) }
                sendEffect(ExploreUiEffect.ShowToast(e.message ?: "加载榜单失败"))
            }
        }
    }
}

data class ExploreUiState(
    // 支持发现/榜单的启用书源列表
    val rules: List<SourceRule> = emptyList(),
    val selectedRuleId: String = "",
    // 当前书源的分类/榜单标签
    val tabs: List<ExploreTab> = emptyList(),
    val selectedTabUrl: String = "",
    val books: List<Book> = emptyList(),
    val isLoadingRules: Boolean = true,
    val isLoadingTabs: Boolean = false,
    val isLoadingBooks: Boolean = false
) : UiState

sealed class ExploreUiIntent : UiIntent {
    data object LoadRules : ExploreUiIntent()
    data class SelectRule(val ruleId: String) : ExploreUiIntent()
    data class SelectTab(val tab: ExploreTab) : ExploreUiIntent()
}

sealed class ExploreUiEffect : UiEffect {
    data class ShowToast(val message: String) : ExploreUiEffect()
}
