package com.zhhz.spider.viewModel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.RuleManager
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.RuleApi.runSearchLogic
import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.toDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val ruleDao: RuleDao,
    private val taskRunner: FetchTaskRunner,
    private val ruleManager: RuleManager,
    private val sessionManager: ContextSessionManager,
) : ViewModel() {

    // 1. 响应式状态
    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _rules = MutableStateFlow<List<RuleEntity>>(emptyList())
    val rules = _rules.asStateFlow()

    var selectedRule by mutableStateOf<SourceRule?>(null)
    var keyword by mutableStateOf("")

    init {
        // 启动时加载所有规则
        viewModelScope.launch {
            ruleDao.getAllRulesFlow().collect { list ->
                _rules.value = list
                if (selectedRule == null && list.isNotEmpty()) {
                    selectedRule = list.first().toDomain()
                    selectedRule?.let { ruleManager.updateActiveRule(it) }
                }
            }
        }
    }

    // 2. 搜索行为
    fun executeSearch() {
        val rule = selectedRule ?: return
        if (keyword.isBlank()) return

        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            try {
                val results = runSearchLogic(taskRunner,rule, keyword)
                _uiState.value = SearchUiState.Success(results)
            } catch (e: Exception) {
                _uiState.value = SearchUiState.Error(e.message ?: "搜索失败")
            }
        }
    }

    fun onBookSelected(book: SearchBook) {
        // 此时 runtimeCtx 可能包含搜索页特有的 Token
        //sessionManager.updateContext(book.detailUrl, )
    }

}

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data class Success(val books: List<SearchBook>) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}