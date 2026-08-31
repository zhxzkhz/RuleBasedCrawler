package com.zhhz.spider.ui.widget.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.rememberCoroutineScope
import com.zhhz.spider.ui.widget.novel.android.AndroidReadProvider
import com.zhhz.spider.ui.widget.novel.android.textHeight
import com.zhhz.spider.viewModel.ReaderUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android 原生小说阅读器（使用 TextPaint + nativeCanvas 渲染）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun AndroidNovelReader(
    uiState: ReaderUiState,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val density = LocalDensity.current
    val currentBlock = uiState.content.blocks.firstOrNull() ?: return
    val text = currentBlock.text
    val chapterTitle = currentBlock.chapterTitle

    var textPages by remember { mutableStateOf<List<TextPage>>(emptyList()) }

    val config = ReadBookConfig(
        textColorArgb = uiState.settings.textColor.toInt(),
        background = uiState.settings.backgroundColor.toInt(),
        textSize = uiState.settings.fontSize,
        lineSpacing = uiState.settings.lineSpacing,
        margin = uiState.settings.margin,
        fontFamily = uiState.settings.fontFamily,
        brightness = uiState.settings.brightness
    )

    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(config.background))
            .pointerInput(Unit) {
                detectTapGestures {
                    if (textPages.isEmpty()) onToggleMenu()
                }
            }
    ) {
        val constraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val screenWidth = constraints.maxWidth.toFloat()

        LaunchedEffect(text, config, constraints) {
            if (constraints.maxWidth <= 0 || constraints.maxHeight <= 0) return@LaunchedEffect
            textPages = AndroidReadProvider.measureTextInBackground(text, constraints, config, density)
        }

        if (textPages.isNotEmpty()) {
            val pagerState = rememberPagerState(
                initialPage = uiState.currentProgress.coerceIn(0, textPages.size - 1),
                pageCount = { textPages.size }
            )

            LaunchedEffect(pagerState, uiState.hasNext) {
                androidx.compose.runtime.snapshotFlow { pagerState.currentPage }
                    .distinctUntilChanged()
                    .debounce(250L)
                    .collectLatest { page ->
                        onProgressUpdate(currentBlock.index, page)
                    }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val target = when {
                            offset.x < screenWidth / 3 -> pagerState.currentPage - 1
                            offset.x > screenWidth * 2 / 3 -> pagerState.currentPage + 1
                            else -> {
                                onToggleMenu()
                                return@detectTapGestures
                            }
                        }
                        if (target in 0 until textPages.size) {
                            scope.launch { pagerState.animateScrollToPage(target) }
                        } else if (target < 0) {
                            onPrev()
                        } else {
                            onNext()
                        }
                    }
                }
            ) { pageIndex ->
                val page = textPages[pageIndex]

                // 收集当前页所有插图 URL，预加载到缓存
                val illustrationUrls = remember(pageIndex, textPages) {
                    page.textLines.flatMap { line ->
                        line.elements.filterIsInstance<NovelElement.Illustration>().map { it.url }
                    }.distinct()
                }
                val illustrationBitmaps = rememberIllustrations(illustrationUrls)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val textPaint = AndroidReadProvider.textPaint

                    val marginPx = config.margin * density.density
                    textPaint.textSize = config.textSize * density.density
                    textPaint.color = config.textColorArgb

                    val headerHeight = textPaint.textHeight * 0.65f
                    var curY = marginPx + headerHeight

                    // 绘制章节标题（左上角）
                    val savedSize = textPaint.textSize
                    textPaint.textSize = savedSize * 0.5f
                    val alpha = (0.45f * 255).toInt() shl 24
                    textPaint.color = alpha or (config.textColorArgb and 0x00FFFFFF)
                    nativeCanvas.drawText(chapterTitle, marginPx, marginPx * 0.4f + textPaint.textHeight, textPaint)

                    // 绘制页码（右下角）
                    val progressText = "${pageIndex + 1}/${textPages.size}"
                    val progressWidth = textPaint.measureText(progressText)
                    nativeCanvas.drawText(
                        progressText,
                        size.width - marginPx - progressWidth,
                        size.height - marginPx * 0.4f,
                        textPaint
                    )

                    // 恢复正文设置
                    textPaint.textSize = savedSize
                    textPaint.color = config.textColorArgb

                    // 绘制正文
                    for (textLine in page.textLines) {
                        curY += textLine.height
                        for (element in textLine.elements) {
                            when (element) {
                                is NovelElement.TextRun -> {
                                    if (element.text.isNotBlank() && element.text != "\n") {
                                        nativeCanvas.drawText(element.text, element.rect.left, curY, textPaint)
                                    }
                                }
                                is NovelElement.Illustration -> {
                                    // 插图区域：绘制已加载的位图，未加载时画占位灰块
                                    val illustTop = curY - marginPx
                                    val illustHeight = textPaint.textHeight * 3 + marginPx
                                    val illustWidth = size.width - marginPx * 2

                                    drawIllustration(
                                        url = element.url,
                                        topLeft = Offset(marginPx, illustTop),
                                        size = androidx.compose.ui.geometry.Size(illustWidth, illustHeight),
                                        bitmap = illustrationBitmaps[element.url]
                                    )
                                }
                            }
                        }
                        curY += textLine.extraHeight
                    }

                    // 亮度控制
                    if (config.brightness < 1.0f) {
                        drawRect(
                            color = Color.Black.copy(alpha = 1f - config.brightness),
                            topLeft = Offset(0f, 0f),
                            size = size
                        )
                    }
                }
            }
        } else {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
