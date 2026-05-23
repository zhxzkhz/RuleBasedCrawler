package com.zhhz.spider.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface EditorProvider {
    @Composable
    fun CodeEditor(code: String, onCodeChange: (String) -> Unit, modifier: Modifier = Modifier)
}