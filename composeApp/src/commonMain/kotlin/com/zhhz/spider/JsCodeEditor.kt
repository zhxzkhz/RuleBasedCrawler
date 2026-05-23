package com.zhhz.spider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun JsEditorOverlay(
    title: String,
    initialCode: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {

    var tempCode by remember { mutableStateOf(initialCode) }

    // --- 核心度量衡：确保行号和代码完美对齐的关键 ---
    val scrollState = rememberScrollState() // 共享滚动状态

    // 计算行数
    val lines = tempCode.split("\n")
    val lineCount = lines.size.coerceAtLeast(1)

    // --- 极致对齐的配置 ---
    val fontSize = 14.sp
    val lineHeight = 20.sp // 必须是 sp
    // 强制使用相同的字体，防止 Baseline 偏移
    val fontFamily = FontFamily.Monospace

    val textStyle = TextStyle(
        fontFamily = fontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = Color(0xFFD4D4D4),
        // 关键：强制行高分布，防止行间距累积误差
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )

    // 这里的宽度和高度由你控制，不再受系统窗口管理器限制
    Card(
        modifier = Modifier
            .fillMaxWidth(0.8f) // 占据主窗口 80% 宽度
            .fillMaxHeight(0.8f),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(16.dp)
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {

            // --- 编辑区域 ---
            Row(Modifier.weight(1f).fillMaxWidth()) {

                // 1. 行号侧边栏
                Column(
                    modifier = Modifier
                        .width(45.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF252526))
                        .verticalScroll(scrollState)
                        .padding(top = 12.dp), // 这里的 top 必须和右边代码 Box 的 top 一致
                    horizontalAlignment = Alignment.End
                ) {
                    repeat(lineCount) { i ->
                        // 关键：行号的 Text 直接使用和代码完全一样的 TextStyle
                        Text(
                            text = "${i + 1}",
                            style = textStyle.copy(color = Color(0xFF858585), textAlign = TextAlign.End),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 10.dp)
                        )
                    }
                }

                // 2. 代码编辑主区
                // 2. 代码编辑主区
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(top = 12.dp, start = 12.dp) // top 保持一致
                ) {
                    BasicTextField(
                        value = tempCode,
                        onValueChange = { tempCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = JsHighlighter(),
                        cursorBrush = SolidColor(Color.White),
                        textStyle = textStyle // 使用完全一样的配置
                    )
                }
            }

            // --- 底部操作栏 ---
            HorizontalDivider(color = Color(0xFF333333))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lines: $lineCount",
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )

                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color(0xFFCCCCCC))
                }

                Spacer(Modifier.width(12.dp))

                Button(
                    onClick = { onSave(tempCode); onDismiss() },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC))
                ) {
                    Text("保存脚本", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


class JsHighlighter : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text.text)
        val rawText = text.text

        // 配色方案 (VS Code Dark+ 风格)
        val keywordColor = Color(0xFFC586C0) // 紫色
        val builtinColor = Color(0xFF4FC1FF) // 浅蓝
        val stringColor = Color(0xFFCE9178)  // 橙红
        val numberColor = Color(0xFFB5CEA8)  // 浅绿
        val commentColor = Color(0xFF6A9955) // 幽灵绿

        // 1. 关键字
        "\\b(function|return|var|let|const|if|else|for|while|try|catch|new|in|typeof|instanceof)\\b"
            .toRegex().findAll(rawText).forEach {
                builder.addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1)
            }

        // 2. 内置对象与上下文变量
        "\\b(JSON|console|log|ctx|result|value|java|Math|Object|Array|String|Number)\\b"
            .toRegex().findAll(rawText).forEach {
                builder.addStyle(SpanStyle(color = builtinColor), it.range.first, it.range.last + 1)
            }

        // 3. 字符串 (处理单双引号)
        "(['\"])(?:(?=(\\\\?))\\2.)*?\\1".toRegex().findAll(rawText).forEach {
            builder.addStyle(SpanStyle(color = stringColor), it.range.first, it.range.last + 1)
        }

        // 4. 数字
        "\\b\\d+\\b".toRegex().findAll(rawText).forEach {
            builder.addStyle(SpanStyle(color = numberColor), it.range.first, it.range.last + 1)
        }

        // 5. 注释
        "//.*|/\\*.*?\\*/".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(rawText).forEach {
            builder.addStyle(SpanStyle(color = commentColor), it.range.first, it.range.last + 1)
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}