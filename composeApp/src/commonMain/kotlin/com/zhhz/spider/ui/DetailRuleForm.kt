package com.zhhz.spider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zhhz.spider.rule.DetailPage
import com.zhhz.spider.ui.widget.FetchConfigEditor
import com.zhhz.spider.ui.widget.SelectorEditor

@Composable
fun DetailRuleForm(detailRule: DetailPage?, highlightedSelectorName: String? = null, onOpenJsEditor: (JsEditContext) -> Unit, onUpdate: (DetailPage) -> Unit) {
    if (detailRule == null) {
        Button(onClick = { onUpdate(DetailPage()) }) { Text("+ 创建目录规则") }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FetchConfigEditor("详情页网络配置", detailRule.config,onOpenJsEditor = onOpenJsEditor) { onUpdate(detailRule.copy(config = it)) }
            SelectorEditor("搜索地址 (可选)", detailRule.urlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "DetailPage.urlSelector"), onOpenJsEditor = onOpenJsEditor) {
                onUpdate(detailRule.copy(urlSelector = it))
            }
            SelectorEditor("书名", detailRule.bookNameSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "DetailPage.bookNameSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(detailRule.copy(bookNameSelector = it)) }
            SelectorEditor("作者", detailRule.bookAuthorSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "DetailPage.bookAuthorSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(detailRule.copy(bookAuthorSelector = it)) }
            SelectorEditor("封面", detailRule.bookCoverSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "DetailPage.bookCoverSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(detailRule.copy(bookCoverSelector = it)) }
            SelectorEditor("标签", detailRule.bookLabelSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "DetailPage.bookLabelSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(detailRule.copy(bookLabelSelector = it)) }
            SelectorEditor("目录URL", detailRule.catalogUrlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "DetailPage.catalogUrlSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(detailRule.copy(catalogUrlSelector = it)) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onUpdate(DetailPage()) }) { Text("初始化详情规则", color = Color.Red) }
            }
        }
    }
}
