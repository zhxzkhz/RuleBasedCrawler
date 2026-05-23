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
import com.zhhz.spider.rule.CatalogPage
import com.zhhz.spider.ui.widget.FetchConfigEditor
import com.zhhz.spider.ui.widget.SelectorEditor


@Composable
fun CatalogRuleForm(catalogRule: CatalogPage?,onOpenJsEditor: (JsEditContext) -> Unit, onUpdate: (CatalogPage) -> Unit) {
    if (catalogRule == null) {
        Button(onClick = { onUpdate(CatalogPage()) }) { Text("+ 创建目录规则") }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FetchConfigEditor("详情页网络配置", catalogRule.config,onOpenJsEditor = onOpenJsEditor) { onUpdate(catalogRule.copy(config = it)) }
            SelectorEditor("搜索地址 (可选)", catalogRule.urlSelector,onOpenJsEditor = onOpenJsEditor) {
                onUpdate(catalogRule.copy(urlSelector = it))
            }
            SelectorEditor("目录列表", catalogRule.chapterListSelector,onOpenJsEditor = onOpenJsEditor) { onUpdate(catalogRule.copy(chapterListSelector = it)) }
            SelectorEditor("章节名称", catalogRule.chapterNameSelector,onOpenJsEditor = onOpenJsEditor) { onUpdate(catalogRule.copy(chapterNameSelector = it)) }
            SelectorEditor("章节链接", catalogRule.chapterUrlSelector,onOpenJsEditor = onOpenJsEditor) { onUpdate(catalogRule.copy(chapterUrlSelector = it)) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onUpdate(CatalogPage()) }) { Text("初始化目录规则", color = Color.Red) }
            }
        }
    }
}