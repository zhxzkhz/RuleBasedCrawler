package com.zhhz.spider.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.rememberRuleFileActions
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.toDomain
import com.zhhz.spider.rule.toEntity
import com.zhhz.spider.ui.safeJson
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.add_24px
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px
import rulebasedcrawler.composeapp.generated.resources.close_24px
import rulebasedcrawler.composeapp.generated.resources.delete_24px
import rulebasedcrawler.composeapp.generated.resources.edit_24px
import rulebasedcrawler.composeapp.generated.resources.search_24px

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleManagementScreen(
    onNavigateBack: () -> Unit,
    onEditRule: (SourceRule) -> Unit
) {
    val repository = koinInject<RuleRepository>()
    val rules by repository.loadData().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var showAddMenu by remember { mutableStateOf(false) }
    var showTextImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RuleEntity?>(null) }

    fun saveImportedText(content: String) {
        val parsed = runCatching { safeJson.decodeFromString<SourceRule>(content) }
            .getOrElse { error ->
                importText = content
                importError = "JSON 解析失败：${error.message ?: error::class.simpleName}"
                showTextImport = true
                return
            }
        if (rules.any { it.id == parsed.id }) {
            importText = content
            importError = "规则 ID ${parsed.id} 已存在，请使用列表中的“修改”功能"
            showTextImport = true
            return
        }
        scope.launch {
            runCatching { repository.saveData(parsed.toEntity()) }
                .onSuccess {
                    showTextImport = false
                    importText = ""
                    importError = null
                    snackbarHostState.showSnackbar("规则“${parsed.name.ifBlank { parsed.id }}”导入成功")
                }
                .onFailure { snackbarHostState.showSnackbar("规则保存失败：${it.message}") }
        }
    }

    val fileActions = rememberRuleFileActions(
        onOpenResult = { result ->
            result.onSuccess(::saveImportedText)
                .onFailure { scope.launch { snackbarHostState.showSnackbar("文件读取失败：${it.message}") } }
        },
        onSaveResult = {}
    )
    val filteredRules = remember(rules, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) rules else rules.filter {
            it.name.contains(keyword, ignoreCase = true) || it.id.contains(keyword, ignoreCase = true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("规则管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(Res.drawable.arrow_back_24px), "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showAddMenu = true }) {
                    Icon(painterResource(Res.drawable.add_24px), "新增规则")
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("导入文本") },
                        onClick = {
                            showAddMenu = false
                            importText = ""
                            importError = null
                            showTextImport = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("选择文件") },
                        onClick = {
                            showAddMenu = false
                            fileActions.openJsonFile()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("自己编写") },
                        onClick = {
                            showAddMenu = false
                            onEditRule(SourceRule(name = "新规则"))
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("查询规则名称或 ID") },
                leadingIcon = { Icon(painterResource(Res.drawable.search_24px), null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(painterResource(Res.drawable.close_24px), "清空")
                        }
                    }
                }
            )
            Text(
                text = "共 ${rules.size} 条，已启用 ${rules.count { it.isEnabled }} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (filteredRules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (rules.isEmpty()) "暂无规则，点击右下角新增" else "没有匹配的规则",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredRules, key = { it.id }) { entity ->
                        RuleManagementItem(
                            entity = entity,
                            onEnabledChange = { enabled ->
                                scope.launch { repository.saveData(entity.copy(isEnabled = enabled)) }
                            },
                            onEdit = {
                                runCatching { entity.toDomain() }
                                    .onSuccess(onEditRule)
                                    .onFailure {
                                        scope.launch { snackbarHostState.showSnackbar("规则内容损坏：${it.message}") }
                                    }
                            },
                            onDelete = { pendingDelete = entity }
                        )
                    }
                }
            }
        }
    }

    if (showTextImport) {
        AlertDialog(
            onDismissRequest = { showTextImport = false },
            title = { Text("导入规则文本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it; importError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp),
                        placeholder = { Text("粘贴规则 JSON") },
                        isError = importError != null
                    )
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = importText.isNotBlank(),
                    onClick = { saveImportedText(importText) }
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showTextImport = false }) { Text("取消") } }
        )
    }

    pendingDelete?.let { entity ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除规则") },
            text = { Text("确定删除“${entity.name.ifBlank { entity.id }}”吗？此操作不可撤销。") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    scope.launch {
                        runCatching { repository.deleteData(entity) }
                            .onSuccess { snackbarHostState.showSnackbar("规则已删除") }
                            .onFailure { snackbarHostState.showSnackbar("删除失败：${it.message}") }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun RuleManagementItem(
    entity: RuleEntity,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.name.ifBlank { "未命名规则" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "ID: ${entity.id}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = entity.isEnabled, onCheckedChange = onEnabledChange)
            IconButton(onClick = onEdit) {
                Icon(painterResource(Res.drawable.edit_24px), "修改")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(Res.drawable.delete_24px),
                    "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
