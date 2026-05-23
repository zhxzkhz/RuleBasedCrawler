package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.util.MangaDescrambleTransformation
import com.zhhz.spider.viewModel.ReaderViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun MangaPageList(
    imageUrls: List<String>,
    model: ReaderViewModel,
    onPageChange: (Int) -> Unit,
) {
    val scrollState = rememberLazyListState()

    val context = LocalPlatformContext.current
    val rule = model.rule

    val headers = NetworkHeaders.Builder().set("X-Internal-Rule-Id", rule.id).build()
    val imageLoader = SingletonImageLoader.get(context)

    // 监听滚动索引变化
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                model.saveProgress(pageIndex = index)
            }
    }

    LazyColumn(
        state = scrollState,
        modifier = Modifier.fillMaxSize().background(Color.Black), // 黑色背景专业感
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(imageUrls, key = { index, item ->
            // 建议使用 item (即 URL) 作为 Key，因为它是唯一的
            // 如果 URL 可能重复，可以用 "${index}_$item"
            "${index}_$item"
        }) { index, url ->
            MangaPageItem(url, context, rule, headers.newBuilder().build())

            // 2. 【核心优化】主动预加载下一张
            // 当 index 这一项进入屏幕时，启动协程去下载 index + 1

            LaunchedEffect(index) {
                val nextIndex = index + 1
                if (nextIndex < imageUrls.size) {
                    // 只是下载并存入磁盘/内存缓存，不渲染，不占 UI 资源
                    imageLoader.enqueue(imageRequest(url,context,rule,headers.newBuilder().build()))
                    println("PREFETCH: 已预取第 $nextIndex 页")
                }
            }
        }
    }
}

@Composable
fun MangaPageItem(url: String, context: PlatformContext, rule: SourceRule, headers: NetworkHeaders) {
    // 构造带 Header 的请求
    val request = imageRequest(url, context, rule, headers)
    var aspectRatio by rememberSaveable(url) { mutableStateOf(0f) }
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

                is AsyncImagePainter.State.Loading -> println("正在加载(${url})...")
                is AsyncImagePainter.State.Success -> {
                    println("加载成功")
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

fun imageRequest(url: String, context: PlatformContext, rule: SourceRule, headers: NetworkHeaders): ImageRequest {
    val preloadRequest = ImageRequest.Builder(context).data(url).size(Size.ORIGINAL)
    preloadRequest.httpHeaders(headers)
    if (rule.content.decryptImage.isNotBlank()) {
        preloadRequest.transformations(MangaDescrambleTransformation(url, rule))
    }

    val build = preloadRequest.build()

    return build
}
