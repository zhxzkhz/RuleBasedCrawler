package com.zhhz.spider.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.db.RuleEntity
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.filter_list_24px

@Composable
fun SourceSelectorRow(rules: List<RuleEntity>, selectedId: String?,onOpen: (Boolean) -> Unit, onSelect: (RuleEntity) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        OutlinedButton(onClick = { onOpen(true) }) { Text("编辑") }

        Spacer(Modifier.width(12.dp))

        Icon(painterResource(Res.drawable.filter_list_24px), null, modifier = Modifier.size(16.dp), tint = Color.Gray)
        Spacer(Modifier.width(8.dp))
        Text("选择来源:", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)

        LazyRow(Modifier.weight(1f).padding(start = 8.dp)) {
            items(rules) { rule ->
                val isSelected = rule.id == selectedId
                SuggestionChip(
                    onClick = { onSelect(rule) },
                    label = { Text(rule.name, fontSize = 11.sp) },
                    modifier = Modifier.padding(end = 6.dp),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        labelColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray
                    )
                )
            }
        }
    }
}