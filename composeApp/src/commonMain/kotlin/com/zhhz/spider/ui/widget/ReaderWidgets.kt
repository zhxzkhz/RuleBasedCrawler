package com.zhhz.spider.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.zhhz.spider.manager.imageRequest
import com.zhhz.spider.viewModel.ChapterBlock
import com.zhhz.spider.viewModel.MangaImage
import com.zhhz.spider.viewModel.ReaderUiState

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


@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}


@Composable
fun NovelReaderView(
    uiState: ReaderUiState,
    onToggleMenu: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    // 移除点击时的水波纹，让阅读体验更纯粹
    val interactionSource = remember { MutableInteractionSource() }

    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggleMenu
                )
                .padding(16.dp)
        ) {
            // 标题
            Text(
                text = uiState.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.Black
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 正文
            Text(
                text = uiState.content.blocks.first().text,
                fontSize = 18.sp,
                lineHeight = 30.sp, // 舒适的行间距
                color = Color(0xFF333333)
            )

            // 底部翻页控制器
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
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
        uiState.content.blocks.flatMap { it.images }
    }


    val imageLoader = SingletonImageLoader.get(context)

    // 💡 1. 常驻内存的按需缓存字典
    val requestCache = remember { mutableMapOf<String, ImageRequest>() }

    // 💡 2. 核心辅助函数：按需创建 (Lazy Evaluation)
    // 无论是预加载还是 UI 渲染，都要找这张图。没有？现场建并塞入缓存！有？直接返回！
    val getOrBuildRequest: (MangaImage) -> ImageRequest = { mangaImage ->
        requestCache.getOrPut(mangaImage.url) {

            val newRequest = imageRequest(ruleId, uiState.bookUrl, mangaImage, uiState.content.blocks.first().isImageDecrypt, context)

            // 💡 一步到位：在创建时直接扔给 Coil 队列。
            // 因为 getOrPut 的闭包只会在 key 不存在时运行一次，所以这里保证了 0 重复 enqueue！
            imageLoader.enqueue(newRequest)

            newRequest
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex == null || flattenedImages.isEmpty()) return@collect

                val preloadCount = 5
                val startIndex = minOf(lastVisibleIndex, flattenedImages.size)
                val endIndex = minOf(startIndex + preloadCount, flattenedImages.size)

                flattenedImages.subList(startIndex, endIndex).forEach { mangaImage ->
                    getOrBuildRequest(mangaImage)
                }
            }
    }


    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstVisibleIndex ->
                // 计算当前属于哪一章 (通过遍历 blocks 判断)
                val currentChapterIndex = calculateChapterIndex(uiState.content.blocks, firstVisibleIndex)

                // 上报精确进度：第几章，以及列表当前的绝对 Item Index
                onProgressUpdate(currentChapterIndex, firstVisibleIndex)
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
        }
            .collect { (lastVisibleIndex, totalItemsCount) ->
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
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggleMenu
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp) // 💡 强行撑开一个高度，防止列表迅速塌陷到底部触发 onNext
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
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
                items(block.images) { image ->
                    val request = imageRequest(ruleId, uiState.bookUrl, image, block.isImageDecrypt, context)
                    var aspectRatio by rememberSaveable(image.url) { mutableStateOf(0f) }
                    AsyncImage(
                        model = request,
                        contentDescription = "Manga Page",
                        modifier = Modifier
                            .fillMaxWidth()
                            // 关键：预设一个大致比例，防止加载时高度跳动
                            .then(
                                if (aspectRatio > 0) Modifier.aspectRatio(aspectRatio)
                                else Modifier.height(500.dp) // 初始占位高度
                            ),
                        contentScale = ContentScale.FillWidth, // 宽度铺满，高度自适应
                        onState = { state ->
                            when (state) {
                                is AsyncImagePainter.State.Error -> {
                                    // 打印出具体的错误原因
                                    println("图片加载失败: ${state.result.throwable}")
                                }

                                is AsyncImagePainter.State.Loading -> println("正在加载(${image})...")
                                is AsyncImagePainter.State.Success -> {
                                    println("加载成功 $image")
                                    val size = state.painter.intrinsicSize
                                    if (size.width > 0 && size.height > 0) {
                                        aspectRatio = size.width / size.height
                                    }
                                }

                                AsyncImagePainter.State.Empty -> TODO()
                            }
                        }
                    )
                }

            }
        }

        // 底部加载状态提示 (无缝连接)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
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

