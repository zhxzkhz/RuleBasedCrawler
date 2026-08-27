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

/**
 * 提供一个"安全剪贴板"环境：
 * 桌面端 Windows 下系统剪贴板可能被其它进程占用导致读写抛异常，
 * 不处理会让应用崩溃退出；包装后自动重试并静默失败。
 * 平台不支持的场景（Android）原样透传。
 */
@Composable
expect fun WithSafeClipboard(content: @Composable () -> Unit)

/**
 * 音量键翻页：Android 上把音量键转发给阅读翻页回调（enabled 时拦截系统音量）。
 * 桌面端无操作。
 */
@Composable
expect fun VolumeKeyPageEffect(enabled: Boolean, onForward: () -> Unit, onBackward: () -> Unit)
