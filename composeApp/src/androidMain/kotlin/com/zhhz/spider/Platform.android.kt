package com.zhhz.spider

import android.os.Build
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhhz.spider.remote.AndroidRuleEditorServer
import fi.iki.elonen.NanoHTTPD
import org.koin.compose.koinInject
import java.net.Inet4Address
import java.net.NetworkInterface

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun isDesktopPlatform(): Boolean = false

actual fun ruleEditorServerAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress
        ?.let { "http://$it:8765" }
}.getOrNull()

@Composable
actual fun rememberRuleFileActions(
    onOpenResult: (Result<String>) -> Unit,
    onSaveResult: (Result<Unit>) -> Unit
): RuleFileActions {
    val context = LocalContext.current
    val currentOpenResult by rememberUpdatedState(onOpenResult)
    val currentSaveResult by rememberUpdatedState(onSaveResult)
    var pendingContent by remember { mutableStateOf("") }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentOpenResult(runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取所选文件")
            })
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            currentSaveResult(runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                    it.write(pendingContent)
                } ?: error("无法写入所选文件")
            })
        }
    }
    return remember(openLauncher, saveLauncher) {
        RuleFileActions(
            openJsonFile = { openLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
            saveJsonFile = { fileName, content ->
                pendingContent = content
                saveLauncher.launch(fileName)
            }
        )
    }
}

@Composable
actual fun rememberBookExportDirectoryAction(
    onResult: (Result<String>) -> Unit
): BookExportDirectoryAction {
    val context = LocalContext.current
    val currentResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            currentResult(Result.success(uri.toString()))
        }
    }
    return remember(launcher) { BookExportDirectoryAction { launcher.launch(null) } }
}

@Composable
actual fun AndroidRuleEditorServerEffect() {
    val server = koinInject<AndroidRuleEditorServer>()
    DisposableEffect(server) {
        runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            .onFailure { it.printStackTrace() }
        onDispose { server.stop() }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
