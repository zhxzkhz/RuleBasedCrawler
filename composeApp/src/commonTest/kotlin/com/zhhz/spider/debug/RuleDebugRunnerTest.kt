package com.zhhz.spider.debug

import com.zhhz.spider.rule.ExtractType
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.SearchPage
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.StepType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleDebugRunnerTest {

    @Test
    fun runLocalSearchReturnsOutputAndTraceEvents() {
        val rule = SourceRule(
            search = SearchPage(
                listSelector = css(".book-item", ExtractType.ELEMENT),
                nameSelector = css(".title"),
                authorSelector = css(".author"),
                coverSelector = css(".cover", ExtractType.ATTR, "src"),
                detailUrlSelector = css(".title", ExtractType.ATTR, "href")
            )
        )
        val html = """
            <div class="book-item">
                <a class="title" href="/book/1">Debug Book</a>
                <span class="author">Tester</span>
                <img class="cover" src="/cover.jpg"/>
            </div>
        """.trimIndent()

        val result = RuleDebugRunner.runLocal(2, html, rule, mutableMapOf())

        assertTrue(result.output.contains("Debug Book"))
        assertTrue(result.output.contains("/book/1"))
        assertEquals(
            listOf(
                "SearchPage.listSelector",
                "SearchPage.nameSelector",
                "SearchPage.authorSelector",
                "SearchPage.coverSelector",
                "SearchPage.detailUrlSelector"
            ),
            result.traces.map { it.selectorName }
        )
        assertTrue(result.traces.all { it.outputCount > 0 })
    }

    private fun css(
        rule: String,
        extractType: ExtractType = ExtractType.TEXT,
        attr: String? = null
    ): Selector {
        return Selector(steps = listOf(ParseStep(type = StepType.CSS, rule = rule, extractType = extractType, attr = attr)))
    }
}
