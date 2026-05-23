package com.zhhz.spider.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.rule.FetchConfig
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.ui.widget.FetchConfigEditor
import com.zhhz.spider.ui.widget.LoginRuleForm
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*

@Composable
fun RuleFormWithTabs(
    rule: SourceRule,
    selectedIndex: Int,
    scope : CoroutineScope,
    onOpenJs: (JsEditContext) -> Unit,
    onTabChange: (Int) -> Unit,
    onRuleChange: (SourceRule) -> Unit
) {
    val tabs = listOf("基础信息", "登录页", "搜索页", "详情页", "目录页", "正文页")


    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = selectedIndex, containerColor = Color.White, contentColor = Color(0xFF1976D2)) {
            tabs.forEachIndexed { index, t ->
                Tab(selected = selectedIndex == index, onClick = { onTabChange(index) }, text = { Text(t, fontSize = 12.sp) })
            }
        }

        Box(Modifier.fillMaxSize().padding(12.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    when (selectedIndex) {
                        0 -> { // 基础信息
                            FetchConfigEditor("全局默认网络配置 (Global Config)", rule.globalConfig, onOpenJsEditor = onOpenJs) { onRuleChange(rule.copy(globalConfig = it)) }
                            Spacer(Modifier.height(12.dp))
                            BasicInfoForm(rule, scope){ onRuleChange(it) }
                        }
                        1 -> { // 搜索页
                            LoginRuleForm(rule.login, onOpenJsEditor = onOpenJs){
                                onRuleChange(rule.copy(login = it))
                            }
                        }
                        2 -> { // 搜索页
                            SearchRuleForm(rule.search, onOpenJsEditor = onOpenJs){
                                onRuleChange(rule.copy(search = it))
                            }
                        }
                        3 -> {
                            DetailRuleForm(rule.detail, onOpenJsEditor = onOpenJs){ onRuleChange(rule.copy(detail = it)) }
                        }
                        4 -> {
                            CatalogRuleForm(rule.catalog, onOpenJsEditor = onOpenJs){ onRuleChange(rule.copy(catalog = it)) }
                        }
                        5 -> {
                            ContentRuleForm(rule.content, onOpenJsEditor = onOpenJs){ onRuleChange(rule.copy(content = it)) }
                        }
                    }
                }
                item { Spacer(Modifier.height(50.dp)) }
            }
        }
    }

/*
    // 统一渲染：整个编辑区共用这一个对话框实例
    activeEditContext?.let { ctx ->
        JsEditorDialog(
            title = ctx.title,
            initialCode = ctx.initialCode,
            onDismiss = { activeEditContext = null },
            onSave = { newCode ->
                ctx.onSave(newCode)
                activeEditContext = null
            }
        )
    }
*/

}


@Composable
fun JsInterceptorsSection(
    config: FetchConfig,
    onUpdate: (FetchConfig) -> Unit,
    onOpenJsEditor: (JsEditContext) -> Unit // 接收开启弹窗的回调
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(12.dp)
    ) {
        // 组标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.code_blocks_24px), null, modifier = Modifier.size(16.dp), tint = Color(0xFF673AB7))
            Spacer(Modifier.width(8.dp))
            Text("JS 逻辑拦截器", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
        }

        Spacer(Modifier.height(12.dp))

        // 1. 请求前拦截 (Header Script)
        ScriptSlot(
            label = "请求前：动态 Header 签名",
            script = config.headerScript,
            icon = painterResource(Res.drawable.login_24px), // 进站图标
            onEdit = {
                // 定义：我是谁，我拿什么代码去，我存到哪里
                onOpenJsEditor(JsEditContext(
                    title = "编辑请求头脚本",
                    initialCode = config.headerScript ?: "",
                    onSave = { newCode -> onUpdate(config.copy(headerScript = newCode)) }
                ))
            },
            onClear = { onUpdate(config.copy(headerScript = null)) }
        )

        Spacer(Modifier.height(8.dp))

        // 2. 请求后拦截 (Response Script)
        ScriptSlot(
            label = "请求后：数据解密/清洗",
            script = config.responseScript,
            icon = painterResource(Res.drawable.logout_24px), // 出站图标
            onEdit = {
                onOpenJsEditor(JsEditContext(
                    title = "编辑数据解密脚本",
                    initialCode = config.responseScript ?: "",
                    onSave = { newCode -> onUpdate(config.copy(responseScript = newCode)) }
                ))
            },
            onClear = { onUpdate(config.copy(responseScript = null)) }
        )
    }
}

@Composable
fun ScriptSlot(
    label: String,
    script: String?,
    icon: Painter,
    onEdit: () -> Unit,
    onClear: () -> Unit
) {
    val isConfigured = !script.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(if (isConfigured) Color(0xFFF3E5F5) else Color.White, RoundedCornerShape(4.dp))
            .border(1.dp, if (isConfigured) Color(0xFF673AB7).copy(0.3f) else Color(0xFFEEEEEE), RoundedCornerShape(4.dp))
            .clickable { onEdit() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (isConfigured) Color(0xFF673AB7) else Color.Gray)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (isConfigured) Color(0xFF673AB7) else Color.Gray,
            modifier = Modifier.weight(1f)
        )
        if (isConfigured) {
            Text("[${script.length} chars]", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                Icon(painterResource(Res.drawable.close_24px), null, modifier = Modifier.size(14.dp), tint = Color.Gray)
            }
        } else {
            Icon(painterResource(Res.drawable.chevron_right_24px), null, modifier = Modifier.size(14.dp), tint = Color.LightGray)
        }
    }
}

// 记录当前弹窗的状态
data class JsEditContext(
    val title: String,         // 弹窗标题
    val initialCode: String,   // 初始代码
    val onSave: (String) -> Unit // 【核心】：保存时的回调
)