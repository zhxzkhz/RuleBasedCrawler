package com.zhhz.spider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zhhz.spider.rule.ContentPage
import com.zhhz.spider.rule.ParseStep
import com.zhhz.spider.rule.StepType
import com.zhhz.spider.ui.widget.FetchConfigEditor
import com.zhhz.spider.ui.widget.SelectorEditor
import com.zhhz.spider.ui.widget.StepRow

@Composable
fun ContentRuleForm(contentRule: ContentPage?, highlightedSelectorName: String? = null, onOpenJsEditor: (JsEditContext) -> Unit, onUpdate: (ContentPage) -> Unit) {
    if (contentRule == null) {
        Button(onClick = { onUpdate(ContentPage()) }) { Text("+ 创建正文规则") }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FetchConfigEditor("详情页网络配置", contentRule.config,onOpenJsEditor = onOpenJsEditor) { onUpdate(contentRule.copy(config = it)) }
            SelectorEditor("搜索地址 (可选)", contentRule.urlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ContentPage.urlSelector"), onOpenJsEditor = onOpenJsEditor) {
                onUpdate(contentRule.copy(urlSelector = it))
            }
            SelectorEditor("正文内容", contentRule.contentSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ContentPage.contentSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(contentRule.copy(contentSelector = it)) }
            SelectorEditor("下一页", contentRule.nextUrlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ContentPage.nextUrlSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(contentRule.copy(nextUrlSelector = it)) }

            SelectorEditor("图片头部（ImageHeader）", contentRule.imageHeaders,0,listOf(StepType.CONSTANT, StepType.SCRIPT), highlighted = isSelectorHighlighted(highlightedSelectorName, "ContentPage.imageHeaders"), onOpenJsEditor = onOpenJsEditor) {
                onUpdate(contentRule.copy(imageHeaders = it))
            }


            StepRow(
                index = 0,
                step = ParseStep(StepType.SCRIPT,contentRule.decryptImage),
                onStepChange = { newStep ->
                    onUpdate(contentRule.copy(decryptImage = newStep.rule))
                },
                onDelete = {
                    onUpdate(contentRule.copy(decryptImage = ""))
                },
                onOpenJsEditor = onOpenJsEditor
            )


            SelectorEditor("正则净化", contentRule.regexReplaceSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ContentPage.regexReplaceSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(contentRule.copy(regexReplaceSelector = it)) }
            TextButton(onClick = { onUpdate(ContentPage()) }) { Text("初始化正文规则", color = Color.Red) }
        }
    }
}
