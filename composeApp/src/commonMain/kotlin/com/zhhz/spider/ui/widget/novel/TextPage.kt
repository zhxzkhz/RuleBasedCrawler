package com.zhhz.spider.ui.widget.novel

class TextPage(
    var index: Int = 0,
    text: String = "加载中...",
    val textLines: MutableList<TextLine> = mutableListOf(),

) {

    var text: String = text
        get() {
            if (this.textLines.isEmpty()) return field
            return this.textLines.joinToString("\n"){ line ->
                line.text
            }
        }

    fun addTextLine(textLine: TextLine){
        textLines.add(textLine)
    }

}
