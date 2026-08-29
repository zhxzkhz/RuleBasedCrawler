package com.zhhz.spider.ui.widget.novel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect as ComposeRect

class MutableRect(
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float
) {
    // 从各个维度初始化
    constructor(position: Offset, size: Size) : this(
        position.x,
        position.y,
        position.x + size.width,
        position.y + size.height
    )

    // 转换为 Compose 的不可变 Rect
    fun toComposeRect(): ComposeRect = ComposeRect(left, top, right, bottom)

    // 宽度
    val width: Float get() = right - left

    // 高度
    val height: Float get() = bottom - top

    // 中心点
    val center: Offset get() = Offset(left + width / 2, top + height / 2)

    // 大小
    val size: Size get() = Size(width, height)

    // 是否包含点
    fun contains(point: Offset): Boolean =
        point.x in left..right && point.y in top..bottom

    // 是否与另一个矩形相交
    fun intersects(other: MutableRect): Boolean =
        !(right < other.left ||
                left > other.right ||
                bottom < other.top ||
                top > other.bottom)

    // 移动矩形
    fun offset(dx: Float, dy: Float): MutableRect {
        left += dx
        right += dx
        top += dy
        bottom += dy
        return this
    }

    // 缩放矩形
    fun scale(scaleX: Float, scaleY: Float): MutableRect {
        val newWidth = width * scaleX
        val newHeight = height * scaleY
        val center = center
        left = center.x - newWidth / 2
        right = center.x + newWidth / 2
        top = center.y - newHeight / 2
        bottom = center.y + newHeight / 2
        return this
    }

    // 合并两个矩形
    fun union(other: MutableRect): MutableRect {
        left = minOf(left, other.left)
        top = minOf(top, other.top)
        right = maxOf(right, other.right)
        bottom = maxOf(bottom, other.bottom)
        return this
    }

    override fun toString(): String =
        "MutableRect(left=$left, top=$top, right=$right, bottom=$bottom)"
}
