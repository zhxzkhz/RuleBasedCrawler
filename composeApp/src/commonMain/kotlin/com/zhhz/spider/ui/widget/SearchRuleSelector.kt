package com.zhhz.spider.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhhz.spider.viewModel.SearchRuleOption
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.close_24px
import rulebasedcrawler.composeapp.generated.resources.filter_list_24px
import rulebasedcrawler.composeapp.generated.resources.search_24px

@Composable
fun SearchRuleSelector(
    rules: List<SearchRuleOption>,
    selectedRuleId: String?,
    isPreciseSearch: Boolean,
    isIdSearch: Boolean,
    onRuleSelected: (String?) -> Unit,
    onPreciseSearchChange: (Boolean) -> Unit,
    onIdSearchChange: (Boolean) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedRule = rules.firstOrNull { it.id == selectedRuleId }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(Res.drawable.filter_list_24px),
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { showDialog = true },
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .clickable { showDialog = true }
            ) {
                Text(
                    text = "搜索范围",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = selectedRule?.name ?: "全部规则",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            VerticalDivider(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedRule?.supportsIdSearch == true) {
                    SearchModeCheckbox(
                        label = "ID 搜索",
                        checked = isIdSearch,
                        onCheckedChange = onIdSearchChange
                    )
                }
                SearchModeCheckbox(
                    label = "精准搜索",
                    checked = isPreciseSearch,
                    enabled = !isIdSearch,
                    onCheckedChange = onPreciseSearchChange
                )
            }
        }
    }

    if (showDialog) {
        SearchRuleDialog(
            rules = rules,
            selectedRuleId = selectedRuleId,
            onRuleSelected = { ruleId ->
                onRuleSelected(ruleId)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun SearchRuleDialog(
    rules: List<SearchRuleOption>,
    selectedRuleId: String?,
    onRuleSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filteredRules = remember(rules, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            rules
        } else {
            rules.filter {
                it.name.contains(keyword, ignoreCase = true) ||
                    it.id.contains(keyword, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择搜索范围") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索规则名称或 ID") },
                    leadingIcon = {
                        Icon(painterResource(Res.drawable.search_24px), contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(painterResource(Res.drawable.close_24px), contentDescription = "清空")
                            }
                        }
                    }
                )

                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item(key = "all-rules") {
                        RuleOptionRow(
                            title = "全部规则",
                            subtitle = "同时搜索 ${rules.size} 个已启用规则",
                            selected = selectedRuleId == null,
                            onClick = { onRuleSelected(null) }
                        )
                        HorizontalDivider()
                    }

                    items(filteredRules, key = { it.id }) { rule ->
                        RuleOptionRow(
                            title = rule.name,
                            subtitle = rule.id + if (rule.supportsIdSearch) " · 支持 ID 搜索" else "",
                            selected = selectedRuleId == rule.id,
                            onClick = { onRuleSelected(rule.id) }
                        )
                    }

                    if (filteredRules.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = "没有匹配的规则",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SearchModeCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
        )
    }
}

@Composable
private fun RuleOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
