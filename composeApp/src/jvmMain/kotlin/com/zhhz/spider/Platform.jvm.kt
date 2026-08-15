package com.zhhz.spider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.io.FilenameFilter
import java.util.Base64
import javax.swing.JFileChooser

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
            chooseJsonFile(
                title = "选择爬虫规则 JSON",
                mode = FileDialog.LOAD
            )?.let { file ->
                onOpenResult(runCatching { file.readText() })
            }
        },
        saveJsonFile = { fileName, content ->
            chooseJsonFile(
                title = "导出爬虫规则 JSON",
                mode = FileDialog.SAVE,
                suggestedName = fileName
            )?.let { selectedFile ->
                onSaveResult(runCatching {
                    val file = selectedFile.let {
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

private fun chooseJsonFile(
    title: String,
    mode: Int,
    suggestedName: String? = null
): File? = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    when (val result = chooseWindowsJsonFile(title, mode, suggestedName)) {
        is WindowsFileDialogResult.Selected -> result.file
        WindowsFileDialogResult.Cancelled -> null
        WindowsFileDialogResult.Unavailable -> chooseAwtJsonFile(title, mode, suggestedName)
    }
} else {
    chooseAwtJsonFile(title, mode, suggestedName)
}

private fun chooseWindowsJsonFile(
    title: String,
    mode: Int,
    suggestedName: String?
): WindowsFileDialogResult = runCatching {
    val script = """
        try {
            Add-Type -AssemblyName System.Windows.Forms
            ${'$'}dialog = if (${'$'}env:RULE_DIALOG_MODE -eq 'save') {
                New-Object System.Windows.Forms.SaveFileDialog
            } else {
                New-Object System.Windows.Forms.OpenFileDialog
            }
            ${'$'}dialog.AutoUpgradeEnabled = ${'$'}true
            ${'$'}dialog.Title = ${'$'}env:RULE_DIALOG_TITLE
            ${'$'}dialog.Filter = 'JSON 文件 (*.json)|*.json'
            ${'$'}dialog.DefaultExt = 'json'
            ${'$'}dialog.AddExtension = ${'$'}true
            if (${'$'}env:RULE_DIALOG_MODE -eq 'save') {
                ${'$'}dialog.FileName = ${'$'}env:RULE_DIALOG_FILE_NAME
                ${'$'}dialog.OverwritePrompt = ${'$'}true
            } else {
                ${'$'}dialog.CheckFileExists = ${'$'}true
                ${'$'}dialog.Multiselect = ${'$'}false
            }
            ${'$'}result = ${'$'}dialog.ShowDialog()
            if (${'$'}result -eq [System.Windows.Forms.DialogResult]::OK) {
                ${'$'}bytes = [Text.Encoding]::UTF8.GetBytes(${'$'}dialog.FileName)
                Write-Output ('SELECTED:' + [Convert]::ToBase64String(${'$'}bytes))
            } else {
                Write-Output 'CANCELLED'
            }
            ${'$'}dialog.Dispose()
        } catch {
            Write-Output 'UNAVAILABLE'
        }
    """.trimIndent()
    val process = ProcessBuilder(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-STA",
        "-WindowStyle",
        "Hidden",
        "-Command",
        script
    ).redirectErrorStream(true).apply {
        environment()["RULE_DIALOG_MODE"] = if (mode == FileDialog.SAVE) "save" else "open"
        environment()["RULE_DIALOG_TITLE"] = title
        environment()["RULE_DIALOG_FILE_NAME"] = suggestedName.orEmpty()
    }.start()

    val output = process.inputStream.bufferedReader().use { it.readLines() }
    val exitCode = process.waitFor()
    if (exitCode != 0) return@runCatching WindowsFileDialogResult.Unavailable

    val response = output.lastOrNull { line ->
        line == "CANCELLED" || line == "UNAVAILABLE" || line.startsWith("SELECTED:")
    } ?: return@runCatching WindowsFileDialogResult.Unavailable
    when {
        response == "CANCELLED" -> WindowsFileDialogResult.Cancelled
        response.startsWith("SELECTED:") -> {
            val path = String(
                Base64.getDecoder().decode(response.removePrefix("SELECTED:")),
                Charsets.UTF_8
            )
            WindowsFileDialogResult.Selected(File(path))
        }
        else -> WindowsFileDialogResult.Unavailable
    }
}.getOrDefault(WindowsFileDialogResult.Unavailable)

private fun chooseAwtJsonFile(
    title: String,
    mode: Int,
    suggestedName: String?
): File? {
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    val dialog = when (owner) {
        is Frame -> FileDialog(owner, title, mode)
        is Dialog -> FileDialog(owner, title, mode)
        else -> FileDialog(null as Frame?, title, mode)
    }
    return dialog.useDialog {
        filenameFilter = FilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
        file = suggestedName ?: "*.json"
        isMultipleMode = false
        isVisible = true

        val selectedName = file ?: return@useDialog null
        File(directory ?: return@useDialog null, selectedName)
    }
}

private sealed interface WindowsFileDialogResult {
    data class Selected(val file: File) : WindowsFileDialogResult
    data object Cancelled : WindowsFileDialogResult
    data object Unavailable : WindowsFileDialogResult
}

private inline fun <T> FileDialog.useDialog(block: FileDialog.() -> T): T =
    try {
        block()
    } finally {
        dispose()
    }


@Composable
actual fun AndroidRuleEditorServerEffect() = Unit

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
