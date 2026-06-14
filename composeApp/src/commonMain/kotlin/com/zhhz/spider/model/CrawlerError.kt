package com.zhhz.spider.model

enum class CrawlerStage(val label: String) {
    SEARCH("搜索"),
    DETAIL("详情"),
    CATALOG("目录"),
    CONTENT("正文"),
    NETWORK("网络"),
    PARSE("解析")
}

data class CrawlerError(
    val sourceId: String,
    val sourceName: String,
    val stage: CrawlerStage,
    val message: String
) {
    fun toUserMessage(): String {
        val displayName = sourceName.ifBlank { sourceId }
        return "$displayName ${stage.label}失败：$message"
    }
}
