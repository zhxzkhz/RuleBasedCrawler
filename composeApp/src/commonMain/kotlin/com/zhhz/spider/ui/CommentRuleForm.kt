package com.zhhz.spider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhhz.spider.rule.CommentPage
import com.zhhz.spider.ui.widget.FetchConfigEditor
import com.zhhz.spider.ui.widget.SelectorEditor

/**
 * 评论规则表单：urlSelector 生成评论地址（{{key}}=详情URL，{{page}} 翻页），
 * 列表项解析出用户/内容，时间/评分/头像可选。
 */
@Composable
fun CommentRuleForm(
    commentRule: CommentPage?,
    highlightedSelectorName: String? = null,
    onOpenJsEditor: (JsEditContext) -> Unit,
    onUpdate: (CommentPage) -> Unit
) {
    if (commentRule == null) {
        Button(onClick = { onUpdate(CommentPage()) }) { Text("+ 初始化评论规则") }
        return
    }
    FetchConfigEditor("评论页网络配置", commentRule.config, onOpenJsEditor = onOpenJsEditor) { onUpdate(commentRule.copy(config = it)) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        Text("评论地址与列表", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        SelectorEditor("评论地址 (必填)", commentRule.urlSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.urlSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(urlSelector = it))
        }
        Text(
            "支持 {{key}} 占位书籍详情 URL、{{page}} 翻页",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        SelectorEditor("评论列表 (List)", commentRule.listSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.listSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(listSelector = it))
        }
        SelectorEditor("用户名", commentRule.userSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.userSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(userSelector = it))
        }
        SelectorEditor("评论内容 (必填)", commentRule.contentSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.contentSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(contentSelector = it))
        }
        SelectorEditor("楼中楼回复列表 (可选)", commentRule.replyListSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.replyListSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(replyListSelector = it))
        }
        Text(
            "配置后每条评论下的回复（如 $.replys[*]）会缩进平铺展示，复用上方的用户/内容选择器",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        Text("可选字段", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        SelectorEditor("时间", commentRule.timeSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.timeSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(timeSelector = it))
        }
        SelectorEditor("评分", commentRule.scoreSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.scoreSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(scoreSelector = it))
        }
        SelectorEditor("头像", commentRule.avatarSelector, highlighted = isSelectorHighlighted(highlightedSelectorName, "CommentPage.avatarSelector"), onOpenJsEditor = onOpenJsEditor) {
            onUpdate(commentRule.copy(avatarSelector = it))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onUpdate(CommentPage()) }) {
                Text("初始化评论规则", color = Color.Red)
            }
        }
    }
}
