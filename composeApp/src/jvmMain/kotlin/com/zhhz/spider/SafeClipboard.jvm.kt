package com.zhhz.spider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 桌面端安全剪贴板实现。
 *
 * 背景：Windows 上系统剪贴板是全局独占资源，其它程序（输入法、截图工具、远程桌面等）
 * 短暂持有剪贴板时，AWT 的 setContents/getContents 会抛 IllegalStateException
 * （"cannot open clipboard"）。该异常会一路冒泡到 Compose 的未捕获异常弹窗，
 * 用户点 OK 后整个应用退出——复制失败不该有这么严重的后果。
 *
 * 处理：短暂退避重试 3 次；仍失败则记录日志并静默返回，保证应用继续运行。
 */
@Composable
actual fun WithSafeClipboard(content: @Composable () -> Unit) {
    val platform = LocalClipboardManager.current
    val safe = remember(platform) { SafeDesktopClipboardManager(platform) }
    CompositionLocalProvider(LocalClipboardManager provides safe, content = content)
}

private class SafeDesktopClipboardManager(
    private val delegate: ClipboardManager
) : ClipboardManager by delegate {

    override fun getText(): AnnotatedString? = try {
        delegate.getText()
    } catch (t: Throwable) {
        logger.warn(t) { "读取系统剪贴板失败（可能被其它程序占用），返回空内容" }
        null
    }

    override fun setText(annotation: AnnotatedString) {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                delegate.setText(annotation)
                return
            } catch (t: Throwable) {
                lastError = t
                // Windows 剪贴板占用通常是瞬时的，逐次加大间隔重试
                Thread.sleep(60L * (attempt + 1))
            }
        }
        logger.warn(lastError) { "写入系统剪贴板失败（已重试3次），已忽略以保证应用不中断" }
    }
}
