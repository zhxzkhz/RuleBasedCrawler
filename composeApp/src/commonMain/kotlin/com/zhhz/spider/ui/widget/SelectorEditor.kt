package com.zhhz.spider.ui.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.Selector
import com.zhhz.spider.rule.StepType
import com.zhhz.spider.ui.JsEditContext
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.list_24px

@Composable
fun SelectorEditor1(
    title: String,
    selector: Selector,
    level: Int = 0,
    onSelectorChange: (Selector) -> Unit,
    onOpenJsEditor: (JsEditContext) -> Unit
) {
    val bgColor = if (level == 0) Color(0xFFFAFAFA) else Color(0xFFFFF8E1)
    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, if (level == 0) Color.LightGray else Color(0xFFFFB74D)),
        modifier = Modifier.fillMaxWidth().padding(start = (level * 16).dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (level == 0) Icon(
                    painterResource(Res.drawable.list_24px),
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.Blue
                ) else Text("↳", fontSize = 16.sp, color = Color(0xFFFF9800))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (level == 0) title else "备选规则",
                    fontWeight = FontWeight.Bold,
                    fontSize = if (level == 0) 14.sp else 13.sp
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), DividerDefaults.Thickness, DividerDefaults.color)

            selector.steps.forEachIndexed { index, step ->
                StepRow(
                    index = index, step = step,
                    onStepChange = { newStep ->
                        val s = selector.steps.toMutableList(); s[index] =
                        newStep; onSelectorChange(selector.copy(steps = s))
                    },
                    onDelete = {
                        val s =
                            selector.steps.toMutableList(); s.removeAt(index); onSelectorChange(selector.copy(steps = s))
                    },
                    onOpenJsEditor = onOpenJsEditor
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    onSelectorChange(selector.copy(steps = selector.steps + ParseStep()))
                },
                modifier = Modifier
                    .fillMaxWidth() // 铺满宽度，方便点击
                    .height(32.dp),
                contentPadding = PaddingValues(0.dp),
                // 使用淡蓝色边框，既专业又不突兀
                border = BorderStroke(1.dp, Color(0xFF1976D2).copy(alpha = 0.3f)),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF1976D2).copy(alpha = 0.05f),
                    contentColor = Color(0xFF1976D2)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // 使用加号文本或图标
                    Text(
                        text = "+",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp) // 微调加号位置
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "添加解析步骤",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))


            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSelectorChange(selector.copy(steps = selector.steps + ParseStep())) },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(Color.White)
                ) { Text("+ 步骤", fontSize = 11.sp, color = Color.Black) }
                Spacer(Modifier.width(16.dp))
                Text("失败默认值:", fontSize = 11.sp, color = Color.Gray)
                BasicTextField(
                    value = selector.defaultValue,
                    onValueChange = { onSelectorChange(selector.copy(defaultValue = it)) },
                    modifier = Modifier.width(100.dp).background(Color.White).border(1.dp, Color.LightGray)
                        .padding(4.dp),
                    textStyle = TextStyle(fontSize = 11.sp)
                )
            }

            Spacer(Modifier.height(12.dp))
            if (selector.fallback != null) {
                SelectorEditor1(
                    "备选",
                    selector.fallback,
                    level + 1,
                    { onSelectorChange(selector.copy(fallback = it)) },
                    onOpenJsEditor
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onSelectorChange(selector.copy(fallback = null)) },
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            "🗑 删除备选",
                            modifier = Modifier.height(40.dp).padding(0.dp),
                            color = Color.Red,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { onSelectorChange(selector.copy(fallback = Selector())) },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFB74D).copy(0.5f))
                ) { Text("↳ 添加备选策略", color = Color(0xFFEF6C00), fontSize = 11.sp) }
            }
        }
    }
}

@Composable
fun SelectorEditor(
    title: String,
    selector: Selector,
    level: Int = 0, // 用于递归缩进
    onOpenJsEditor: (JsEditContext) -> Unit,
    onSelectorChange: (Selector) -> Unit
) {
    SelectorEditor(title, selector, level, emptyList(), onOpenJsEditor, onSelectorChange)
}

@Composable
fun SelectorEditor(
    title: String,
    selector: Selector,
    level: Int = 0, // 用于递归缩进
    typeList: List<StepType>,
    onOpenJsEditor: (JsEditContext) -> Unit,
    onSelectorChange: (Selector) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (level * 12).dp) // 递归时的缩进
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
    ) {
        // --- 1. 标题栏 (仅在顶层显示) ---
        if (level == 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
            }
            HorizontalDivider(color = Color(0xFFF0F0F0))
        }

        // --- 2. 步骤列表区 ---
        Column(Modifier.padding(12.dp)) {
            selector.steps.forEachIndexed { index, step ->
                StepRow(
                    index = index,
                    step = step,
                    types = typeList,
                    onStepChange = { newStep ->
                        val newList = selector.steps.toMutableList()
                        newList[index] = newStep
                        onSelectorChange(selector.copy(steps = newList))
                    },
                    onDelete = {
                        val newList = selector.steps.toMutableList()
                        newList.removeAt(index)
                        onSelectorChange(selector.copy(steps = newList))
                    },
                    onOpenJsEditor = onOpenJsEditor
                )
                Spacer(Modifier.height(6.dp))
            }

            // 添加步骤按钮 (对齐 Badge 线下)
            TextButton(
                onClick = {
                    onSelectorChange(
                        selector.copy(
                            steps = selector.steps + ParseStep(
                                type = if (typeList.isEmpty()) StepType.CSS else typeList.first()
                            )
                        )
                    )
                },
                modifier = Modifier.padding(start = 10.dp).height(32.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text("+ 添加解析步骤", fontSize = 12.sp, color = Color(0xFF1976D2), fontWeight = FontWeight.Medium)
            }
        }

        // --- 3. 逻辑页脚 (处理异常与备选) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8F9FA))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .drawBehind {
                    drawLine(Color(0xFFEEEEEE), Offset(0f, 0f), Offset(size.width, 0f), 1f)
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧：备选策略开关
                if (selector.fallback == null) {
                    TextButton(
                        onClick = { onSelectorChange(selector.copy(fallback = Selector())) },
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("↳ 添加备选策略", fontSize = 11.sp, color = Color(0xFFEF6C00))
                    }
                } else {
                    Text("已启用备选策略", fontSize = 11.sp, color = Color(0xFFEF6C00), fontWeight = FontWeight.Bold)
                }

                // 右侧：兜底值配置
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("失败默认值:", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = selector.defaultValue,
                        onValueChange = { onSelectorChange(selector.copy(defaultValue = it)) },
                        modifier = Modifier
                            .width(100.dp)
                            .height(24.dp)
                            .background(Color.White, RoundedCornerShape(2.dp))
                            .border(1.dp, Color(0xFFD1D1D1), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (selector.defaultValue.isEmpty()) Text(
                                    "NULL",
                                    color = Color(0xFFCCCCCC),
                                    fontSize = 10.sp
                                )
                                inner()
                            }
                        }
                    )
                }
            }

            // 递归渲染备选策略
            selector.fallback?.let { fb ->
                Spacer(Modifier.height(8.dp))
                SelectorEditor(
                    title = "备选",
                    selector = fb,
                    level = level + 1,
                    onSelectorChange = { onSelectorChange(selector.copy(fallback = it)) },
                    onOpenJsEditor = onOpenJsEditor
                )
                // 删除备选按钮
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onSelectorChange(selector.copy(fallback = null)) }) {
                        Text("移除备选策略", fontSize = 10.sp, color = Color.Red.copy(0.6f))
                    }
                }
            }
        }
    }
}