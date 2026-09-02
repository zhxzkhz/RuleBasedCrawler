package com.zhhz.spider.ui.widget.novel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val illustrationCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
private val cacheOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
private const val MAX_CACHE = 30

@Composable
actual fun rememberIllustrations(urls: List<String>): Map<String, ImageBitmap> {
    val context = LocalPlatformContext.current
    var bitmaps by remember(urls) {
        mutableStateOf(
            urls.mapNotNull { url -> illustrationCache[url]?.let { url to it } }.toMap()
        )
    }

    LaunchedEffect(urls) {
        val missing = urls.filter { !illustrationCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                missing.distinct().forEach { url ->
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
                    }
                }
            }
            while (cacheOrder.size > MAX_CACHE) {
                val oldest = cacheOrder.poll() ?: break
                illustrationCache.remove(oldest)
            }
            bitmaps = urls.mapNotNull { url -> illustrationCache[url]?.let { url to it } }.toMap()
        }
    }
    return bitmaps
}