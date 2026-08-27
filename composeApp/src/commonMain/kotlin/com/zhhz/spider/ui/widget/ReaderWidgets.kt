package com.zhhz.spider.ui.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Dimension
import com.zhhz.spider.manager.imageRequest
import com.zhhz.spider.viewModel.ChapterBlock
import com.zhhz.spider.viewModel.MangaImage
import com.zhhz.spider.viewModel.ReaderUiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private data class MangaImageEntry(
    val image: MangaImage,
    val isImageDecrypt: Boolean
)

/**
 * 💡 纯函数算法：根据 LazyColumn 里的绝对可见索引，推算出它属于哪一个章节。
 * @param blocks 当前屏幕渲染的所有章节块列表
 * @param firstVisibleIndex Compose LazyColumn 当前第一项可见元素的绝对索引
 * @return 所属章节的全局绝对索引（block.index）
 */
private fun calculateChapterIndex(blocks: List<ChapterBlock>, firstVisibleIndex: Int): Int {
    var accumulatedItemCount = 0

    for (block in blocks) {
        // 💡 算法升级：计算这个 Block 占据的 Item 数量
        val itemsInThisBlock = if (block.images.isEmpty()) {
            // 如果是空章节：1 (章节分割线) + 1 (空章报错占位UI) = 2 个 Item
            2
        } else {
            // 如果是正常章节：1 (章节分割线) + images.size
            1 + block.images.size
        }

        val blockEndIndex = accumulatedItemCount + itemsInThisBlock

        if (firstVisibleIndex < blockEndIndex) {
            return block.index
        }
        accumulatedItemCount += itemsInThisBlock
    }

    return blocks.firstOrNull()?.index ?: 0
}


/**
 * 💡 纯函数算法：根据 LazyColumn 里的绝对可见索引，刨除所有的 Header，推算出当前对应的是 flattenedImages 里的第几张图。
 */
private fun calculateImageIndex(blocks: List<ChapterBlock>, absoluteIndex: Int): Int {
    var imageCount = 0
    var itemCount = 0

    for (block in blocks) {
        // 1. 累加 Header 占用的 1 个 Item
        itemCount += 1

        if (absoluteIndex < itemCount) {
            // 说明当前可见的是 Header（分割线），此时对应的图片位置就是当前已累加的图片数
            return imageCount
        }

        val imagesInBlock = block.images.size
        // 2. 如果绝对索引落在这章的图片区间内
        if (absoluteIndex < itemCount + imagesInBlock) {
            val offsetInBlock = absoluteIndex - itemCount
            return imageCount + offsetInBlock // 💡 完美计算出绝对图片索引！
        }

        // 3. 累加这章的图片数和 Item 数，进入下一章计算
        imageCount += imagesInBlock
        itemCount += imagesInBlock
    }

    return imageCount
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}


@Composable
fun NovelReaderView(
    uiState: ReaderUiState, onToggleMenu: () -> Unit, onNext: () -> Unit, onPrev: () -> Unit
) {
    // 移除点击时的水波纹，让阅读体验更纯粹
    val interactionSource = remember { MutableInteractionSource() }
    val currentBlock = uiState.content.blocks.firstOrNull()
    val chapterTitle = currentBlock?.chapterTitle?.takeIf { it.isNotBlank() } ?: uiState.title
    val bodyText = currentBlock?.text.orEmpty()

    SelectionContainer {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(uiState.settings.backgroundColor)).verticalScroll(rememberScrollState()).clickable(
                    interactionSource = interactionSource, indication = null, onClick = onToggleMenu
                ).padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            val textColor = Color(uiState.settings.textColor)

            // 标题：左侧强调条
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 22.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (bodyText.isBlank()) {
                EmptyReaderContent(text = "本章暂无正文内容")
            } else {
                Text(
                    text = bodyText,
                    fontSize = uiState.settings.fontSize.sp,
                    lineHeight = (uiState.settings.fontSize * 1.6).sp, // 舒适的行间距
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = textColor.copy(alpha = 0.12f))

            // 底部翻页控制器
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onPrev,
                    enabled = uiState.hasPrev,
                    border = BorderStroke(1.dp, textColor.copy(alpha = 0.35f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                ) {
                    Text("← 上一章")
                }
                Button(
                    onClick = onNext,
                    enabled = uiState.hasNext,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("下一章 →")
                }
            }
        }
    }
}


