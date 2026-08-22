package com.zhhz.spider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhhz.spider.rule.ExploreFixedTab
import com.zhhz.spider.rule.ExplorePage
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*
import com.zhhz.spider.ui.widget.FetchConfigEditor
import com.zhhz.spider.ui.widget.SelectorEditor

/**
 * 发现/榜单规则表单：
 * 第一步从入口页解析分类标签（名称+地址），第二步按分类地址解析书籍列表。
 */
@Composable
fun ExploreRuleForm(
    exploreRule: ExplorePage?,
    highlightedSelectorName: String? = null,
    onOpenJsEditor: (JsEditContext) -> Unit,
    onUpdate: (ExplorePage) -> Unit
) {
    if (exploreRule == null) {
        Button(onClick = { onUpdate(ExplorePage()) }) { Text("+ 初始化发现规则") }
        return
    }
    FetchConfigEditor("发现页网络配置", exploreRule.config, onOpenJsEditor = onOpenJsEditor) { onUpdate(exploreRule.copy(config = it)) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        Text("固定分类/榜单（推荐）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "分类地址固定时直接写在这里，无需配置入口页；与下方动态解析二选一",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        exploreRule.fixedTabs.forEachIndexed { index, tab ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tab.title,
                    onValueChange = { newTitle ->
                        onUpdate(exploreRule.copy(fixedTabs = exploreRule.fixedTabs.toMutableList().apply { set(index, tab.copy(title = newTitle)) }))
                    },
                    label = { Text("名称") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = tab.url,
                    onValueChange = { newUrl ->
                        onUpdate(exploreRule.copy(fixedTabs = exploreRule.fixedTabs.toMutableList().apply { set(index, tab.copy(url = newUrl)) }))
                    },
                    label = { Text("列表页地址") },
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
                TextButton(onClick = {
                    onUpdate(exploreRule.copy(fixedTabs = exploreRule.fixedTabs.filterIndexed { i, _ -> i != index }))
                }) {
                    Text("删除", color = Color.Red)
                }
            }
        }
        TextButton(onClick = {
            onUpdate(exploreRule.copy(fixedTabs = exploreRule.fixedTabs + ExploreFixedTab()))
        }) {
            Text("+ 添加固定分类")
        }

        // JS 脚本动态生成固定分类（如按日期/参数批量拼地址）
        ScriptSlot(
            label = "或用 JS 脚本动态生成分类（返回 [{title,url}]）",
            script = exploreRule.tabsScript,
            icon = painterResource(Res.drawable.code_blocks_24px),
            onEdit = {
                onOpenJsEditor(JsEditContext(
                    title = "编辑分类生成脚本",
                    initialCode = exploreRule.tabsScript ?: "",
                    onSave = { newCode -> onUpdate(exploreRule.copy(tabsScript = newCode.ifBlank { null })) }
                ))
            },
            onClear = { onUpdate(exploreRule.copy(tabsScript = null)) }
        )

        Text("或：从入口页解析分类标签", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        SelectorEditor("发现地址 (可选，留空用书源主页)", exploreRule.urlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.urlSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(urlSelector = it))
        }
        SelectorEditor("分类容器 (List)", exploreRule.tabsSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.tabsSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(tabsSelector = it))
        }
        SelectorEditor("分类名称 (Name)", exploreRule.tabNameSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.tabNameSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(tabNameSelector = it))
        }
        SelectorEditor("分类链接 (Url)", exploreRule.tabUrlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.tabUrlSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(tabUrlSelector = it))
        }

        Text("第二步：解析分类下的书籍列表", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "提示：以下选择器留空时，自动复用搜索页的列表结构（大多数网站榜单与搜索列表一致）",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        SelectorEditor("书籍列表 (List)", exploreRule.listSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.listSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(listSelector = it))
        }
        SelectorEditor("书名 (Name)", exploreRule.nameSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.nameSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(nameSelector = it))
        }
        SelectorEditor("作者 (Author)", exploreRule.authorSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.authorSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(authorSelector = it))
        }
        SelectorEditor("封面 (Cover)", exploreRule.coverSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.coverSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(coverSelector = it))
        }
        SelectorEditor("详情链接 (DetailUrl)", exploreRule.detailUrlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "ExplorePage.detailUrlSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(exploreRule.copy(detailUrlSelector = it))
        }

        // 重置按钮
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onUpdate(ExplorePage()) }) {
                Text("初始化发现规则", color = Color.Red)
            }
        }
    }
}
