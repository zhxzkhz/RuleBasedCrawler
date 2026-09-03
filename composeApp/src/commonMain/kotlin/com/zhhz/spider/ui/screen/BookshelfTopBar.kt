package com.zhhz.spider.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.viewModel.BookshelfUiState
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*

/**
 * 书架顶部栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfTopBar(
    uiState: BookshelfUiState,
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onRefresh: () -> Unit,
    onGoToSearch: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenTaskCenter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRuleManagement: () -> Unit,
    onImportBookshelf: () -> Unit,
    onOpenHistory: () -> Unit = {}
) {
    Column {
        if (uiState.isSelectionMode) {
            TopAppBar(
                title = { Text("已选择 ${uiState.selectedBooks.size} 本书") },
                navigationIcon = {
                    IconButton(onClick = onToggleSelectionMode) {
                        Icon(painterResource(Res.drawable.close_24px), "退出管理")
                    }
                },
                actions = {
                    val isAllSelected = uiState.selectedBooks.size == uiState.books.size && uiState.books.isNotEmpty()
                    TextButton(onClick = onSelectAll) {
                        Text(if (isAllSelected) "取消全选" else "全选")
                    }
                }
            )
        } else {
            TopAppBar(
                title = {
                    Column {
                        Text("我的书架", fontWeight = FontWeight.ExtraBold)
                        if (uiState.isRefreshing) {
                            Text(
                                text = "正在更新 ${uiState.refreshCompletedCount}/${uiState.refreshTotalCount}：${uiState.refreshBookTitle}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    TooltippedIconButton("搜索新书", onClick = onGoToSearch) {
                        Icon(painterResource(Res.drawable.search_24px), null)
                    }
                    TooltippedIconButton(
                        tooltip = "更新章节目录",
                        enabled = !uiState.isRefreshing,
                        onClick = onRefresh
                    ) {
                        Icon(painterResource(Res.drawable.refresh_24px), null)
                    }
                    TooltippedIconButton("阅读历史", onClick = onOpenHistory) {
                        Icon(painterResource(Res.drawable.history_24px), null)
                    }

                    var moreMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        TooltippedIconButton(tooltip = "更多", onClick = { moreMenuExpanded = true }) {
                            Icon(painterResource(Res.drawable.more_vert_24px), null)
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("任务中心") },
                                leadingIcon = { Icon(painterResource(Res.drawable.cloud_download_24px), null) },
                                onClick = { moreMenuExpanded = false; onOpenTaskCenter() }
                            )
                            DropdownMenuItem(
                                text = { Text("导入书架") },
                                leadingIcon = { Icon(painterResource(Res.drawable.login_24px), null) },
                                onClick = { moreMenuExpanded = false; onImportBookshelf() }
                            )
                            DropdownMenuItem(
                                text = { Text("榜单") },
                                leadingIcon = { Icon(painterResource(Res.drawable.list_24px), null) },
                                onClick = { moreMenuExpanded = false; onOpenExplore() }
                            )
                            DropdownMenuItem(
                                text = { Text("规则管理") },
                                leadingIcon = { Icon(painterResource(Res.drawable.code_blocks_24px), null) },
                                onClick = { moreMenuExpanded = false; onOpenRuleManagement() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("下载设置") },
                                leadingIcon = { Icon(painterResource(Res.drawable.settings_24px), null) },
                                onClick = { moreMenuExpanded = false; onOpenSettings() }
                            )
                        }
                    }
                }
            )
        }
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                progress = {
                    if (uiState.refreshTotalCount == 0) 0f
                    else uiState.refreshCompletedCount.toFloat() / uiState.refreshTotalCount
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 带悬停提示的图标按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltippedIconButton(
    tooltip: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = tooltipState,
        enableUserInput = enabled
    ) {
        IconButton(onClick = onClick, enabled = enabled, content = content)
    }
}
