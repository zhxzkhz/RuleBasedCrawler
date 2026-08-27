package com.zhhz.spider.rule

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplyExploreSortTest {

    @Test
    fun blankParamReturnsUrlUnchanged() {
        assertEquals("https://a/list", applyExploreSort("https://a/list", ""))
        assertEquals("https://a/list", applyExploreSort("https://a/list", "  "))
    }

    @Test
    fun appendsWithQuestionMarkWhenUrlHasNoQuery() {
        assertEquals("https://a/list?sort=hot", applyExploreSort("https://a/list", "?sort=hot"))
        assertEquals("https://a/list?sort=hot", applyExploreSort("https://a/list", "&sort=hot"))
    }

    @Test
    fun appendsWithAmpersandWhenUrlAlreadyHasQuery() {
        assertEquals("https://a/list?id=1&sort=hot", applyExploreSort("https://a/list?id=1", "?sort=hot"))
        assertEquals("https://a/list?id=1&sort=hot", applyExploreSort("https://a/list?id=1", "&sort=hot"))
    }
}
