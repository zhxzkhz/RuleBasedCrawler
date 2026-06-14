package com.zhhz.spider.util

import android.graphics.BitmapFactory
import android.os.Build
import coil3.Bitmap
import com.zhhz.spider.rule.JsEngineRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import javax.script.ScriptException
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger { }

actual fun ByteArray.toCoilBitmap(): Bitmap {
    // 1. 利用 Android 系统底层的 BitmapFactory 解码字节
    val androidBitmap = BitmapFactory.decodeByteArray(this, 0, this.size)
        ?: throw Exception("Android 图像解码失败")

    // 2. 将 Android 原生 Bitmap 包装转换为 Coil KMP Bitmap
    return androidBitmap
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
                    JsExtensionClass.jsToJavaObject(JsEngineRunner.eval(decryptRule, bindings)) as Bitmap
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
            "png" -> android.graphics.Bitmap.CompressFormat.PNG to 100
            "webp","bmp","ico","heif" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS to 100
            } else {
                android.graphics.Bitmap.CompressFormat.WEBP to 100
            }
            else -> android.graphics.Bitmap.CompressFormat.JPEG to 80   // 默认 JPEG
        }
        val bytes = ByteArrayOutputStream()
        output.compress(imageFormat, quality, bytes)
        bytes.toByteArray()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

