package com.zhhz.spider.ui.widget.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.zhhz.spider.VolumeKeyPageEffect
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
    if (currentBlock.inlineImages.isNotEmpty()) {
        NovelInlineImagePagedReader(uiState, onProgressUpdate, onToggleMenu, onNext, onPrev)
        return
    }
    val text = currentBlock.text
    val chapterTitle = currentBlock.chapterTitle

    var textPages by remember(currentBlock.chapterUrl, text) { mutableStateOf<List<TextPage>>(emptyList()) }

    val config = ReadBookConfig(
        textColorArgb = uiState.settings.textColor.toInt(),
        background = uiState.settings.backgroundColor.toInt(),
        textSize = uiState.settings.fontSize,
        lineSpacing = uiState.settings.lineSpacing,
        paragraphSpacing = uiState.settings.paragraphSpacing,
        margin = uiState.settings.margin,
        fontFamily = uiState.settings.fontFamily,
        brightness = uiState.settings.brightness
    )

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(config.background))
            .focusRequester(focusRequester)
            .focusable()
    ) {
        // 系统栏高度：排版与绘制都需避开状态栏/导航栏，背景仍铺满全屏
        val insets = WindowInsets.systemBars.asPaddingValues()
        val topInsetPx = with(density) { insets.calculateTopPadding().toPx() }
        val bottomInsetPx = with(density) { insets.calculateBottomPadding().toPx() }

        val constraints = Constraints(
            maxWidth = constraints.maxWidth,
            maxHeight = (constraints.maxHeight - topInsetPx.toInt() - bottomInsetPx.toInt()).coerceAtLeast(0)
        )
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()

        LaunchedEffect(currentBlock.chapterUrl, text, config, constraints) {
            if (constraints.maxWidth <= 0 || constraints.maxHeight <= 0) return@LaunchedEffect
            textPages = emptyList()
            textPages = AndroidReadProvider.measureTextInBackground(text, constraints, config, density)
        }

        if (textPages.isNotEmpty()) {
            key(currentBlock.chapterUrl, text) {
            val pagerState = rememberPagerState(
                initialPage = uiState.currentProgress.coerceIn(0, textPages.size - 1),
                pageCount = { textPages.size }
            )

            fun turnPage(forward: Boolean) {
                val target = pagerState.currentPage + if (forward) 1 else -1
                if (target in textPages.indices) {
                    scope.launch { pagerState.animateScrollToPage(target) }
                } else if (forward) {
                    onNext()
                } else {
                    onPrev()
                }
            }

            LaunchedEffect(pagerState, textPages.size) {
                focusRequester.requestFocus()
                androidx.compose.runtime.snapshotFlow { pagerState.currentPage }
                    .distinctUntilChanged()
                    .debounce(250L)
                    .collectLatest { page ->
                        onProgressUpdate(currentBlock.index, page)
                    }
            }

            VolumeKeyPageEffect(
                enabled = !uiState.isMenuVisible,
                onForward = { turnPage(false) },
                onBackward = { turnPage(true) }
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !uiState.isMenuVisible,
                modifier = Modifier
                    .fillMaxSize()
                    .blockReaderGestures(uiState.isMenuVisible, onToggleMenu)
                    .onPreviewKeyEvent { event ->
                        if (uiState.isMenuVisible) return@onPreviewKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight, Key.PageDown, Key.DirectionDown -> { turnPage(true); true }
                            Key.DirectionLeft, Key.PageUp, Key.DirectionUp -> { turnPage(false); true }
                            Key.Home -> { scope.launch { pagerState.scrollToPage(0) }; true }
                            else -> false
                        }
                    }
                    .pointerInput(textPages.size, screenWidth, screenHeight) {
                        detectTapGestures { offset ->
                            val target = when {
                                offset.x < screenWidth / 3 -> pagerState.currentPage - 1
                                offset.x > screenWidth * 2 / 3 -> pagerState.currentPage + 1
                                offset.y <= screenHeight * 0.75f -> {
                                    onToggleMenu()
                                    return@detectTapGestures
                                }
                                else -> return@detectTapGestures
                            }
                            if (target in textPages.indices) {
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
                    textPaint.letterSpacing = config.letterSpacing
                    textPaint.color = config.textColorArgb

                    // 内容整体避开状态栏/导航栏
                    var curY = marginPx + topInsetPx

                    // 绘制章节标题（左上角）
                    val savedSize = textPaint.textSize
                    textPaint.textSize = savedSize * 0.5f
                    val alpha = (0.45f * 255).toInt() shl 24
                    textPaint.color = alpha or (config.textColorArgb and 0x00FFFFFF)
                    nativeCanvas.drawText(chapterTitle, marginPx, topInsetPx + marginPx * 0.4f + textPaint.textHeight, textPaint)

                    // 绘制页码（右下角）
                    val progressText = "${pageIndex + 1}/${textPages.size}"
                    val progressWidth = textPaint.measureText(progressText)
                    nativeCanvas.drawText(
                        progressText,
                        size.width - marginPx - progressWidth,
                        size.height - bottomInsetPx - marginPx * 0.4f,
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
                                    // 插图行：在分配的排版空间 (lineH) 中绝对居中绘制，
                                    // 并在此空间上下各留出 1 倍字高的强制留白。
                                    val lineH = textLine.height
                                    val pad = textPaint.textHeight * 1f
                                    val illustHeight = (lineH - pad * 2).coerceAtLeast(textPaint.textHeight)
                                    val illustTop = curY - lineH + pad
                                    drawIllustration(
                                        url = element.url,
                                        topLeft = Offset(marginPx, illustTop),
                                        size = Size(size.width - marginPx * 2, illustHeight),
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
