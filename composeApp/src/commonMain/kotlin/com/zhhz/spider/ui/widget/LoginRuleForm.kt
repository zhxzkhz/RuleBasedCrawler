package com.zhhz.spider.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.zhhz.spider.rule.LoginPage
import com.zhhz.spider.ui.JsEditContext

@Composable
fun LoginRuleForm(loginPage: LoginPage, highlightedSelectorName: String? = null, onOpenJsEditor: (JsEditContext) -> Unit, onUpdate: (LoginPage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. 登录请求的 Header/Method/Body (复用 FetchConfigEditor)
        FetchConfigEditor("登录网络配置", loginPage.config,onOpenJsEditor = onOpenJsEditor) { onUpdate(loginPage.copy(config = it)) }


        // 2. 登录的 URL 提取 (复用 SelectorEditor)
        SelectorEditor("登录接口 URL", loginPage.urlSelector, highlighted = com.zhhz.spider.ui.isSelectorHighlighted(highlightedSelectorName, "LoginPage.urlSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(loginPage.copy(urlSelector = it)) }

        // 3. 登录后的 Token 提取 (复用 SelectorEditor)
        SelectorEditor("Token 提取规则", loginPage.tokenSelector, highlighted = com.zhhz.spider.ui.isSelectorHighlighted(highlightedSelectorName, "LoginPage.tokenSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(loginPage.copy(tokenSelector = it)) }
        SelectorEditor("Token 失效时间规则", loginPage.expiresSelector, highlighted = com.zhhz.spider.ui.isSelectorHighlighted(highlightedSelectorName, "LoginPage.expiresSelector"), onOpenJsEditor = onOpenJsEditor) { onUpdate(loginPage.copy(expiresSelector = it)) }
    }
}
