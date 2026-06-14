package com.zhhz.spider.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONWriter
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.viewModel.MangaImage
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.play_arrow_24px
import rulebasedcrawler.composeapp.generated.resources.save_24px

// 💡 1. 声明一个极度宽容、抗揍的爬虫专用序列化配置
val safeJson = Json {
    ignoreUnknownKeys = true // 💡 核心：忽略未知字段！JSON 里多出什么牛鬼蛇神字段都不会报错，直接跳过！
    coerceInputValues = true // 如果类型不对（比如本来是数字却传了空字符串），自动进行类型保底
    isLenient = true         // 宽容模式，允许格式不那么规范的 JSON
    prettyPrint = true      // 格式化输出，方便调试
}

enum class RunMode(val label: String, val color: Color) {
    STEP_RUN("⚡ 全链路连跑", Color(0xFF673AB7)),
    NETWORK("🌐 网络抓取", Color(0xFFE91E63)),
    LOCAL("📂 本地解析", Color(0xFF4CAF50))
}


@Composable
fun TopBarSection(
    selectedTabIndex: Int,
    inputText: String,
    onInputChange: (String) -> Unit,
    currentRule: SourceRule,
    onRuleChange: (SourceRule) -> Unit,
    onShowSelectDialogChange: () -> Unit,
    onOpen: (Boolean) -> Unit,
    onLocalTest: () -> Unit,
    onNetworkFetch: () -> Unit,
    onStepRun: () -> Unit,
    onSave: () -> Job
) {
    var showJsonDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var mobileMenuExpanded by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(RunMode.STEP_RUN) }

    var isExport by remember { mutableStateOf(false) }
    var jsonStr by remember { mutableStateOf("") }
    var jsonParseError by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().background(Color.White)) {
        val isMobile = maxWidth < 768.dp

        if (isMobile) {
            // ==========================================
            // 📱 手机端：经过精心排版、对齐、配色流动的最高规顶栏
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // --- 第一行：标题、显式保存与文件菜单 ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🕷️ IDE",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 💡 1. 核心修正：使用极简的“保存”文本按钮，语义清晰，再也不会和运行图标打架！
                        /*
                        TextButton(
                            onClick = { onSave() },
                            modifier = Modifier.width(80.dp).height(36.dp).border(1.dp, currentMode.color.copy(alpha = 0.4f), RoundedCornerShape(6.dp)), // 动态边框
                        ) {
                            Image(
                                painter = painterResource(Res.drawable.save_24px),
                                contentDescription = "保存",
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                "保存",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                            )
                        }*/


                        Button(
                            onClick = { onSave() },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            // 💡 利用 Row 轻松实现图文并排和间距控制
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.save_24px), // 你的下载图标
                                    contentDescription = "保存",
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "保存",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // 💡 2. 干净的“文件”折叠菜单
                        Box(
                            modifier = Modifier.width(80.dp).height(36.dp).border(1.dp, currentMode.color.copy(alpha = 0.4f), RoundedCornerShape(6.dp)), // 动态边框
                        ) {
                            TextButton(
                                onClick = { mobileMenuExpanded = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    "文件 ▾",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            DropdownMenu(
                                expanded = mobileMenuExpanded,
                                onDismissRequest = { mobileMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重选书源", fontSize = 13.sp) },
                                    onClick = { mobileMenuExpanded = false; onShowSelectDialogChange() }
                                )
                                DropdownMenuItem(
                                    text = { Text("导入书源 (JSON)", fontSize = 13.sp) },
                                    onClick = {
                                        mobileMenuExpanded = false
                                        isExport = false
                                        jsonStr = ""
                                        jsonParseError = null
                                        showJsonDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("导出书源 (JSON)", fontSize = 13.sp) },
                                    onClick = {
                                        mobileMenuExpanded = false
                                        isExport = true
                                        jsonStr = safeJson.encodeToString(currentRule)
                                        jsonParseError = null
                                        showJsonDialog = true
                                    }
                                )
                                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                                DropdownMenuItem(
                                    text = { Text("关闭并返回", fontSize = 13.sp) },
                                    onClick = { mobileMenuExpanded = false; onOpen(false) }
                                )
                            }
                        }
                    }
                }

                // --- 第二行：输入框与模式运行器（高度绝对对齐 + 动态变色） ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 💡 3. 优化：灰底圆角输入框，高度锁定为 36dp
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp) // 👈 统一锁定 36dp
                            .background(Color(0xFFF5F5F5), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp)),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = Color.Black),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        "输入 URL 或关键字...",
                                        color = Color.Gray.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // 💡 4. 终极设计：高度完美对齐 36dp，且边框、背景和文本颜色跟随【运行模式（currentMode）】动态变幻！
                    Row(
                        modifier = Modifier
                            .height(36.dp) // 👈 统一锁定 36dp
                            .background(currentMode.color.copy(alpha = 0.08f), RoundedCornerShape(6.dp)) // 动态浅色背景
                            .border(1.dp, currentMode.color.copy(alpha = 0.4f), RoundedCornerShape(6.dp)), // 动态边框
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // A. 模式切换下拉框
                        Box {
                            TextButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                // 截取掉 emoji 前缀，让手机端文字更精简
                                val labelText = currentMode.label.substring(2)
                                Text(
                                    labelText,
                                    fontSize = 12.sp,
                                    color = currentMode.color,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(" ▾", fontSize = 12.sp, color = currentMode.color)
                            }

                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                RunMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label, fontSize = 12.sp) },
                                        onClick = {
                                            currentMode = mode
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // B. 垂直分割线（颜色也跟随模式对齐）
                        Box(Modifier.width(1.dp).height(20.dp).background(currentMode.color.copy(alpha = 0.2f)))

                        // C. 唯一的播放运行按钮
                        IconButton(
                            onClick = {
                                when (currentMode) {
                                    RunMode.STEP_RUN -> onStepRun()
                                    RunMode.NETWORK -> onNetworkFetch()
                                    RunMode.LOCAL -> onLocalTest()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.play_arrow_24px),
                                contentDescription = null,
                                tint = currentMode.color, // 💡 颜色完全对齐
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // 💻 电脑桌面端：保持你原有的完美整排横向布局 (保持完全不变)
            // ==========================================
            // ==========================================
            // 💻 电脑桌面端：保持你原有的完美整排横向布局
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp).background(Color.White).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🕷️ IDE", fontWeight = FontWeight.Bold, fontSize = 18.sp)

                Spacer(Modifier.width(20.dp))

                // 智能输入框：搜索页输入关键词，其他页输入 URL
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .width(140.dp)
                        .height(32.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp)),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            innerTextField()
                        }
                    }
                )

                Spacer(Modifier.width(12.dp))

                OutlinedButton(onClick = { onOpen(false) }) { Text("关闭") }

                Spacer(Modifier.width(12.dp))

                OutlinedButton(onClick = {
                    isExport = false
                    jsonStr = ""
                    jsonParseError = null
                    showJsonDialog = true
                }) { Text("导入") }

                Spacer(Modifier.width(12.dp))

                OutlinedButton(onClick = {
                    isExport = true
                    // 💡 彻底清除 Fastjson 序列化，全盘改用干净、全平台兼容的 safeJson！
                    jsonStr = safeJson.encodeToString(currentRule)
                    jsonParseError = null
                    showJsonDialog = true
                }) { Text("导出") }

                Spacer(Modifier.width(12.dp))

                OutlinedButton(onClick = { onSave() }) { Text("保存") }

                Spacer(Modifier.width(12.dp))

                OutlinedButton(onClick = { onShowSelectDialogChange() }) { Text("重选") }

                Spacer(Modifier.width(12.dp))

                // --- 核心重构：组合式运行选择器 ---
                Row(
                    modifier = Modifier.height(32.dp).border(1.dp, Color.LightGray, RoundedCornerShape(4.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. 模式选择下拉框
                    Box {
                        TextButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(currentMode.label, fontSize = 12.sp, color = Color.DarkGray)
                            Text(" ▾", fontSize = 12.sp, color = Color.Gray)
                        }

                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            RunMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label, fontSize = 12.sp) },
                                    onClick = {
                                        currentMode = mode
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // 2. 垂直分割线
                    Box(Modifier.width(1.dp).height(20.dp).background(Color.LightGray))

                    // 3. 统一的执行按钮 (Play 键)
                    IconButton(
                        onClick = {
                            when (currentMode) {
                                RunMode.STEP_RUN -> onStepRun()
                                RunMode.NETWORK -> onNetworkFetch()
                                RunMode.LOCAL -> onLocalTest()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.play_arrow_24px),
                            contentDescription = null,
                            tint = currentMode.color,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (showJsonDialog) {
            AlertDialog(
                onDismissRequest = { showJsonDialog = false },
                title = { Text("爬虫规则 JSON 配置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = jsonStr,
                            onValueChange = {
                                jsonStr = it
                                jsonParseError = null
                            },
                            readOnly = isExport,
                            modifier = Modifier.fillMaxWidth().height(400.dp),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            isError = jsonParseError != null
                        )
                        jsonParseError?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showJsonDialog = false }) { Text("关闭") }
                },
                dismissButton = {
                    if (!isExport) {
                        Button(onClick = {
                            val parsedRule = runCatching {
                                safeJson.decodeFromString<SourceRule>(jsonStr)
                            }.getOrElse { error ->
                                jsonParseError = "JSON 格式解析失败：${error.message ?: error::class.simpleName}"
                                return@Button
                            }

                            onRuleChange(parsedRule)
                            jsonParseError = null
                            showJsonDialog = false
                        }) { Text("导入") }
                    }
                }
            )
        }
    }
}
