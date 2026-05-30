package com.zhhz.spider.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.broken_image_24px

@Composable
fun BookCover(url: String, modifier: Modifier = Modifier) {

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(url)

            // 如果知道来源，最好加上 Referer
            // .setHeader("Referer", url.substringBefore("/", url))
            .crossfade(true)
            .build(),
        contentDescription = "封面",
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Crop, // 居中裁剪，保证网格整齐
        loading = {
            // 加载中的占位图：灰色块 + Loading
            Box(Modifier.fillMaxSize().background(Color.LightGray)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp))
            }
        },
        error = {
            // 出错后的占位图：显示书名首字母或默认图标
            Box(Modifier.fillMaxSize().background(Color(0xFFEEEEEE))) {
                Icon(painterResource(Res.drawable.broken_image_24px), null, Modifier.align(Alignment.Center), tint = Color.Gray)
            }
        }
    )
}