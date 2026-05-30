package com.zhhz.spider.util

import org.jetbrains.skia.Image
import coil3.Bitmap

actual fun ByteArray.toCoilBitmap(): Bitmap {
    return try {
        Bitmap.makeFromImage(Image.makeFromEncoded(this))
    } catch (e: Exception) {
        throw Exception("PC 图像解码失败: ${e.message}")
    }
}