package com.zhhz.spider.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.rule.toDomain
import com.zhhz.spider.ui.widget.SearchResultItem
import com.zhhz.spider.ui.widget.SearchTopBar
import com.zhhz.spider.ui.widget.SourceSelectorRow
import com.zhhz.spider.viewModel.SearchUiState
import com.zhhz.spider.viewModel.SearchViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
    onOpen: (Boolean) -> Unit,
    viewModel: SearchViewModel = koinViewModel() // 使用 Koin 获取 ViewModel
) {

    val uiState by viewModel.uiState.collectAsState()
    val availableRules by viewModel.rules.collectAsState()

    Scaffold(
        topBar = {
            SearchTopBar(
                keyword = viewModel.keyword,
                onKeywordChange = { viewModel.keyword = it },
                onBack = onBack,
                onSearch = { viewModel.executeSearch() }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {



            // 源选择
            SourceSelectorRow(
                rules = availableRules,
                selectedId = viewModel.selectedRule?.id,
                onOpen = onOpen,
                onSelect = { viewModel.selectedRule = it.toDomain() }
            )

            // 状态分发
            when (val state = uiState) {
                is SearchUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is SearchUiState.Error -> Text("错误: ${state.message}", color = Color.Red)
                is SearchUiState.Success -> {
                    LazyColumn {
                        items(state.books) { book ->
                            SearchResultItem(book) { onBookClick(book) }
                        }
                    }
                }
                SearchUiState.Idle -> { /* 显示搜索建议或空白 */ }
            }
        }
    }
}