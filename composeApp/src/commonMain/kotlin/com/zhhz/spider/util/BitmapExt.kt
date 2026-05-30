package com.zhhz.spider.util

import coil3.Bitmap
import javax.script.SimpleBindings

/**
 * 💡 跨平台扩展：将原始图片字节数组（JPG/PNG）解码并转换成 Coil 3 专用的 KMP Bitmap！
 */
expect fun ByteArray.toCoilBitmap(): Bitmap

expect fun Bitmap.descrambleAndEncode(
    decryptRule: String,
    format: String,
    bindings: SimpleBindings
): ByteArray?