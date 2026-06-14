package com.zhhz.spider.repository.impl

import com.zhhz.spider.model.CrawlerStage
import com.zhhz.spider.rule.SourceRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CrawlerResultGuardTest {

    @Test
    fun throwIfCrawlerErrorDoesNothingForNormalResponse() {
        val rule = SourceRule(id = "rule-a", name = "测试源")

        "normal html".throwIfCrawlerError(rule, CrawlerStage.DETAIL)
    }

    @Test
    fun throwIfCrawlerErrorBuildsUserFacingMessage() {
        val rule = SourceRule(id = "rule-a", name = "测试源")

        val error = assertFailsWith<IllegalStateException> {
            "ERROR: HTTP 500 >> broken".throwIfCrawlerError(rule, CrawlerStage.CONTENT)
        }

        assertEquals("测试源 正文失败：HTTP 500 >> broken", error.message)
    }

    @Test
    fun throwIfCrawlerErrorFallsBackToSourceIdAndUnknownMessage() {
        val rule = SourceRule(id = "rule-a", name = "")

        val error = assertFailsWith<IllegalStateException> {
            "ERROR: ".throwIfCrawlerError(rule, CrawlerStage.CATALOG)
        }

        assertEquals("rule-a 目录失败：未知错误", error.message)
    }
}
