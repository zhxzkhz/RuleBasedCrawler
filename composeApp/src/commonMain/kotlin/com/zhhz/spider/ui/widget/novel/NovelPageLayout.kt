package com.zhhz.spider.ui.widget.novel

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints

internal data class NovelSafeDrawingInsets(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float
) {
    fun applyTo(constraints: Constraints): Constraints = Constraints(
        maxWidth = calculateNovelLayoutWidth(constraints.maxWidth, leftPx, rightPx),
        maxHeight = calculateNovelLayoutHeight(constraints.maxHeight, topPx, bottomPx)
    )
}

@Composable
internal fun novelSafeDrawingInsets(): NovelSafeDrawingInsets {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val padding = WindowInsets.safeDrawing.asPaddingValues()
    return NovelSafeDrawingInsets(
        leftPx = with(density) { padding.calculateLeftPadding(layoutDirection).toPx() },
        topPx = with(density) { padding.calculateTopPadding().toPx() },
        rightPx = with(density) { padding.calculateRightPadding(layoutDirection).toPx() },
        bottomPx = with(density) { padding.calculateBottomPadding().toPx() }
    )
}

internal fun calculateNovelLayoutWidth(
    windowWidth: Int,
    leftInsetPx: Float,
    rightInsetPx: Float
): Int = (windowWidth - leftInsetPx.toInt() - rightInsetPx.toInt()).coerceAtLeast(0)

internal fun calculateNovelLayoutHeight(
    windowHeight: Int,
    topInsetPx: Float,
    bottomInsetPx: Float
): Int = (windowHeight - topInsetPx.toInt() - bottomInsetPx.toInt()).coerceAtLeast(0)

internal fun novelPageRequiredHeight(
    usedHeight: Float,
    entryHeight: Float,
    removableTrailingSpace: Float
): Float = usedHeight + (entryHeight - removableTrailingSpace).coerceAtLeast(0f)

internal fun verticalJustificationIncrement(
    itemHeights: List<Float>,
    gapHeights: List<Float>,
    maxContentHeight: Float,
    justify: Boolean
): Float {
    if (!justify || itemHeights.size <= 1) return 0f
    require(gapHeights.size == itemHeights.size - 1)

    val remainingHeight = maxContentHeight - itemHeights.sum() - gapHeights.sum()
    return remainingHeight.coerceAtLeast(0f) / gapHeights.size
}
