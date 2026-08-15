package com.zhhz.spider.rule

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceRuleCapabilityTest {
    @Test
    fun detailUrlSelectorEnablesIdSearch() {
        val rule = SourceRule(
            detail = DetailPage(
                urlSelector = Selector(
                    steps = listOf(ParseStep(type = StepType.TEMPLATE, rule = "/book/{{key}}"))
                )
            )
        )

        assertTrue(rule.supportsIdSearch())
    }

    @Test
    fun emptyDetailUrlSelectorDoesNotEnableIdSearch() {
        assertFalse(SourceRule().supportsIdSearch())
    }
}
