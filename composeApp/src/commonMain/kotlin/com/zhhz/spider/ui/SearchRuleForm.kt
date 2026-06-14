package com.zhhz.spider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhhz.spider.rule.SearchPage
import com.zhhz.spider.ui.widget.FetchConfigEditor
import com.zhhz.spider.ui.widget.SelectorEditor

/**
 * 更新后的搜索规则表单
 * 增加了：请求方法选择、编码选择、JSON开关、POST Body、Header编辑器
 */
@Composable
fun SearchRuleForm(
    searchRule: SearchPage?,
    highlightedSelectorName: String? = null,
    onOpenJsEditor: (JsEditContext) -> Unit,
    onUpdate: (SearchPage) -> Unit
) {
    if (searchRule == null) {
        Button(onClick = { onUpdate(SearchPage()) }) { Text("+ 初始化搜索规则") }
        return
    }
    FetchConfigEditor("搜索页网络配置", searchRule.config,onOpenJsEditor = onOpenJsEditor) { onUpdate(searchRule.copy(config = it)) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {


        Text("内容解析逻辑", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        SelectorEditor("搜索地址 (必填)", searchRule.urlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "SearchPage.urlSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(searchRule.copy(urlSelector = it))
        }

        SelectorEditor("列表容器 (List)", searchRule.listSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "SearchPage.listSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(searchRule.copy(listSelector = it))
        }
        SelectorEditor("书名 (Name)", searchRule.nameSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "SearchPage.nameSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(searchRule.copy(nameSelector = it))
        }
        SelectorEditor("作者 (Author)", searchRule.authorSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "SearchPage.authorSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(searchRule.copy(authorSelector = it))
        }
        SelectorEditor("封面 (Cover)", searchRule.coverSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "SearchPage.coverSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(searchRule.copy(coverSelector = it))
        }
        SelectorEditor("详情链接 (DetailUrl)", searchRule.detailUrlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "SearchPage.detailUrlSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(searchRule.copy(detailUrlSelector = it))
        }

        // 删除按钮
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onUpdate(SearchPage()) }) {
                Text("初始化搜索规则", color = Color.Red)
            }
        }
    }
}
