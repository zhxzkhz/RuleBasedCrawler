package com.zhhz.spider.ui.widget.novel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable

@Serializable
data class ReadBookConfig(
    val textColorArgb: Int = Color.Black.toArgb(),
    val background: Int = Color.White.toArgb(),
    val textSize: Int = 24,
    //字间距
    val letterSpacing: Float = 0.15f,
    //边距
    val margin: Float = 40f,
    //行间距
    val lineSpacing: Int = 10,
    //行间距倍率
    val lineSpacingRatio: Float = 1.1f,
    //段落间距
    val paragraphSpacing: Int = 5,
    //首航缩进，默认true
    val firstLineIndentation: Boolean = true,
    //两端对齐，默认true
    val isJustify: Boolean = true,
    //保留空白行，默认false
    val preserveEmptyLine: Boolean = true,
    //忽略每页首行空白行
    val ignoreFirstEmptyLine: Boolean = true,
    //忽略每页尾行空白行
    val ignoreLastEmptyLine: Boolean = true,
)
