package com.zhhz.spider.repository.impl

import com.zhhz.spider.model.CrawlerError
import com.zhhz.spider.model.CrawlerStage
import com.zhhz.spider.rule.SourceRule

internal fun String.throwIfCrawlerError(rule: SourceRule, stage: CrawlerStage) {
    if (!startsWith("ERROR:")) return

    val message = removePrefix("ERROR:").trim().ifBlank { "未知错误" }
    throw IllegalStateException(
        CrawlerError(
            sourceId = rule.id,
            sourceName = rule.name,
            stage = stage,
            message = message
        ).toUserMessage()
    )
}
