package com.zhhz.spider.constant

import androidx.annotation.IntDef

@Suppress("ConstPropertyName")
object BookType {
    /**
     * 2 文本
     */
    const val text = 0b10

    /**
     * 4 图片
     */
    const val image = 0b100

    /**
     * 8 文本
     */
    const val local = 0b1000

    /**
     * 16 更新失败
     */
    const val updateError = 0b10000

    /**
     * 32 未正式加入到书架的临时阅读书籍
     */
    const val notShelf = 0b100000

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(text, image, local, updateError, notShelf)
    annotation class Type

    const val allBookType = text or image

    const val allBookTypeLocal = text or image or local

}