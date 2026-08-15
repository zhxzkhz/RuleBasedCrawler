package com.zhhz.spider.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import coil3.size.Dimension
import com.zhhz.spider.manager.imageRequest
import com.zhhz.spider.viewModel.ChapterBlock
import com.zhhz.spider.viewModel.MangaImage
import com.zhhz.spider.viewModel.ReaderUiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
                ).padding(16.dp)
        ) {
            // 标题
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = Color(uiState.settings.textColor),
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (bodyText.isBlank()) {
                EmptyReaderContent(text = "本章暂无正文内容")
            } else {
                Text(
                    text = bodyText,
                    fontSize = uiState.settings.fontSize.sp,
                    lineHeight = (uiState.settings.fontSize * 1.6).sp, // 舒适的行间距
                    color = Color(uiState.settings.textColor)
                )
            }

            // 底部翻页控制器
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(enabled = uiState.hasPrev, onClick = onPrev) {
                    Text("上一章")
                }
                Button(enabled = uiState.hasNext, onClick = onNext) {
                    Text("下一章")
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
    onPrev: () -> Unit // 漫画瀑布流通常不用上一章，但可以保留接口以备不时之需
) {
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

    LazyColumn(
        state = listState, modifier = Modifier.fillMaxSize().background(Color.Black).clickable(
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

                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    Text(
                        text = "—— ${block.chapterTitle} ——",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
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

                            // 💡 给用户一个手动拉取下一章的权利
                            Button(onClick = { onNext() }) {
                                Text("手动跳往下一章")
                            }
                        }
                    }
                }
            } else {

                // 2. 渲染该章节的所有图片
                items(block.images, key = { it.url }) { image ->
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
                                .build()
                        }
                        var painterState by remember(image.url, retryVersion, appliedReloadVersion) {
                            mutableStateOf<AsyncImagePainter.State?>(null)
                        }

                        // Keep a small minimum height without replacing the image with a fixed
                        // loading block. Replacing the block caused a second LazyColumn measure
                        // pass exactly while the user was scrolling.
                        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
                            AsyncImage(
                                model = request,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                                onState = { painterState = it }
                            )

                            if (painterState is AsyncImagePainter.State.Error) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().height(300.dp).background(Color(0xFF1E1E1E)),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("图片加载失败", color = Color.Gray)
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { retryVersion++ }) {
                                        Text("重试")
                                    }
                                }
                            }
                        }
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
                    CircularProgressIndicator(color = Color.White)
                } else if (!uiState.hasNext) {
                    Text("—— 已经是最新一话了 ——", color = Color.Gray)
                }
            }
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
