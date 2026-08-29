package com.zhhz.spider.ui.widget.novel

import androidx.compose.ui.unit.Dp

// 数据模型
sealed class NovelElement(
    var rect : MutableRect = MutableRect(0f,0f,0f,0f)
) {
    data class TextRun(
        var text: String,
        var lineBase: Float = 0f,
        var isSelect: Boolean = false
    ) : NovelElement()

    data class Illustration(
        var url: String,
        var description: String = "插图",
        var widthFraction: Float = 1f,
        var height: Dp? = null
    ) : NovelElement()
}
