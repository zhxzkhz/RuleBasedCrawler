package com.zhhz.spider.ui.widget.novel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * 当前页插图位图状态：进入页面时从缓存取，缺失则异步加载并触发重组重绘。
 * 解码与缓存逻辑各平台有差异，通过 expect/actual 实现。
 */
@Composable
expect fun rememberIllustrations(urls: List<String>): Map<String, ImageBitmap>

/**
 * 在 Canvas 中绘制插图；未加载完成时画占位灰块。
 * 等比缩放居中。
 */
fun DrawScope.drawIllustration(
    url: String,
    topLeft: Offset,
    size: Size,
    bitmap: ImageBitmap?
) {
    if (bitmap != null) {
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstRatio = size.width / size.height
        var drawW = size.width
        var drawH = size.height
        if (srcRatio > dstRatio) {
            drawH = size.width / srcRatio
        } else {
            drawW = size.height * srcRatio
        }
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