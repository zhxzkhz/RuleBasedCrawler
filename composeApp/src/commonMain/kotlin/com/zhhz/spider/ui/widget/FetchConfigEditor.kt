package com.zhhz.spider.ui.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.ui.JsEditContext
import com.zhhz.spider.ui.JsInterceptorsSection


@Composable
fun FetchConfigEditor(
    title: String,
    config: FetchConfig,
    onOpenJsEditor: (JsEditContext) -> Unit,
    onUpdate: (FetchConfig) -> Unit
) {
    //var showJsEditor by remember { mutableStateOf(false) }


    val bgColor = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
    Card(elevation = CardDefaults.cardElevation(2.dp), colors = bgColor, border = BorderStroke(1.dp, Color(0xFFE0E0E0))) {

        // --- 第一部分：基础网络请求配置 ---
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 2. 请求方法选择 (GET/POST)
                Box(Modifier.weight(1f)) {
                    val methods = listOf("GET", "POST")
                    Column {
                        Text("请求方法", fontSize = 12.sp, color = Color.Gray)
                        Row {

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                                RadioButton(
                                    selected = methods.indexOf(config.method) < 0,
                                    onClick = { onUpdate(config.copy(method = null)) }
                                )
                                Text("默认", fontSize = 13.sp)
                            }

                            methods.forEach { m ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                                    RadioButton(
                                        selected = config.method == m,
                                        onClick = { onUpdate(config.copy(method = m)) }
                                    )
                                    Text(m, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // 3. 字符编码 (UTF-8/GBK)
                Box(Modifier.weight(0.4f)) {
                    Column {
                        Text("网页编码", fontSize = 12.sp, color = Color.Gray)
                        // 简单的文本输入，也可以改为下拉框
                        OutlinedTextField(
                            value = config.charset ?: "",
                            onValueChange = { onUpdate(config.copy(charset = it)) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            textStyle = TextStyle(fontSize = 13.sp)
                        )
                    }
                }
            }

            // 4. POST 专属配置
            if (config.method == "POST") {
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.isJson ?: false,
                        onCheckedChange = { onUpdate(config.copy(isJson = it)) },
                    )
                    Text("发送 JSON 格式 (Body 为 JSON 字符串)", fontSize = 13.sp)
                }

                OutlinedTextField(
                    value = config.bodyPayload ?: "",
                    onValueChange = { onUpdate(config.copy(bodyPayload = it)) },
                    label = { Text("POST Body (支持 {{key}})") },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    placeholder = { Text("例如：keyword={{key}}&page=1") }
                )
            }
        }

        // --- 第二部分：Header 编辑器 (简单版：一行一个 Key:Value) ---
        Column(Modifier.padding(12.dp)) {
            Text("自定义 Headers", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
            Text("格式：Key:Value，一行一对", fontSize = 11.sp, color = Color.Gray)

            // 将 Map 转为换行字符串以便编辑
            val headerString = config.headers.map { "${it.key}:${it.value}" }.joinToString("\n")

            OutlinedTextField(
                value = headerString,
                onValueChange = { input ->
                    // 解析回 Map
                    val newMap = input.lines()
                        .filter { it.contains(":") }
                        .associate {
                            val parts = it.split(":", limit = 2)
                            parts[0].trim() to parts[1].trim()
                        }
                    onUpdate(config.copy(headers = newMap))
                },
                modifier = Modifier.fillMaxWidth().height(80.dp).padding(top = 8.dp),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            )
        }

        /*
        Column(Modifier.padding(  8.dp)) {
            // 标题行：增加一个微小的图标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(Res.drawable.code_blocks_24px), // 找一个代码/脚本图标
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF673AB7) // 使用 SCRIPT 步骤的紫色
                )
                Spacer(Modifier.width(6.dp))
                Text("动态 Header 脚本 (JavaScript)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
            }

            Spacer(Modifier.height(6.dp))

            // 脚本入口卡片
            Surface(
                onClick = { showJsEditor = true },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                color = Color(0xFFF3E5F5), // 极浅的紫色背景，呼应脚本逻辑
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color(0xFF673AB7).copy(alpha = 0.2f)) // 淡紫色边框
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (config.headerScript.isNullOrBlank()) "点击编写请求头签名逻辑..." else "已配置动态脚本",
                            fontSize = 11.sp,
                            color = if (config.headerScript.isNullOrBlank()) Color.Gray else Color(0xFF673AB7)
                        )
                        if (!config.headerScript.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            // 显示脚本大小，增加“专业感”
                            Text(
                                "[${config.headerScript?.length} chars]",
                                fontSize = 10.sp,
                                color = Color(0xFF673AB7).copy(0.6f),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // 状态图标
                    Icon(
                        painter = if (config.headerScript.isNullOrBlank()) painterResource(Res.drawable.add_24px) else painterResource(Res.drawable.edit_24px) ,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF673AB7)
                    )
                }
            }
        }*/


        // 调用我们之前写的组件
        JsInterceptorsSection(
            config = config,
            onUpdate = onUpdate,
            onOpenJsEditor = onOpenJsEditor
        )



    }
}
