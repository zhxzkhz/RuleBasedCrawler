package com.zhhz.spider.rule

import kotlinx.serialization.Serializable

@Serializable
enum class ParseTraceStatus {
    OK,
    EMPTY,
    ERROR,
    SKIPPED
}

@Serializable
data class ParseTraceValue(
    val text: String,
    val truncated: Boolean = false
)

@Serializable
data class ParseTraceEvent(
    val selectorName: String,
    val stepIndex: Int,
    val stepCount: Int,
    val type: StepType,
    val rule: String,
    val inputCount: Int,
    val outputCount: Int,
    val status: ParseTraceStatus,
    val message: String = "",
    val inputValues: List<ParseTraceValue> = emptyList(),
    val outputValues: List<ParseTraceValue> = emptyList()
)

data class ParseTraceResult<T>(
    val value: T,
    val events: List<ParseTraceEvent>
)
