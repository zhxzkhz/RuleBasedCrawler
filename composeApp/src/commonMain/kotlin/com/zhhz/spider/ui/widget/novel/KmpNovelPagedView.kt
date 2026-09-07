package com.zhhz.spider.ui.widget.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.geometry.Size
import com.zhhz.spider.ui.widget.novel.drawIllustration
import com.zhhz.spider.ui.widget.novel.rememberIllustrations
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import com.zhhz.spider.viewModel.ReaderUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KmpNovelPagedView(
    uiState: ReaderUiState,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val currentBlock = uiState.content.blocks.firstOrNull() ?: return
    if (currentBlock.inlineImages.isNotEmpty()) {
        NovelInlineImagePagedReader(uiState, onProgressUpdate, onToggleMenu, onNext, onPrev)
        return
    }
    val text = currentBlock.text
    val chapterTitle = currentBlock.chapterTitle

    var textPages by remember { mutableStateOf<List<TextPage>>(emptyList()) }

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

    val fontFamily = when (config.fontFamily) {
        "sans-serif" -> FontFamily.SansSerif
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.Serif
    }
    val textStyle = TextStyle(color = Color(config.textColorArgb), fontSize = config.textSize.sp, fontFamily = fontFamily)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(config.background))
            .focusRequester(focusRequester)
            .focusable()
    ) {
        val safeDrawingInsets = novelSafeDrawingInsets()
        val constraints = safeDrawingInsets.applyTo(constraints)
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()

        LaunchedEffect(text, config, constraints) {
            if (constraints.maxWidth <= 0 || constraints.maxHeight <= 0) return@LaunchedEffect
            val provider = KmpReadProvider(textMeasurer, textStyle, density)
            textPages = provider.measureTextInBackground(text, constraints, config)
        }

        if (textPages.isNotEmpty()) {
            key(currentBlock.chapterUrl, textPages.size, uiState.openAtEnd) {
                // 菜单或设置面板任一可见时都禁止翻页手势（两者互斥，设置打开时 menu=false）
                val hudVisible = uiState.isMenuVisible || uiState.isSettingsVisible
                val pagerState = rememberPagerState(
                    initialPage = initialChapterPageIndex(uiState.openAtEnd, uiState.currentProgress, textPages.lastIndex),
                    pageCount = { textPages.size }
                )
                val navigationMutex = remember { Mutex() }

                // 键盘翻页：在 pagerState 可用后绑定键盘事件
                LaunchedEffect(pagerState, textPages.size) {
                    focusRequester.requestFocus()
                }

                // 串行翻页：锁内重新读取当前页，连续点击/按键不会互相取消或重复计算目标
                fun turnPage(forward: Boolean, animate: Boolean = true) {
                    if (hudVisible || uiState.isLoading) return
                    scope.launch {
                        navigationMutex.withLock {
                            when (val turn = pageTurnTarget(pagerState.currentPage, forward, textPages.size)) {
                                is PageTurn.ToPage -> {
                                    if (animate && uiState.settings.pageAnimation) {
                                        pagerState.animateScrollToPage(turn.page)
                                    } else {
                                        pagerState.scrollToPage(turn.page)
                                    }
                                }
                                PageTurn.ToNextChapter -> onNext()
                                PageTurn.ToPreviousChapter -> onPrev()
                            }
                        }
                    }
                }

                // 音量键翻页（Android）：上=上一页，下=下一页；HUD 打开时禁用
                com.zhhz.spider.VolumeKeyPageEffect(
                    enabled = !hudVisible,
                    onForward = { turnPage(false) },
                    onBackward = { turnPage(true) }
                )

                // 进度上报与翻页
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
                    userScrollEnabled = !hudVisible,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .blockReaderGestures(hudVisible, onToggleMenu)
                        .onPreviewKeyEvent { event ->
                            if (hudVisible) return@onPreviewKeyEvent false
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionRight -> { turnPage(true); true }
                                Key.DirectionLeft -> { turnPage(false); true }
                                Key.PageDown, Key.DirectionDown -> { turnPage(true); true }
                                Key.PageUp, Key.DirectionUp -> { turnPage(false); true }
                                Key.Home -> { scope.launch { pagerState.scrollToPage(0) }; true }
                                else -> false
                            }
                        }
                        .pointerInput(textPages.size, screenWidth, screenHeight, hudVisible) {
                            detectTapGestures { offset ->
                                if (hudVisible) return@detectTapGestures
                                when (tapZoneAction(offset.x, offset.y, screenWidth, screenHeight)) {
                                    TapZoneAction.ToggleMenu -> onToggleMenu()
                                    TapZoneAction.Previous -> turnPage(forward = false, animate = false)
                                    TapZoneAction.Next -> turnPage(forward = true, animate = false)
                                    TapZoneAction.None -> Unit
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
                    val marginPx = config.margin * density.density
                    val header = textMeasurer.measure("我", textStyle).size.height * 0.85f
                    var curY = marginPx + header

                    val smallTextSize = (config.textSize * 0.7f).coerceIn(11f, 15f).sp
                    val smallTextStyle = textStyle.copy(
                        fontSize = smallTextSize,
                        color = textStyle.color.copy(alpha = 0.45f)
                    )

                    // 绘制左上角章节标题
                    drawText(
                        textMeasurer = textMeasurer,
                        text = chapterTitle,
                        topLeft = Offset(marginPx, marginPx * 0.4f),
                        style = smallTextStyle
                    )

                    // 绘制右下角阅读进度
                    val progressText = "${pageIndex + 1}/${textPages.size}"
                    val progressLayout = textMeasurer.measure(progressText, smallTextStyle)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = progressText,
                        topLeft = Offset(
                            size.width - marginPx - progressLayout.size.width,
                            size.height - marginPx * 0.4f - progressLayout.size.height
                        ),
                        style = smallTextStyle
                    )

                    for (textLine in page.textLines) {
                        for (element in textLine.elements) {
                            when (element) {
                                is NovelElement.TextRun -> {
                                    if (element.text.isNotBlank() && element.text != "\n") {
                                        drawText(
                                            textMeasurer = textMeasurer,
                                            text = element.text,
                                            topLeft = Offset(element.rect.left, curY),
                                            style = textStyle
                                        )
                                    }
                                }
                                is NovelElement.Illustration -> {
                                    // 插图行：在分配的排版空间内居中，上下各留 1 倍字高
                                    val lineH = textLine.height
                                    val pad = (config.textSize * density.density)
                                    val top = curY - lineH + pad
                                    val h = (lineH - pad * 2).coerceAtLeast(pad)
                                    drawIllustration(
                                        url = element.url,
                                        topLeft = Offset(marginPx, top),
                                        size = Size(size.width - marginPx * 2, h),
                                        bitmap = illustrationBitmaps[element.url]
                                    )
                                }
                            }
                        }
                        curY += textLine.height + textLine.extraHeight
                    }

                    // 亮度控制：叠加半透明黑色遮罩
                    if (config.brightness < 1.0f) {
                        drawRect(
                            color = Color.Black.copy(alpha = 1f - config.brightness),
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
