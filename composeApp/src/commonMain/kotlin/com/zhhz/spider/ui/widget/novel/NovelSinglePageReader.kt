package com.zhhz.spider.ui.widget.novel

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 小说阅读器单页视图：用一个 Canvas 渲染当前页，通过 Animatable 实现左右滑动翻页。
 * 替代原本的 HorizontalPager：从根上消除"切章节时 Pager 卸载/挂载"的视觉跳变。
 *
 * 翻页手势：水平拖动。跟手时 translationX 随手指移动；松手时根据阈值与速度决定翻页或回弹。
 * 章节切换：翻到首/末页继续拖过阈值时调用 [onPrev] / [onNext]。
 *
 * 中央 75% 区域点击回调 [onContentTap]（弹菜单）由独立的 [detectTapGestures] 链路处理，
 * 不与翻页手势冲突。
 *
 * [renderPage] 同时承担"画当前页"和"画相邻页"两个职责：跟手翻页时 renderPage
 * 会被调用画 pageIndex ± 1。调用方约定 renderPage 只依赖 textPages[pageIndex]，
 * 不要从外层捕获"当前章节信息"。
 */
@Composable
fun NovelSinglePageReader(
    textPages: List<TextPage>,
    pageIndex: Int,
    onPageIndexChange: (Int) -> Unit,
    onContentTap: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    isFirstPage: () -> Boolean,
    isLastPage: () -> Boolean,
    pageAnimation: Boolean,
    renderPage: (DrawScope, pageIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (textPages.isEmpty()) return

    val scope = rememberCoroutineScope()
    val pageWidth = remember { mutableIntStateOf(0) }
    val pageOffset = remember { Animatable(0f) }
    val dragState = remember { DragState() }

    // 切章节或外部 pageIndex 变化时让 offset 复位（不动画）。
    LaunchedEffect(pageIndex) {
        pageOffset.snapTo(0f)
    }

    Box(
        modifier = modifier
            .onSizeChanged { pageWidth.intValue = it.width }
            .clipToBounds()
            .pointerInput(textPages.size, pageIndex) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragState.startX = offset.x
                        dragState.startPage = pageIndex
                        dragState.lastX = offset.x
                        dragState.moved = false
                    },
                    onDragEnd = {
                        val width = pageWidth.intValue.coerceAtLeast(1)
                        val threshold = width * 0.3f
                        val totalDelta = dragState.lastX - dragState.startX
                        if (abs(totalDelta) > threshold) {
                            when {
                                totalDelta < 0f && dragState.startPage < textPages.lastIndex -> {
                                    scope.launch {
                                        pageOffset.animateTo(-width.toFloat())
                                        pageOffset.snapTo(0f)
                                        onPageIndexChange(dragState.startPage + 1)
                                    }
                                }
                                totalDelta > 0f && dragState.startPage > 0 -> {
                                    scope.launch {
                                        pageOffset.animateTo(width.toFloat())
                                        pageOffset.snapTo(0f)
                                        onPageIndexChange(dragState.startPage - 1)
                                    }
                                }
                                totalDelta < 0f && dragState.startPage == textPages.lastIndex && isLastPage() -> onNext()
                                totalDelta > 0f && dragState.startPage == 0 && isFirstPage() -> onPrev()
                                else -> scope.launch { pageOffset.animateTo(0f) }
                            }
                        } else {
                            scope.launch { pageOffset.animateTo(0f) }
                        }
                    },
                    onDragCancel = { scope.launch { pageOffset.animateTo(0f) } },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragState.lastX += dragAmount
                        dragState.moved = true
                        val width = pageWidth.intValue.coerceAtLeast(1)
                        val proposed = pageOffset.value + dragAmount
                        val clamped = when {
                            dragState.startPage == 0 && proposed > 0f -> proposed * 0.3f
                            dragState.startPage == textPages.lastIndex && proposed < 0f -> proposed * 0.3f
                            else -> proposed
                        }
                        scope.launch { pageOffset.snapTo(clamped) }
                    }
                )
            }
            .pointerInput(textPages.size, pageIndex) {
                // 中央 75% 区域点击弹菜单（参考 demo1 的 leftRect/centerRect/rightRect 思路，
                // 但用 detectTapGestures + 区域判定实现）。左右 1/3 区域由 renderPage 自己
                // 处理（或扩展这里实现"点击左 1/3 翻上一页"）。
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    when {
                        // 中央 1/3 区域 + 上 75% 高度：弹菜单（参考 demo1 的 centerRect）。
                        offset.x in (w / 3f)..(w * 2f / 3f) && offset.y < h * 0.75f -> onContentTap()
                        // 左 1/3 区域：非首页翻上一页，已是首页则切到上一章。
                        offset.x < w / 3f -> if (pageIndex > 0) onPageIndexChange(pageIndex - 1) else onPrev()
                        // 右 1/3 区域：非末页翻下一页，已是末页则切到下一章。
                        offset.x > w * 2f / 3f -> if (pageIndex < textPages.lastIndex) onPageIndexChange(pageIndex + 1) else onNext()
                        else -> Unit
                    }
                }
            }
    ) {
        // 翻页过程渲染：pageOffset 接近 0 时画当前页；翻过中线后画相邻页并把相邻页放在
        // 屏幕另一侧，pageOffset 推动它进场。第一版只画一页（renderPage 接收的 pageIndex
        // 就是当前应该展示的那一页）。
        val width = pageWidth.intValue.coerceAtLeast(1).toFloat()
        val offset = pageOffset.value
        val renderIndex = when {
            offset < -width / 2f -> (pageIndex + 1).coerceAtMost(textPages.lastIndex)
            offset > width / 2f -> (pageIndex - 1).coerceAtLeast(0)
            else -> pageIndex
        }
        val renderOffsetX = when {
            renderIndex == pageIndex + 1 -> offset + width
            renderIndex == pageIndex - 1 -> offset - width
            else -> 0f
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = renderOffsetX }
        ) {
            renderPage(this, renderIndex)
        }
    }
}

private class DragState {
    var startX: Float = 0f
    var startPage: Int = 0
    var lastX: Float = 0f
    var moved: Boolean = false
}