@OptIn(FlowPreview::class)
@Composable
fun MangaReaderView(
    ruleId: String,
    uiState: ReaderUiState,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit, // 漫画瀑布流通常不用上一章，但可以保留接口以备不时之需
    onRetry: (chapterUrl: String, index: Int) -> Unit = { _, _ -> } // 空章节"重新获取"
) {
    // 💡 阅读模式分流：webtoon 竖滑 / paged 翻页 / rtl 日漫右开
    when (uiState.settings.mangaMode) {
        "paged", "rtl" -> {
            MangaPagedView(
                ruleId = ruleId,
                uiState = uiState,
                rtl = uiState.settings.mangaMode == "rtl",
                onProgressUpdate = onProgressUpdate,
                onToggleMenu = onToggleMenu,
                onNext = onNext,
                onRetry = onRetry
            )
            return
        }
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = uiState.currentProgress)
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalPlatformContext.current
    // 💡 1. 核心判断：提取出当前的“末尾块”，看看它是不是空的
    val lastBlock = uiState.content.blocks.lastOrNull()
    val isLastBlockEmpty = lastBlock != null && lastBlock.images.isEmpty()

    // 💡 1. 将所有已加载的章节区块中的所有 MangaImage 展平为一个连续的大列表
    val flattenedImages = remember(uiState.content.blocks) {
        uiState.content.blocks.flatMap { block ->
            block.images.map { image -> MangaImageEntry(image = image, isImageDecrypt = block.isImageDecrypt) }
        }
    }


    val imageLoader = SingletonImageLoader.get(context)
    var appliedReloadVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.imageReloadVersion) {
        if (uiState.imageReloadVersion == 0) return@LaunchedEffect
        val currentBlock = uiState.content.blocks.firstOrNull { it.index == uiState.currentIndex }
            ?: return@LaunchedEffect
        currentBlock.images.forEach { image ->
            imageLoader.memoryCache?.remove(MemoryCache.Key(image.url))
            imageLoader.diskCache?.remove(image.url)
        }
        appliedReloadVersion = uiState.imageReloadVersion
    }

    val buildRequest: (MangaImage, Boolean) -> ImageRequest = { mangaImage, isImageDecrypt ->
        imageRequest(ruleId, uiState.bookUrl, mangaImage, isImageDecrypt, context)
    }
    val buildPreloadRequest: (MangaImage, Boolean) -> ImageRequest = { mangaImage, isImageDecrypt ->
        buildRequest(mangaImage, isImageDecrypt).newBuilder()
            .memoryCachePolicy(CachePolicy.DISABLED)
            .size(1, 1)
            .build()
    }
    val preloadedImages = remember { mutableSetOf<String>() }

    // 内容到达后立即预取首屏及下一屏，避免预取依赖第一次滚动事件。
    LaunchedEffect(flattenedImages, uiState.currentProgress) {
        val preloadStart = calculateImageIndex(uiState.content.blocks, uiState.currentProgress)
            .coerceIn(0, flattenedImages.size)
        flattenedImages.drop(preloadStart).take(3).forEach { entry ->
            val key = "${entry.image.url}|${entry.isImageDecrypt}"
            if (preloadedImages.add(key)) {
                imageLoader.enqueue(buildPreloadRequest(entry.image, entry.isImageDecrypt))
            }
        }
    }

    LaunchedEffect(listState, uiState.content.blocks) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().debounce(250L)
            .collect { firstVisibleIndex ->
                // 计算当前属于哪一章 (通过遍历 blocks 判断)
                val currentChapterIndex = calculateChapterIndex(uiState.content.blocks, firstVisibleIndex)

                // 上报精确进度：第几章，以及列表当前的绝对 Item Index
                onProgressUpdate(currentChapterIndex, firstVisibleIndex)
            }
    }

    // 💡 1. 增加 blocks 的监听，确保数据追加时，协程捕获的是最新、最全的图片池
    LaunchedEffect(listState, uiState.content.blocks) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.distinctUntilChanged() // 💡 2. 过滤：只有当可见索引发生改变时才往下走
            .debounce(250L) // 💡 3. 防抖：快速滑动时绝对不计算，手停下超过 0.2 秒才去预加载，极大地保护了 CPU！
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex == null || flattenedImages.isEmpty()) return@collect

                // 💡 4. 核心：通过算法，将绝对索引精准转换为没有 Header 污染的“纯图片绝对索引”！
                val realImageIndex = calculateImageIndex(uiState.content.blocks, lastVisibleIndex)

                val preloadCount = 2

                // 💡 5. 此时进行切片，避免越界并只预取紧邻的图片。
                val startIndex = minOf(realImageIndex + 1, flattenedImages.size)
                val endIndex = minOf(startIndex + preloadCount, flattenedImages.size)

                val imagesToPreload = flattenedImages.subList(startIndex, endIndex)

                imagesToPreload.forEach { entry ->
                    val key = "${entry.image.url}|${entry.isImageDecrypt}"
                    if (preloadedImages.add(key)) {
                        imageLoader.enqueue(buildPreloadRequest(entry.image, entry.isImageDecrypt))
                    }
                }
            }
    }


    // 💡 核心逻辑：监听滑动到底部触发加载下一章（瀑布流无限加载）
    // 💡 适配嵌套 Block 后的瀑布流无限加载监听
    // 💡 2. 把 isLastBlockEmpty 加到 LaunchedEffect 的 Key 里，让它参与重组监听
    LaunchedEffect(listState, uiState.isLoading, uiState.hasNext, isLastBlockEmpty) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem?.index to layoutInfo.totalItemsCount
        }.collect { (lastVisibleIndex, totalItemsCount) ->
                if (lastVisibleIndex != null && totalItemsCount > 0) {
                    if (lastVisibleIndex >= totalItemsCount - 1) {

                        // 💡 3. 终极拦截：只有在「不在加载中」且「有下一章」且【末尾块不是空块】的情况下，才允许自动加载！
                        if (!uiState.isLoading && uiState.hasNext && !isLastBlockEmpty) {
                            onNext()
                        }

                    }
                }
            }
    }

    // 💡 主题适配：漫画页背景/文字跟随排版设置（默认米白、护眼绿、夜间、复古均生效）
    val themeBackground = Color(uiState.settings.backgroundColor)
    val themeOnBackground = Color(uiState.settings.textColor)
    val isDarkBackground = themeBackground.luminance() < 0.5f
    // 占位底色：在主题背景上轻微加深/提亮，保证与背景可区分
    val pagePlaceholderBg =
        if (isDarkBackground) lerp(themeBackground, Color.White, 0.08f)
        else lerp(themeBackground, Color.Black, 0.10f)
    val onPlaceholder = if (isDarkBackground) Color.White.copy(alpha = 0.65f) else Color(0xFF8A8A8A)

    // 右上角浮动页码：根据第一可见 Item 实时推导 (本章第几页, 本章总页数)
    val currentPageInfo by remember(uiState.content.blocks) {
        derivedStateOf {
            val absoluteIndex = listState.firstVisibleItemIndex
            var accumulatedItemCount = 0
            for (block in uiState.content.blocks) {
                val itemsInThisBlock = if (block.images.isEmpty()) 2 else 1 + block.images.size
                if (absoluteIndex < accumulatedItemCount + itemsInThisBlock) {
                    if (block.images.isEmpty()) return@derivedStateOf null
                    val offsetInBlock = (absoluteIndex - accumulatedItemCount - 1).coerceAtLeast(0)
                    return@derivedStateOf (offsetInBlock + 1) to block.images.size
                }
                accumulatedItemCount += itemsInThisBlock
            }
            null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxSize().background(themeBackground).clickable(
                interactionSource = interactionSource, indication = null, onClick = onToggleMenu
            )
        ) {

            // 💡 遍历所有已加载的章节块
            uiState.content.blocks.forEach { block ->

                // 1. 优雅的章节分割线
                item {

                    val currentIndex = block.index

                    LaunchedEffect(Unit) {
                        // 汇报当前视口进入了第 currentIndex 章
                        onProgressUpdate(currentIndex, 0)
                    }

                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 1.dp)
                                    .background(themeOnBackground.copy(alpha = 0.25f))
                            )
                            Text(
                                text = block.chapterTitle,
                                color = themeOnBackground.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 1.dp)
                                    .background(themeOnBackground.copy(alpha = 0.25f))
                            )
                        }
                    }
                }

                if (block.images.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(300.dp) // 💡 强行撑开一个高度，防止列表迅速塌陷到底部触发 onNext
                                .padding(16.dp), contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "未获取到图片内容\n可能是网站抽风或规则失效",
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                if (uiState.retryingChapterIndex == block.index) {
                                    // 重新获取进行中：转圈提示（不整页 loading，避免打断其它章节浏览）
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 3.dp,
                                        color = themeOnBackground.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "正在重新获取…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = themeOnBackground.copy(alpha = 0.5f)
                                    )
                                } else {
                                    Button(onClick = { onRetry(block.chapterUrl, block.index) }) {
                                        Text("重新获取")
                                    }
                                }
                            }
                        }
                    }
                } else {

                    // 2. 渲染该章节的所有图片（带加载占位与页码提示）
                    itemsIndexed(block.images, key = { _, item -> item.url }) { imageIndex, image ->
                        var retryVersion by remember(image.url) { mutableIntStateOf(0) }

                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val targetWidth = constraints.maxWidth
                            // Decode to the viewport width instead of the source image's full size.
                            // This is the expensive operation that otherwise blocks scrolling when a
                            // new page enters the viewport.
                            val request = remember(
                                image.url,
                                block.isImageDecrypt,
                                retryVersion,
                                appliedReloadVersion,
                                targetWidth
                            ) {
                                buildRequest(image, block.isImageDecrypt).newBuilder()
                                    .size(Dimension.Pixels(targetWidth), Dimension.Undefined)
                                    .crossfade(150)
                                    .build()
                            }

                            MangaPageItem(
                                request = request,
                                pageIndex = imageIndex + 1,
                                pageCount = block.images.size,
                                placeholderBg = pagePlaceholderBg,
                                onPlaceholder = onPlaceholder,
                                onRetry = { retryVersion++ },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                }
            }

            // 底部加载状态提示 (无缝连接)
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(color = themeOnBackground.copy(alpha = 0.85f))
                    } else if (!uiState.hasNext) {
                        Text("—— 已经是最新一话了 ——", color = themeOnBackground.copy(alpha = 0.45f))
                    }
                }
            }
        }

        // 浮动页码指示器：随时可见当前是第几张图
        val info = currentPageInfo
        if (info != null && flattenedImages.isNotEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = if (isDarkBackground) 0.55f else 0.45f),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(12.dp)
            ) {
                Text(
                    text = "第 ${info.first} / ${info.second} 张",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 💡 翻页模式漫画阅读器（paged / rtl）。
 * HorizontalPager 全屏翻页：点击左右 1/3 区域切页、中间呼出菜单；
 * 日漫(rtl) reverseLayout 且点击语义对调；滑到末尾自动触发下一章追加；
 * 进度按"全局图片序号"上报，页码徽章显示章内位置。
 */
@OptIn(FlowPreview::class)
@Composable
private fun MangaPagedView(
    ruleId: String,
    uiState: ReaderUiState,
    rtl: Boolean,
    onProgressUpdate: (Int, Int) -> Unit,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onRetry: (chapterUrl: String, index: Int) -> Unit
) {
    val context = LocalPlatformContext.current
    val imageLoader = SingletonImageLoader.get(context)
    val scope = rememberCoroutineScope()

    val themeBackground = Color(uiState.settings.backgroundColor)
    val themeOnBackground = Color(uiState.settings.textColor)
    val isDarkBackground = themeBackground.luminance() < 0.5f
    val pagePlaceholderBg =
        if (isDarkBackground) lerp(themeBackground, Color.White, 0.08f)
        else lerp(themeBackground, Color.Black, 0.10f)
    val onPlaceholder = if (isDarkBackground) Color.White.copy(alpha = 0.65f) else Color(0xFF8A8A8A)

    // 展平所有章节的图片，并记录每页所属章节信息：(chapterIndex, chapter 内序号, 章内总数, chapterUrl)
    data class PageMeta(val image: MangaImage, val isDecrypt: Boolean, val chapterIndex: Int, val inChapter: Int, val chapterSize: Int, val chapterUrl: String)
    val pages = remember(uiState.content.blocks) {
        uiState.content.blocks.flatMap { block ->
            block.images.mapIndexed { i, img ->
                PageMeta(img, block.isImageDecrypt, block.index, i + 1, block.images.size, block.chapterUrl)
            }
        }
    }
    val totalPages = pages.size

    // 完全没有内容（空章节）：占位 + 重新获取
    if (totalPages == 0) {
        val emptyBlock = uiState.content.blocks.firstOrNull()
        Box(
            modifier = Modifier.fillMaxSize().background(themeBackground).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleMenu
            ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("未获取到图片内容\n可能是网站抽风或规则失效", color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (uiState.retryingChapterIndex != -1) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = themeOnBackground.copy(alpha = 0.7f))
                } else if (emptyBlock != null) {
                    Button(onClick = { onRetry(emptyBlock.chapterUrl, emptyBlock.index) }) { Text("重新获取") }
                }
            }
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = run {
            // 从"当前章节"定位起始页
            var acc = 0
            var start = 0
            for (block in uiState.content.blocks) {
                if (block.index == uiState.currentIndex && block.images.isNotEmpty()) {
                    start = acc
                    break
                }
                acc += block.images.size
            }
            start.coerceIn(0, totalPages - 1)
        },
        pageCount = { totalPages }
    )
    val pagerFocusRequester = remember { FocusRequester() }

    val buildRequest: (MangaImage, Boolean) -> ImageRequest = { img, dec ->
        imageRequest(ruleId, uiState.bookUrl, img, dec, context)
    }
    val preloaded = remember { mutableSetOf<String>() }

    // 邻页预加载 ±2
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            (page - 2..page + 2).forEach { p ->
                if (p in pages.indices) {
                    val meta = pages[p]
                    val key = "${meta.image.url}|${meta.isDecrypt}"
                    if (preloaded.add(key)) {
                        imageLoader.enqueue(
                            buildRequest(meta.image, meta.isDecrypt).newBuilder()
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .size(1, 1)
                                .build()
                        )
                    }
                }
            }
        }
    }

    // 上报进度（全局图片序号）+ 滑到末尾自动加载下一章
    LaunchedEffect(pagerState, uiState.hasNext, uiState.isLoading) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .debounce(250L)
            .collect { page ->
                val meta = pages.getOrNull(page) ?: return@collect
                onProgressUpdate(meta.chapterIndex, page)
                // 距离末尾 1 页且还有下一章 → 自动追加
                if (!rtl && page >= totalPages - 2 && uiState.hasNext && !uiState.isLoading) onNext()
            }
    }

    /** 统一的翻页动作：越界时向前翻页触发上一章，向后翻页触发下一章 */
    fun turnPage(forward: Boolean) {
        val target = pagerState.currentPage + if (forward) 1 else -1
        if (target in 0 until totalPages) {
            scope.launch { pagerState.animateScrollToPage(target) }
        } else if (forward) {
            onNext()
        }
    }

    // 💡 音量键翻页（Android 生效；桌面端无操作）
    com.zhhz.spider.VolumeKeyPageEffect(
        enabled = true,
        onForward = { turnPage(forward = !rtl) },   // 音量下=下一页（日漫对调）
        onBackward = { turnPage(forward = rtl) }
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(themeBackground)
            .focusRequester(pagerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { turnPage(forward = !rtl); true }
                    Key.DirectionLeft -> { turnPage(forward = rtl); true }
                    Key.PageDown, Key.DirectionDown -> { turnPage(forward = true); true }
                    Key.PageUp, Key.DirectionUp -> { turnPage(forward = false); true }
                    Key.Home -> { scope.launch { pagerState.scrollToPage(0) }; true }
                    else -> false
                }
            }
    ) {
        // 首次进入抢焦点，保证键盘事件路由到本容器
        LaunchedEffect(Unit) { pagerFocusRequester.requestFocus() }

        // 💡 双指缩放状态（跨页共享，翻页自动复位）
        val pageScale = remember { mutableFloatStateOf(1f) }
        val pageOffset = remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(pagerState.currentPage) {
            pageScale.floatValue = 1f
            pageOffset.value = Offset.Zero
        }

        HorizontalPager(
            state = pagerState,
            reverseLayout = rtl,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val meta = pages[page]
            var retryKey by remember(page) { mutableIntStateOf(0) }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val request = remember(meta.image.url, meta.isDecrypt, retryKey, constraints.maxWidth) {
                    buildRequest(meta.image, meta.isDecrypt).newBuilder()
                        .size(Dimension.Pixels(constraints.maxWidth), Dimension.Undefined)
                        .crossfade(120)
                        .build()
                }

                // 缩放偏移越界钳制（graphicsLayer 以中心为原点缩放）
                val clampOffset: (Offset, Float) -> Offset = { off, s ->
                    if (s <= 1f) Offset.Zero else {
                        val maxX = constraints.maxWidth * (s - 1) / 2f
                        val maxY = constraints.maxHeight * (s - 1) / 2f
                        Offset(off.x.coerceIn(-maxX, maxX), off.y.coerceIn(-maxY, maxY))
                    }
                }

                MangaPagedItem(
                    request = request,
                    label = "第 ${meta.inChapter} / ${meta.chapterSize} 张",
                    placeholderBg = pagePlaceholderBg,
                    onPlaceholder = onPlaceholder,
                    background = themeBackground,
                    retryKey = retryKey,
                    onRetryClick = { retryKey++ },
                    chapterUrl = meta.chapterUrl,
                    chapterIndex = meta.chapterIndex,
                    onRetryChapter = onRetry,
                    scaleState = pageScale,
                    offsetState = pageOffset,
                    clampOffset = clampOffset,
                    onTapZone = { zone ->
                        // 放大状态下点击用于拖看图片，不触发翻页/菜单
                        if (pageScale.floatValue <= 1f) {
                            when (zone) {
                                -1 -> turnPage(forward = rtl)   // 左侧：日漫=下一页，普通=上一页
                                1 -> turnPage(forward = !rtl)
                                else -> onToggleMenu()
                            }
                        }
                    },
                    onDoubleTapZoom = {
                        if (pageScale.floatValue > 1f) {
                            pageScale.floatValue = 1f
                            pageOffset.value = Offset.Zero
                        } else {
                            pageScale.floatValue = 2.5f
                            pageOffset.value = Offset.Zero
                        }
                    }
                )
            }
        }

        // 页码徽章：底部居中，随时可见当前章内位置
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 18.dp)
        ) {
            Text(
                text = "${pages.getOrNull(pagerState.currentPage)?.inChapter ?: 1} / ${pages.getOrNull(pagerState.currentPage)?.chapterSize ?: totalPages}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 翻页模式的单页渲染：图片 Fit 居中；支持双指缩放/拖动查看、双击缩放切换；
 * 加载失败提供两层重试——单张重载（retryKey）与整章重新获取（onRetryChapter）。
 */
@Composable
private fun MangaPagedItem(
    request: ImageRequest,
    label: String,
    placeholderBg: Color,
    onPlaceholder: Color,
    background: Color,
    retryKey: Int,
    onRetryClick: () -> Unit,
    chapterUrl: String,
    chapterIndex: Int,
    onRetryChapter: (String, Int) -> Unit,
    scaleState: MutableState<Float>,
    offsetState: MutableState<Offset>,
    clampOffset: (Offset, Float) -> Offset,
    onTapZone: (Int) -> Unit,   // -1 左 / 0 中 / 1 右
    onDoubleTapZoom: () -> Unit
) {
    var painterState by remember(request) { mutableStateOf<AsyncImagePainter.State?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        if (scaleState.value <= 1f) {
                            val w = size.width.toFloat()
                            onTapZone(if (pos.x < w / 3) -1 else if (pos.x > w * 2 / 3) 1 else 0)
                        }
                    },
                    onDoubleTap = { onDoubleTapZoom() }
                )
            }
    ) {
        // 图片层：应用缩放与平移；双指手势被消费后 Pager 不会抢滚动
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleState.value
                    scaleY = scaleState.value
                    translationX = offsetState.value.x
                    translationY = offsetState.value.y
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var engaged = scaleState.value > 1f
                        do {
                            val event = awaitPointerEvent()
                            if (engaged || event.changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (event.changes.size >= 2 && zoomChange != 1f) engaged = true
                                if (engaged) {
                                    event.changes.forEach { it.consume() }
                                    val newScale = (scaleState.value * zoomChange).coerceIn(1f, 5f)
                                    scaleState.value = newScale
                                    offsetState.value = clampOffset(offsetState.value + pan, newScale)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onState = { painterState = it }
            )
        }

        when (painterState) {
            is AsyncImagePainter.State.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().background(placeholderBg),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("图片加载失败", color = onPlaceholder)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetryClick) { Text("重试本图") }
                        OutlinedButton(onClick = { onRetryChapter(chapterUrl, chapterIndex) }) { Text("重新获取本章") }
                    }
                }
            }

            null, is AsyncImagePainter.State.Empty, is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(placeholderBg),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = onPlaceholder, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(label, color = onPlaceholder.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            else -> Unit
        }
    }
}

/**
 * 单张漫画页：加载中显示主题化占位背景 + 进度圈 + 页码，失败显示重试按钮。
 * AsyncImage 始终保持挂载，占位只是覆盖层，避免 LazyColumn 在滚动中发生二次测量。
 */
@Composable
private fun MangaPageItem(
    request: ImageRequest,
    pageIndex: Int,
    pageCount: Int,
    placeholderBg: Color,
    onPlaceholder: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var painterState by remember(request) { mutableStateOf<AsyncImagePainter.State?>(null) }

    Box(modifier = modifier.fillMaxWidth().heightIn(min = 300.dp)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
            onState = { painterState = it }
        )

        when (painterState) {
            is AsyncImagePainter.State.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth().height(300.dp).background(placeholderBg),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("图片加载失败", color = onPlaceholder)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) {
                        Text("重试")
                    }
                }
            }

            null, is AsyncImagePainter.State.Empty, is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp).background(placeholderBg),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = onPlaceholder,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "第 $pageIndex / $pageCount 张",
                            color = onPlaceholder.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun EmptyReaderContent(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFF7A7164),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
