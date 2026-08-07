package com.zhhz.spider

import androidx.compose.runtime.Composable

interface Platform {
    val name: String
}

data class RuleFileActions(
    val openJsonFile: () -> Unit,
    val saveJsonFile: (fileName: String, content: String) -> Unit
)

data class BookExportDirectoryAction(val chooseDirectory: () -> Unit)

expect fun getPlatform(): Platform

expect fun isDesktopPlatform(): Boolean

expect fun ruleEditorServerAddress(): String?

@Composable
expect fun rememberRuleFileActions(
    onOpenResult: (Result<String>) -> Unit,
    onSaveResult: (Result<Unit>) -> Unit
): RuleFileActions

@Composable
expect fun rememberBookExportDirectoryAction(
    onResult: (Result<String>) -> Unit
): BookExportDirectoryAction

@Composable
expect fun AndroidRuleEditorServerEffect()

@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
