package com.zhhz.spider.util

import org.jetbrains.skia.Image
import coil3.Bitmap
import com.zhhz.spider.rule.SCRIPT_ENGINE
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.skia.EncodedImageFormat
import javax.script.ScriptException
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

actual fun ByteArray.toCoilBitmap(): Bitmap {
    return try {
        Bitmap.makeFromImage(Image.makeFromEncoded(this))
    } catch (e: Exception) {
        throw Exception("PC 图像解码失败: ${e.message}")
    }
}


actual fun Bitmap.descrambleAndEncode(
    decryptRule: String,
    format: String,
    bindings: SimpleBindings
): ByteArray? {
    return try {
        var output: Bitmap = this

        if (decryptRule.isNotBlank()) {
            try {
                output =
                    JsExtensionClass.jsToJavaObject(SCRIPT_ENGINE.eval(decryptRule, bindings)) as Bitmap
            } catch (e: ScriptException) {
                val errorDetail = """
                JS执行失败！
                错误原因: ${e.message}
                错误行号: ${e.lineNumber}
                错误源码: ${e.columnNumber}
                错误堆栈: ${e.stackTrace.joinToString("\n")}
                """.lines().joinToString("\n") { it.trimStart() }
                logger.error { errorDetail }
            } catch (e: Exception) {
                // 捕获 Java 层的 NPE 或其他异常
                logger.error { e }
            }
        }


        val (imageFormat, quality) = when (format) {
            "webp" -> EncodedImageFormat.WEBP to 100
            "png" -> EncodedImageFormat.PNG to 100
            "gif" -> EncodedImageFormat.GIF to 100  // 支持 GIF
            "bmp" -> EncodedImageFormat.BMP to 100  // 支持 BMP
            "ico" -> EncodedImageFormat.ICO to 100  // 支持 ICO
            "heif" -> EncodedImageFormat.HEIF to 80 // 支持 HEIF
            else -> EncodedImageFormat.JPEG to 80   // 默认 JPEG
        }

        Image.makeFromBitmap(output).encodeToData(imageFormat, 100)!!.bytes
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}