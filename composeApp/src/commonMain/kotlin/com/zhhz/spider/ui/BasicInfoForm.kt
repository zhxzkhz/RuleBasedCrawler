package com.zhhz.spider.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.rule.SourceRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.refresh_24px

@Composable
fun BasicInfoForm(rule: SourceRule, scope : CoroutineScope, onRuleChange: (SourceRule) -> Unit) {

    val dao = koinInject<RuleDao>()

    val session by dao.getSessionFlow(rule.id).collectAsState(null)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        //SectionHeader("源基础配置")

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. 源名称
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { onRuleChange(rule.copy(name = it)) },
                    label = { Text("源名称") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = rule.url,
                    onValueChange = { onRuleChange(rule.copy(url = it)) },
                    label = { Text("网站地址") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = rule.proxyUrl ?: "",
                    onValueChange = { onRuleChange(rule.copy(proxyUrl = it)) },
                    label = { Text("网络代理(可选)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = rule.customDns ?: "",
                    onValueChange = { onRuleChange(rule.copy(customDns = it)) },
                    label = { Text("dns代理(可选)") },
                    modifier = Modifier.fillMaxWidth()
                )



                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically, // 垂直居中对齐
                    horizontalArrangement = Arrangement.spacedBy(16.dp) // 左右两块之间的间距
                ) {
                    Row(
                        modifier = Modifier.weight(1f), // 占据左半部分
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("是否登录", fontSize = 14.sp)
                        Checkbox(
                            checked = rule.requireLogin,
                            onCheckedChange = { onRuleChange(rule.copy(requireLogin = it)) }
                        )
                    }

                    if (rule.requireLogin) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 2. 根据 session 是否为空，动态显示状态
                            val statusColor = if (session != null) Color(0xFF4CAF50) else Color(0xFF9E9E9E)

                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(statusColor, CircleShape)
                            )

                            Spacer(Modifier.width(6.dp))

                            Text(
                                text = if (session != null) "已登录" else "未登录",
                                fontSize = 11.sp,
                                color = statusColor
                            )

                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { dao.deleteSession(rule.id) }
                            }) {
                                Icon(painterResource(Res.drawable.refresh_24px), "清除登录状态", tint = Color.Gray)
                            }
                        }
                    }

                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically, // 垂直居中对齐
                    horizontalArrangement = Arrangement.spacedBy(16.dp) // 左右两块之间的间距
                ) {

                    // --- 左侧：数据缓存 ---
                    Row(
                        modifier = Modifier.weight(1f), // 占据左半部分
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("数据缓存", fontSize = 14.sp)
                        Checkbox(
                            checked = rule.useCache,
                            onCheckedChange = { onRuleChange(rule.copy(useCache = it)) }
                        )
                    }

                    // --- 右侧：抓取间隔 ---
                    Row(
                        modifier = Modifier.weight(1f), // 占据右半部分
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End // 将内容推向右侧对齐
                    ) {
                        Text("抓取间隔 (ms):", fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        // 使用你之前的 SlimTextField 或基础 TextField
                        BasicTextField(
                            value = rule.concurrentRate.toString(),
                            onValueChange = {
                                // 仅允许输入数字
                                val newValue = it.filter { char -> char.isDigit() }.toLongOrNull() ?: 0L
                                onRuleChange(rule.copy(concurrentRate = newValue))
                            },
                            modifier = Modifier
                                .width(80.dp) // 固定输入框宽度
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 13.sp)
                        )
                    }
                }

            }
        }
    }
}