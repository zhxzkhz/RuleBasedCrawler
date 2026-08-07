package com.zhhz.spider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun isDesktopPlatform(): Boolean = true

actual fun ruleEditorServerAddress(): String? = null

@Composable
actual fun rememberRuleFileActions(
    onOpenResult: (Result<String>) -> Unit,
    onSaveResult: (Result<Unit>) -> Unit
): RuleFileActions = remember(onOpenResult, onSaveResult) {
    RuleFileActions(
        openJsonFile = {
            val chooser = jsonFileChooser()
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                onOpenResult(runCatching { chooser.selectedFile.readText() })
            }
        },
        saveJsonFile = { fileName, content ->
            val chooser = jsonFileChooser().apply { selectedFile = File(fileName) }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                onSaveResult(runCatching {
                    val file = chooser.selectedFile.let {
                        if (it.extension.equals("json", ignoreCase = true)) it else File(it.parentFile, "${it.name}.json")
                    }
                    file.writeText(content)
                })
            }
        }
    )
}

@Composable
actual fun rememberBookExportDirectoryAction(
    onResult: (Result<String>) -> Unit
): BookExportDirectoryAction = remember(onResult) {
    BookExportDirectoryAction {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择漫画 ZIP 导出目录"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            onResult(Result.success(chooser.selectedFile.absolutePath))
        }
    }
}

private fun jsonFileChooser() = JFileChooser().apply {
    dialogTitle = "爬虫规则 JSON"
    fileFilter = FileNameExtensionFilter("JSON 文件 (*.json)", "json")
    isAcceptAllFileFilterUsed = false
}


@Composable
actual fun AndroidRuleEditorServerEffect() = Unit

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
