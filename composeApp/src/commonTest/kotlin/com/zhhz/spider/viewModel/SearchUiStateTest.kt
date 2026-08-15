package com.zhhz.spider.viewModel

import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.network.SearchBookSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchUiStateTest {

    private val sharedBook = SearchBook(
        title = "同一本书",
        author = "作者",
        cover = "cover",
        type = 0,
        sources = listOf(
            SearchBookSource("rule-a", "规则 A", "url-a"),
            SearchBookSource("rule-b", "规则 B", "url-b")
        )
    )

    @Test
    fun selectedRuleFiltersBooksAndSources() {
        val state = SearchUiState(
            selectedRuleId = "rule-b",
            allSearchResults = listOf(sharedBook)
        )

        assertEquals(1, state.searchResults.size)
        assertEquals(listOf("rule-b"), state.searchResults.single().sources.map { it.ruleId })
    }

    @Test
    fun allRulesRestoresCompleteCachedResults() {
        val state = SearchUiState(
            selectedRuleId = null,
            allSearchResults = listOf(sharedBook)
        )

        assertEquals(listOf("rule-a", "rule-b"), state.searchResults.single().sources.map { it.ruleId })
    }

    @Test
    fun preciseSearchMatchesTitleOrAuthorIgnoringCase() {
        val titleMatch = sharedBook.copy(title = "Kotlin 实战", author = "作者甲")
        val authorMatch = sharedBook.copy(title = "其他书籍", author = "KOTLIN 团队")
        val unrelated = sharedBook.copy(title = "Compose 入门", author = "作者乙")
        val state = SearchUiState(
            keyword = " kotlin ",
            isPreciseSearch = true,
            allSearchResults = listOf(titleMatch, authorMatch, unrelated)
        )

        assertEquals(listOf(titleMatch, authorMatch), state.searchResults)
    }

    @Test
    fun preciseSearchCanCombineWithRuleFilter() {
        val matchingBook = sharedBook.copy(title = "目标书籍")
        val state = SearchUiState(
            keyword = "目标",
            selectedRuleId = "rule-b",
            isPreciseSearch = true,
            allSearchResults = listOf(matchingBook, sharedBook.copy(title = "其他书籍"))
        )

        assertEquals(1, state.searchResults.size)
        assertEquals(listOf("rule-b"), state.searchResults.single().sources.map { it.ruleId })
    }

    @Test
    fun disablingPreciseSearchRestoresAllCachedResults() {
        val results = listOf(sharedBook.copy(title = "匹配书籍"), sharedBook.copy(title = "其他书籍"))
        val state = SearchUiState(
            keyword = "匹配",
            isPreciseSearch = false,
            allSearchResults = results
        )

        assertEquals(results, state.searchResults)
    }

    @Test
    fun idSearchResultIsNotFilteredByNumericKeyword() {
        val result = sharedBook.copy(title = "通过详情页匹配的书")
        val state = SearchUiState(
            keyword = "123456",
            isPreciseSearch = true,
            isIdSearch = true,
            allSearchResults = listOf(result)
        )

        assertEquals(listOf(result), state.searchResults)
    }
}
