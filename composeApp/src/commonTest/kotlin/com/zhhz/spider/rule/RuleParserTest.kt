package com.zhhz.spider.rule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleParserTest {

    @Test
    fun cssSelectorExtractsTextAndAttributes() {
        val html = """
            <html>
                <body>
                    <a class="book" href="/book/1">First Book</a>
                    <a class="book" href="/book/2">Second Book</a>
                </body>
            </html>
        """.trimIndent()

        val textSelector = Selector(
            steps = listOf(ParseStep(type = StepType.CSS, rule = "a.book", extractType = ExtractType.TEXT))
        )
        val hrefSelector = Selector(
            steps = listOf(ParseStep(type = StepType.CSS, rule = "a.book", extractType = ExtractType.ATTR, attr = "href"))
        )

        assertEquals(listOf("First Book", "Second Book"), RuleParser.parseList(html, textSelector, mutableMapOf()))
        assertEquals("/book/1", RuleParser.parseString(html, hrefSelector, mutableMapOf()))
    }

    @Test
    fun regexHonorsListFlag() {
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.REGEX, rule = """chapter-(\d+)""", isList = true))
        )

        assertEquals(listOf("1", "2", "3"), RuleParser.parseList("chapter-1 chapter-2 chapter-3", selector, mutableMapOf()))
    }

    @Test
    fun jsonPathExtractsValue() {
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.JSON, rule = "$.book.title"))
        )

        assertEquals("Rule Book", RuleParser.parseString("""{"book":{"title":"Rule Book"}}""", selector, mutableMapOf()))
    }

    @Test
    fun templateResolvesInputAndContextVariables() {
        val ctx = mutableMapOf("page" to "3", "source" to "alpha")
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.TEMPLATE, rule = "search/{{key}}?page={{page}}&source={{source}}"))
        )

        assertEquals("search/keyword?page=3&source=alpha", RuleParser.parseString("keyword", selector, ctx))
    }

    @Test
    fun templateResolvesWhitespaceAndCtxPrefixedVariables() {
        val ctx = mutableMapOf("page" to "3", "source" to "alpha")
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.TEMPLATE, rule = "search/{{ key }}?page={{ ctx.page }}&source={{ source }}"))
        )

        assertEquals("search/keyword?page=3&source=alpha", RuleParser.parseString("keyword", selector, ctx))
    }

    @Test
    fun templateCanQueryCurrentAndRootHtml() {
        val html = """
            <section>
                <h1 class="root-title">Root Title</h1>
                <article><a class="detail" href="/book/1">Item Title</a></article>
            </section>
        """.trimIndent()
        val selector = Selector(
            steps = listOf(
                ParseStep(type = StepType.CSS, rule = "article", extractType = ExtractType.ELEMENT),
                ParseStep(type = StepType.TEMPLATE, rule = "{{css:a.detail}}|{{root.css:.root-title}}|{{rootcss:.root-title}}")
            )
        )

        assertEquals("Item Title|Root Title|Root Title", RuleParser.parseString(html, selector, mutableMapOf()))
    }

    @Test
    fun templateCanQueryJsonWithExplicitJsonProtocol() {
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.TEMPLATE, rule = "{{json:$.book.title}}"))
        )

        assertEquals("Rule Book", RuleParser.parseString("""{"book":{"title":"Rule Book"}}""", selector, mutableMapOf()))
    }

    @Test
    fun fallbackRunsWhenPrimarySelectorIsEmpty() {
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.CSS, rule = ".missing", extractType = ExtractType.TEXT)),
            fallback = Selector(
                steps = listOf(ParseStep(type = StepType.CSS, rule = ".title", extractType = ExtractType.TEXT))
            )
        )

        assertEquals("Fallback Title", RuleParser.parseString("""<h1 class="title">Fallback Title</h1>""", selector, mutableMapOf()))
    }

    @Test
    fun replaceDecodesEscapedReplacementText() {
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.REPLACE, rule = """<br\s*/?>""", replacement = "\\n"))
        )

        assertTrue(RuleParser.parseString("a<br>b", selector, mutableMapOf()).contains("\n"))
    }

    @Test
    fun replaceResolvesRegexFromContext() {
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.REPLACE, rule = "{{separator}}", replacement = "\\n"))
        )
        val ctx = mutableMapOf("separator" to """\s*\|\s*""")

        assertEquals("a\nb\nc", RuleParser.parseString("a | b | c", selector, ctx))
    }

    @Test
    fun templateKeepsUnknownVariablesUnchanged() {
        val selector = Selector(
            steps = listOf(ParseStep(type = StepType.TEMPLATE, rule = "{{known}}/{{ missing }}"))
        )
        val ctx = mutableMapOf("known" to "value")

        assertEquals("value/{{ missing }}", RuleParser.parseString("input", selector, ctx))
    }
}
