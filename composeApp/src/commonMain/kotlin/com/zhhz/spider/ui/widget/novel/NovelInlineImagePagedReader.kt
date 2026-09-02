package com.zhhz.spider.ui.widget.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import com.zhhz.spider.VolumeKeyPageEffect
import com.zhhz.spider.manager.imageRequest
import com.zhhz.spider.viewModel.MangaImage
import com.zhhz.spider.viewModel.ReaderUiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private sealed interface InlinePageEntry {
    data class Text(val line: TextLine, val trailingSpace: Float) : InlinePageEntry
    data class Image(val image: MangaImage, val height: Float, val gap: Float) : InlinePageEntry
}

private data class InlineReaderPage(val entries: List<InlinePageEntry>)

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun NovelInlineImagePagedReader(
    uiState: ReaderUiState,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val block = uiState.content.blocks.firstOrNull() ?: return
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val context = LocalPlatformContext.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
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
    val textStyle = TextStyle(
        color = Color(config.textColorArgb),
        fontSize = config.textSize.sp,
        fontFamily = fontFamily
    )
    var pages by remember { mutableStateOf<List<InlineReaderPage>>(emptyList()) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(config.background))
            .focusRequester(focusRequester)
            .focusable()
    ) {
        val constraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        val imageWidthPx = constraints.maxWidth - config.margin * density.density * 2

        LaunchedEffect(block.chapterUrl, block.text, config, constraints) {
            if (constraints.maxWidth <= 0 || constraints.maxHeight <= 0) return@LaunchedEffect
            val provider = KmpReadProvider(textMeasurer, textStyle, density)
            pages = buildInlineReaderPages(
                sections = splitNovelInlineSections(block.text, block.inlineImages),
                provider = provider,
                constraints = constraints,
                config = config,
                density = density,
                textMeasurer = textMeasurer,
                textStyle = textStyle
            )
        }

        if (pages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@BoxWithConstraints
        }

        val pagerState = rememberPagerState(
            initialPage = uiState.currentProgress.coerceIn(0, pages.lastIndex),
            pageCount = { pages.size }
        )

        fun turnPage(forward: Boolean) {
            val target = pagerState.currentPage + if (forward) 1 else -1
            if (target in pages.indices) {
                scope.launch { pagerState.animateScrollToPage(target) }
            } else if (forward) {
                onNext()
            } else {
                onPrev()
            }
        }

        LaunchedEffect(pagerState, pages.size) {
            focusRequester.requestFocus()
            androidx.compose.runtime.snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .debounce(250L)
                .collectLatest { page -> onProgressUpdate(block.index, page) }
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
                        Key.DirectionRight, Key.PageDown, Key.DirectionDown -> {
                            turnPage(true); true
                        }
                        Key.DirectionLeft, Key.PageUp, Key.DirectionUp -> {
                            turnPage(false); true
                        }
                        Key.Home -> {
                            scope.launch { pagerState.scrollToPage(0) }; true
                        }
                        else -> false
                    }
                }
                .pointerInput(pages.size, screenWidth, screenHeight) {
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
                        if (target in pages.indices) {
                            scope.launch { pagerState.animateScrollToPage(target) }
                        } else if (target < 0) {
                            onPrev()
                        } else {
                            onNext()
                        }
                    }
                }
        ) { pageIndex ->
            InlineNovelPage(
                page = pages[pageIndex],
                pageIndex = pageIndex,
                pageCount = pages.size,
                chapterTitle = block.chapterTitle,
                config = config,
                textMeasurer = textMeasurer,
                textStyle = textStyle,
                density = density,
                imageWidthPx = imageWidthPx,
                uiState = uiState,
                context = context
            )
        }
    }
}

private sealed interface NovelInlineSection {
    data class Text(val value: String) : NovelInlineSection
    data class Image(val image: MangaImage) : NovelInlineSection
}

private fun splitNovelInlineSections(text: String, images: List<MangaImage>): List<NovelInlineSection> {
    val marker = Regex("""▩([^▩]+)▩""")
    val sections = mutableListOf<NovelInlineSection>()
    var textStart = 0
    marker.findAll(text).forEach { match ->
        // 图片边界的换行由 imageGap 统一控制，避免图片前保留空行、图片后却被忽略。
        text.substring(textStart, match.range.first)
            .trim('\r', '\n')
            .takeIf { it.isNotEmpty() }
            ?.let { sections += NovelInlineSection.Text(it) }
        images.firstOrNull { it.url == match.groupValues[1] }?.let {
            sections += NovelInlineSection.Image(it)
        }
        textStart = match.range.last + 1
    }
    text.substring(textStart)
        .trimStart('\r', '\n')
        .takeIf { it.isNotEmpty() }
        ?.let { sections += NovelInlineSection.Text(it) }
    return sections
}

