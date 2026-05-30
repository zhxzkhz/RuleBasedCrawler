package com.zhhz.spider.util

import android.graphics.BitmapFactory
import coil3.Bitmap


actual fun ByteArray.toCoilBitmap(): Bitmap {
    // 1. 利用 Android 系统底层的 BitmapFactory 解码字节
    val androidBitmap = BitmapFactory.decodeByteArray(this, 0, this.size)
        ?: throw Exception("Android 图像解码失败")

    // 2. 将 Android 原生 Bitmap 包装转换为 Coil KMP Bitmap
    return androidBitmap
}