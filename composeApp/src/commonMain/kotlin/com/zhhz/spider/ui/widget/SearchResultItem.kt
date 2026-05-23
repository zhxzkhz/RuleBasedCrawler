package com.zhhz.spider.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.network.SearchBook
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.chevron_right_24px

@Composable
fun SearchResultItem(book: SearchBook, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 左侧封面
            Card(
                shape = RoundedCornerShape(4.dp),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.size(50.dp, 70.dp)
            ) {
                BookCover(url = book.cover) // 使用之前定义的 Coil3 加载组件
            }

            // 2. 右侧信息
            Column(
                modifier = Modifier.padding(start = 16.dp).weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = book.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = book.author,
                    fontSize = 13.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))

                // 来源标签
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = book.sourceName,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // 3. 引导图标
            Icon(
                painter = painterResource(Res.drawable.chevron_right_24px),
                contentDescription = null,
                tint = Color.LightGray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}