private suspend fun buildInlineReaderPages(
    sections: List<NovelInlineSection>,
    provider: KmpReadProvider,
    constraints: Constraints,
    config: ReadBookConfig,
    density: Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle
): List<InlineReaderPage> {
    val textHeight = textMeasurer.measure("我", textStyle).size.height.toFloat()
    val marginPx = config.margin * density.density
    val headerAndFooter = textHeight * 0.85f * 2
    val maxContentHeight = (constraints.maxHeight - marginPx * 2 - headerAndFooter).coerceAtLeast(textHeight)
    val baseLineSpacing = textHeight * (config.lineSpacingRatio - 1f) + config.lineSpacing * density.density
    val paragraphSpacing = config.paragraphSpacing * density.density
    val imageHeight = minOf(maxContentHeight * 0.32f, 260f * density.density).coerceAtLeast(textHeight * 2.5f)
    // 图片上下留白与普通行距保持一致，避免图片与上下文字间距不对称
    val imageGap = baseLineSpacing
    val pages = mutableListOf<InlineReaderPage>()
    val entries = mutableListOf<InlinePageEntry>()
    var usedHeight = 0f

    fun append(entry: InlinePageEntry, height: Float) {
        if (entries.isNotEmpty() && usedHeight + height > maxContentHeight) {
            pages += InlineReaderPage(entries.toList())
            entries.clear()
            usedHeight = 0f
        }
        entries += entry
        usedHeight += height
    }

    for ((sectionIndex, section) in sections.withIndex()) {
        when (section) {
            is NovelInlineSection.Text -> {
                val nextIsImage = sections.getOrNull(sectionIndex + 1) is NovelInlineSection.Image
                val lines = provider
                    .measureTextInBackground(section.value, constraints, config)
                    .flatMap { it.textLines }
                lines.forEachIndexed { lineIndex, line ->
                    // 图片前一行不再附加段距/行距，间隔完全由图片自身的 imageGap 控制。
                    val isLastBeforeImage = nextIsImage && lineIndex == lines.lastIndex
                    val trailingSpace = when {
                        isLastBeforeImage -> 0f
                        line.isParagraphEnd -> baseLineSpacing + paragraphSpacing
                        else -> baseLineSpacing
                    }
                    append(InlinePageEntry.Text(line, trailingSpace), line.height + trailingSpace)
                }
            }
            is NovelInlineSection.Image -> {
                val totalHeight = imageHeight + imageGap * 2
                append(InlinePageEntry.Image(section.image, imageHeight, imageGap), totalHeight)
            }
        }
    }
    if (entries.isNotEmpty()) pages += InlineReaderPage(entries.toList())
    return pages
}

private data class InlineImageLayout(
    val image: MangaImage,
    val top: Float,
    val height: Float
)

@Composable
private fun InlineNovelPage(
    page: InlineReaderPage,
    pageIndex: Int,
    pageCount: Int,
    chapterTitle: String,
    config: ReadBookConfig,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    density: Density,
    imageWidthPx: Float,
    uiState: ReaderUiState,
    context: PlatformContext
) {
    val marginPx = config.margin * density.density
    val contentTop = marginPx + textMeasurer.measure("我", textStyle).size.height * 0.85f
    val imageLayouts = remember(page, contentTop) {
        var currentTop = contentTop
        buildList {
            page.entries.forEach { entry ->
                when (entry) {
                    is InlinePageEntry.Text -> currentTop += entry.line.height + entry.trailingSpace
                    is InlinePageEntry.Image -> {
                        // 图片上方留 gap，与下方 gap 对称
                        add(InlineImageLayout(entry.image, currentTop + entry.gap, entry.height))
                        currentTop += entry.height + entry.gap * 2
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(config.background))) {
        Canvas(Modifier.fillMaxSize()) {
            var curY = contentTop
            for (entry in page.entries) {
                when (entry) {
                    is InlinePageEntry.Text -> {
                        for (element in entry.line.elements) {
                            if (element is NovelElement.TextRun && element.text.isNotBlank() && element.text != "\n") {
                                drawText(textMeasurer, element.text, Offset(element.rect.left, curY), textStyle)
                            }
                        }
                        curY += entry.line.height + entry.trailingSpace
                    }
                    is InlinePageEntry.Image -> curY += entry.height + entry.gap * 2
                }
            }
        }
        imageLayouts.forEach { layout ->
            InlineNovelImage(
                image = layout.image,
                uiState = uiState,
                context = context,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(marginPx.toInt(), layout.top.toInt()) }
                    .width(with(density) { imageWidthPx.toDp() })
                    .height(with(density) { layout.height.toDp() })
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            NovelPageChrome(pageIndex, pageCount, chapterTitle, config, textMeasurer, textStyle, density)
        }
    }
}

@Composable
private fun InlineNovelImage(
    image: MangaImage,
    uiState: ReaderUiState,
    context: PlatformContext,
    modifier: Modifier
) {
    val request = remember(uiState.ruleId, uiState.bookUrl, image) {
        imageRequest(uiState.ruleId, uiState.bookUrl, image, context = context)
    }
    var painterState by remember(request) { mutableStateOf<AsyncImagePainter.State?>(null) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onState = { painterState = it }
        )
        when (painterState) {
            null, is AsyncImagePainter.State.Empty, is AsyncImagePainter.State.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.06f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("图片加载中", modifier = Modifier.padding(top = 8.dp))
                }
            }
            is AsyncImagePainter.State.Error -> Text("图片加载失败")
            else -> Unit
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.NovelPageChrome(
    pageIndex: Int,
    pageCount: Int,
    chapterTitle: String,
    config: ReadBookConfig,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    density: Density
) {
    val marginPx = config.margin * density.density
    val smallTextSize = (config.textSize * 0.7f).coerceIn(11f, 15f).sp
    val smallTextStyle = textStyle.copy(fontSize = smallTextSize, color = textStyle.color.copy(alpha = 0.45f))
    drawText(textMeasurer, chapterTitle, Offset(marginPx, marginPx * 0.4f), smallTextStyle)
    val progressText = "${pageIndex + 1}/$pageCount"
    val progressLayout = textMeasurer.measure(progressText, smallTextStyle)
    drawText(
        textMeasurer,
        progressText,
        Offset(size.width - marginPx - progressLayout.size.width, size.height - marginPx * 0.4f - progressLayout.size.height),
        smallTextStyle
    )
    if (config.brightness < 1.0f) {
        drawRect(Color.Black.copy(alpha = 1f - config.brightness), size = size)
    }
}
