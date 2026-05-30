package com.zhhz.spider

import kotlinx.serialization.Serializable

@Serializable
data class DetailRoute(
    val title: String,
    val author: String,
    val cover: String,
    val detailUrl: String,
    val ruleId: String
)

@Serializable
data class ReaderRoute(
    val bookUrl: String, // 书籍唯一 URL (主键)，用于查询本地数据库
    val chapterIndex: Int = -1, // 当前要阅读的章节Index
    val chapterTitle: String,
    val ruleId: String,       // 使用的书源 ID
)