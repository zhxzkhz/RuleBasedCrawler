package com.zhhz.spider.util

import coil3.Bitmap

/**
 * 💡 跨平台扩展：将原始图片字节数组（JPG/PNG）解码并转换成 Coil 3 专用的 KMP Bitmap！
 */
expect fun ByteArray.toCoilBitmap(): Bitmap

