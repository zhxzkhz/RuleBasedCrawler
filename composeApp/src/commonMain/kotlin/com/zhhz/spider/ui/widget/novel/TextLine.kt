package com.zhhz.spider.ui.widget.novel

class TextLine(
    var text : String = "",
    var elements : ArrayList<NovelElement> = arrayListOf(),
    var height: Float = 0f,
    var extraHeight: Float = 0f,
    //是否标题
    var isTitle: Boolean = false,
    //是否段落结束
    var isParagraphEnd: Boolean = false
) {

    fun addElement(element: NovelElement){
        elements.add(element)
    }

    override fun toString(): String {
        return text
    }

}
