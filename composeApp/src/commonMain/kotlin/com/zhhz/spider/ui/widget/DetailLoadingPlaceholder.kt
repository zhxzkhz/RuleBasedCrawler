package com.zhhz.spider.ui.widget

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DetailLoadingPlaceholder() {
    // 呼吸灯动画效果
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val skeletonColor = Color.LightGray.copy(alpha = alpha)

    Row(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        // 封面占位
        Box(
            modifier = Modifier
                .size(100.dp, 140.dp)
                .background(skeletonColor, RoundedCornerShape(4.dp))
        )

        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            // 书名占位
            Box(Modifier.fillMaxWidth(0.7f).height(24.dp).background(skeletonColor, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(12.dp))
            // 作者占位
            Box(Modifier.fillMaxWidth(0.4f).height(16.dp).background(skeletonColor, RoundedCornerShape(2.dp)))
            Spacer(Modifier.weight(1f))
            // 按钮占位
            Box(Modifier.size(120.dp, 36.dp).background(skeletonColor, RoundedCornerShape(18.dp)))
        }
    }
}