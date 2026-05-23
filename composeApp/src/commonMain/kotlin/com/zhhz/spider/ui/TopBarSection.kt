package com.zhhz.spider.ui

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
import kotlinx.coroutines.Job
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.play_arrow_24px

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
    onNetworkFetch: () -> Unit, // 联网抓取回调
    onStepRun: () -> Unit, // 新增全链路连跑回调
    onSave: () -> Job
) {

    var showJsonDialog by remember { mutableStateOf(false) }
    // 控制下拉菜单的展开状态
    var menuExpanded by remember { mutableStateOf(false) }
    // 记忆当前选中的运行模式，默认全链路
    var currentMode by remember { mutableStateOf(RunMode.STEP_RUN) }

    var isExport by remember { mutableStateOf(false) }

    var jsonStr by remember { mutableStateOf("") }

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
                .height(32.dp) // 这里可以设置很矮的高度
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp)),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    innerTextField() // 没有任何额外 Padding，高度完全由你控制
                }
            }
        )

        Spacer(Modifier.width(12.dp))

        OutlinedButton(onClick = { onOpen(false) }) { Text("关闭") }

        Spacer(Modifier.width(12.dp))

        OutlinedButton(onClick = {
            isExport = false
            jsonStr = ""
            showJsonDialog = true
        }) { Text("导入") }

        Spacer(Modifier.width(12.dp))

        OutlinedButton(onClick = {
            isExport = true
            jsonStr = JSON.toJSONString(currentRule, JSONWriter.Feature.PrettyFormat)
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
                    Text(" ▾", fontSize = 12.sp, color = Color.Gray) // 纯文本箭头，免去资源引用
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
                // 使用你项目中已有的播放图标
                Icon(
                    painter = painterResource(Res.drawable.play_arrow_24px),
                    contentDescription = null,
                    tint = currentMode.color, // 图标颜色随模式变化，提供视觉暗示
                    modifier = Modifier.size(18.dp)
                )
            }
        }


        // 导出 JSON 弹窗
        if (showJsonDialog) {
            AlertDialog(
                onDismissRequest = { showJsonDialog = false },
                title = { Text("Fastjson2 配置输出") },
                text = {
                    OutlinedTextField(value = jsonStr, onValueChange = {
                        jsonStr = it
                    }, readOnly = isExport, modifier = Modifier.fillMaxWidth().height(400.dp), textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                },
                confirmButton = { Button(onClick = { showJsonDialog = false }) { Text("关闭") } },
                dismissButton = { Button(onClick = {
                    onRuleChange(JSON.parseObject<SourceRule>(jsonStr, SourceRule::class.java))
                    showJsonDialog = false
                }) { Text("导入") } }
            )
        }

    }
}