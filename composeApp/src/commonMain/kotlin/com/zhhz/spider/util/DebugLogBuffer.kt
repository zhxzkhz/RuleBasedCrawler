package com.zhhz.spider.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DebugLogBuffer {
    private const val MAX_LINES = 1_000

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun append(line: String) {
        _lines.update { current ->
            (current + line).takeLast(MAX_LINES)
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
