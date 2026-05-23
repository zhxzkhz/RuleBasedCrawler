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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.rule.ExtractType
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.StepType
import com.zhhz.spider.ui.JsEditContext
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.close_24px

@Composable
fun StepRow(index: Int, step: ParseStep, onStepChange: (ParseStep) -> Unit, onDelete: () -> Unit, onOpenJsEditor: (JsEditContext) -> Unit) {

    val stepColor = when (step.type) {
        StepType.CSS -> Color(0xFF1976D2)
        StepType.XPATH -> Color(0xFF0097A7)
        StepType.REGEX -> Color(0xFFF57C00)
        StepType.JSON -> Color(0xFF388E3C)
        StepType.REPLACE -> Color(0xFFE91E63)
        StepType.SCRIPT -> Color(0xFF673AB7)
        StepType.CONSTANT -> Color(0xFF607D8B)
        StepType.TEMPLATE -> Color(0xFFFF8F00)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFFE0E0E0))
            .padding(4.dp)
    ) {
        // 1. 序号
        Text("${index + 1}.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.width(24.dp))

        // 2. 步骤类型切换 (CSS, XPATH等)
        Button(
            onClick = {
                val nextType = StepType.entries[(step.type.ordinal + 1) % StepType.entries.size]
                onStepChange(step.copy(type = nextType))
            },
            colors = ButtonDefaults.buttonColors(containerColor = stepColor),
            modifier = Modifier.width(68.dp).height(28.dp),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(step.type.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.width(8.dp))

        // 3. 表达式输入区
        if (step.type == StepType.SCRIPT) {
            Box(
                modifier = Modifier.weight(1f).height(28.dp).background(Color(0xFFFFF3E0), RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (step.rule.isEmpty()) "空脚本" else "JS (${step.rule.length} char)",
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onOpenJsEditor(JsEditContext(
                                title = "编辑请求头脚本",
                                initialCode = step.rule,
                                onSave = { newCode -> onStepChange(step.copy(rule = newCode)) }
                            ))
                        },
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("编辑", fontSize = 10.sp, color = Color(0xFFFF9800)) }
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).height(28.dp).background(Color(0xFFF5F5F5), RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp)
            ) {
                BasicTextField(
                    value = step.rule,
                    onValueChange = { onStepChange(step.copy(rule = it)) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxSize(),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            val hint = if (step.type == StepType.TEMPLATE) "例: https://site.com/{{key}}" else "输入表达式..."
                            if (step.rule.isEmpty()) Text(
                                hint,
                                color = Color.LightGray,
                                fontSize = 11.sp
                            ); inner()
                        }
                    }
                )
            }
        }

        // --- 核心改动：ExtractType 与 Attr/Repl 联动区 ---

        // 4.1 CSS/XPATH 特有的提取类型切换
        if (step.type == StepType.CSS || step.type == StepType.XPATH) {
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    val nextEType = ExtractType.entries[(step.extractType.ordinal + 1) % ExtractType.entries.size]
                    onStepChange(step.copy(extractType = nextEType))
                },
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                border = BorderStroke(1.dp, Color(0xFF1976D2).copy(0.3f)),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1976D2))
            ) {
                Text(step.extractType.name, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            // 4.2 只有当 ExtractType 为 ATTR 时，才显示属性名输入框
            if (step.extractType == ExtractType.ATTR) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier.width(70.dp).height(28.dp)
                        .background(Color(0xFFF0F8FF), RoundedCornerShape(2.dp))
                        .border(0.5.dp, Color(0xFF1976D2).copy(0.2f)).padding(horizontal = 6.dp)
                ) {
                    BasicTextField(
                        value = step.attr ?: "",
                        modifier = Modifier.fillMaxSize(),
                        onValueChange = { onStepChange(step.copy(attr = it.ifEmpty { null })) },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF1976D2)
                        ),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (step.attr.isNullOrEmpty()) Text(
                                    "Attr",
                                    modifier = Modifier.fillMaxHeight(),
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                ); inner()
                            }
                        }
                    )
                }
            }
        }

        // 4.3 REPLACE 保持原有逻辑，不使用 ExtractType
        if (step.type == StepType.REPLACE) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.width(80.dp).height(28.dp).background(Color(0xFFFFF0F0), RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp)
            ) {
                BasicTextField(
                    value = step.replacement,
                    modifier = Modifier.fillMaxSize(),
                    onValueChange = { onStepChange(step.copy(replacement = it)) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp), singleLine = true,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (step.replacement.isEmpty()) Text(
                                "Repl",
                                color = Color.Gray,
                                fontSize = 10.sp
                            ); inner()
                        }
                    }
                )
            }
        }

        // 5. 操作区 (List + 删除)
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = step.isList,
                onCheckedChange = { onStepChange(step.copy(isList = it)) },
                modifier = Modifier.size(24.dp)
            )
            Text("List", fontSize = 10.sp, color = Color.Gray)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                painterResource(Res.drawable.close_24px),
                null,
                tint = Color.Red.copy(0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }

}