package com.zhhz.spider.ui.widget.novel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插图位图缓存（进程级）：url → ImageBitmap
 * 避免同一插图在翻页往返时反复加载解码。
 */
private val illustrationCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/** 缓存里在用的 URL，超出上限时清理最旧的（简单 FIFO 淘汰） */
private val cacheOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
private const val MAX_CACHE = 30

/**
 * 预加载一组插图 URL 到缓存。在 IO 线程执行解码。
 */
suspend fun preloadIllustrations(urls: List<String>, context: PlatformContext) {
    if (urls.isEmpty()) return
    withContext(Dispatchers.IO) {
        urls.distinct().forEach { url ->
            if (illustrationCache.containsKey(url)) return@forEach
            runCatching {
                val result = ImageLoader(context).execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false)
                        .build()
                )
                val image = result.image as? BitmapImage ?: return@forEach
                illustrationCache[url] = image.bitmap.asImageBitmap()
                cacheOrder.add(url)
                trimCache()
            }
        }
    }
}

private fun trimCache() {
    while (cacheOrder.size > MAX_CACHE) {
        val oldest = cacheOrder.poll() ?: break
        illustrationCache.remove(oldest)
    }
}

/**
 * 在 Canvas 中绘制插图；未加载完成时画占位灰块并触发异步加载。
 * 重复调用时使用 drawWithCache 内的重绘状态。
 */
fun DrawScope.drawIllustration(
    url: String,
    topLeft: Offset,
    size: Size,
    bitmap: ImageBitmap?
) {
    if (bitmap != null) {
        // 等比缩放居中绘制
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstRatio = size.width / size.height
        var drawW = size.width
        var drawH = size.height
        if (srcRatio > dstRatio) drawH = size.width / srcRatio else drawW = size.height * srcRatio
        val left = topLeft.x + (size.width - drawW) / 2
        val top = topLeft.y + (size.height - drawH) / 2
        drawImage(
            image = bitmap,
            dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
            srcSize = androidx.compose.ui.unit.IntSize(bitmap.width, bitmap.height)
        )
    } else {
        drawRect(
            color = Color(0xFFEEEEEE),
            topLeft = topLeft,
            size = size
        )
    }
}

/**
 * 当前页插图位图状态：进入页面时从缓存取，缺失则异步加载并触发重组重绘。
 */
@Composable
fun rememberIllustrations(urls: List<String>): Map<String, ImageBitmap> {
    val context = LocalPlatformContext.current
    var bitmaps by remember(urls) {
        mutableStateOf<Map<String, ImageBitmap>>(
            urls.mapNotNull { url ->
                illustrationCache[url]?.let { url to it }
            }.toMap()
        )
    }

    LaunchedEffect(urls) {
        val missing = urls.filter { !illustrationCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            preloadIllustrations(missing, context)
            // 加载完成后把新到的位图合并进状态
            bitmaps = urls.mapNotNull { url ->
                illustrationCache[url]?.let { url to it }
            }.toMap()
        }
    }
    return bitmaps
}
