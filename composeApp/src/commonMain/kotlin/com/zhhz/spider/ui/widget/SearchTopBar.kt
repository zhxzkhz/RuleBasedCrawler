package com.zhhz.spider.ui.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px

@Composable
fun SearchTopBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    // 增加一个带背景的容器
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(24.dp), // 圆角让它更像搜索框
        color = Color(0xFFF5F5F5), // 浅灰色背景
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(painterResource(Res.drawable.arrow_back_24px), "返回", tint = Color.DarkGray)
            }

            BasicTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp),
                decorationBox = { inner ->
                    if (keyword.isEmpty()) Text("搜索书名、作者...", color = Color.Gray, fontSize = 14.sp)
                    inner()
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchClick() })
            )

            // 将“搜索”文字改为带背景的小按钮，或者直接用图标
            TextButton(
                onClick = onSearchClick,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text("搜索", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}