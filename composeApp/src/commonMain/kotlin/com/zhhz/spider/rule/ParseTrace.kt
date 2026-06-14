package com.zhhz.spider.rule

enum class ParseTraceStatus {
    OK,
    EMPTY,
    ERROR,
    SKIPPED
}

data class ParseTraceEvent(
    val selectorName: String,
    val stepIndex: Int,
    val stepCount: Int,
    val type: StepType,
    val rule: String,
    val inputCount: Int,
    val outputCount: Int,
    val status: ParseTraceStatus,
    val message: String = ""
)

data class ParseTraceResult<T>(
    val value: T,
    val events: List<ParseTraceEvent>
)
