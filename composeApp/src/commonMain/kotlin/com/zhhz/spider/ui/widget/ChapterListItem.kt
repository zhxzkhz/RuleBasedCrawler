package com.zhhz.spider.ui.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.network.Chapter
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.chevron_right_24px

@Composable
fun ChapterListItem(
    chapter: Chapter,
    isRead: Boolean = false, // 是否已读
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 章节名称
            Text(
                text = chapter.title,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                // 已读变灰，未读加粗/黑色
                color = if (isRead) Color.Gray else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 状态标记
            if (isRead) {
                Text("已读", fontSize = 10.sp, color = Color.LightGray)
            } else {
                Icon(
                    painter = painterResource(Res.drawable.chevron_right_24px),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.LightGray
                )
            }
        }
    }
    // 细分割线
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = Color.LightGray.copy(alpha = 0.3f)
    )
}