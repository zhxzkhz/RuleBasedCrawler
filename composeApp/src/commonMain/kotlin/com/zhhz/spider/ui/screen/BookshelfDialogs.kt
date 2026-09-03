package com.zhhz.spider.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.db.BookGroupEntity
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.viewModel.BookGroupImportMode
import com.zhhz.spider.viewModel.BookshelfUiIntent
import com.zhhz.spider.viewModel.BookshelfUiState
import com.zhhz.spider.viewModel.BookshelfViewModel
import com.zhhz.spider.network.Book
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*

/**
 * 导出选项配置弹窗
 */
@Composable
fun ExportOptionDialog(
    onExport: (onlyCached: Boolean, concurrencyLimit: Int, delayMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var concurrencyLimit by remember { mutableStateOf(4) }
    var delayMs by remember { mutableStateOf(0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出选项配置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("请选择导出的内容范围和限制参数，合理的配置能有效防止 IP 被书源网站封锁。")

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("并发下载线程数：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 8).forEach { thread ->
                            FilterChip(
                                selected = concurrencyLimit == thread,
                                onClick = { concurrencyLimit = thread },
                                label = { Text("$thread 线程") }
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("每张图片下载延迟：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0L to "无延迟", 200L to "200ms", 500L to "500ms").forEach { (ms, text) ->
                            FilterChip(
                                selected = delayMs == ms,
                                onClick = { delayMs = ms },
                                label = { Text(text) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(false, concurrencyLimit, delayMs) }) {
                Text("导出所有章节")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onExport(true, 4, 0L) }) {
                Text("仅导出已缓存章节")
            }
        }
    )
}

/**
 * 导出进度弹窗
 */
@Composable
fun ExportAlertDialog(
    uiState: BookshelfUiState,
    viewModel: BookshelfViewModel
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("正在导出书籍...", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "正在打包：《${uiState.exportBookTitle}》",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(
                    progress = { uiState.exportProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Text(uiState.exportChapterProgress, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.processIntent(BookshelfUiIntent.CancelExport) }) {
                Text("取消导出", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

/**
 * 下载设置内容
 */
@Composable
fun DownloadSettingsContent(
    viewModel: BookshelfViewModel,
    onDismiss: () -> Unit
) {
    val bounds = com.zhhz.spider.settings.SettingsRepository.DOWNLOAD_BOOKS_RANGE
    val imageBounds = com.zhhz.spider.settings.SettingsRepository.DOWNLOAD_IMAGES_RANGE

    var books by remember { mutableIntStateOf(2) }
    var images by remember { mutableIntStateOf(6) }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        val initial = viewModel.loadDownloadSettings()
        books = initial.downloadBooks
        images = initial.downloadImages
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Text("下载并发", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            "同时下载的书本数与全局图片数，保存后立即生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        StepperRow(label = "同时下载书本数", value = books, range = bounds, onChange = { books = it })
        StepperRow(label = "同时下载图片数", value = images, range = imageBounds, onChange = { images = it })

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.processIntent(BookshelfUiIntent.SaveDownloadSettings(books, images))
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存下载设置")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("追更检测", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                viewModel.processIntent(BookshelfUiIntent.SetUpdateUngroupedBooks(!uiState.updateUngroupedBooks))
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自动更新未分组的书籍", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = uiState.updateUngroupedBooks,
                onCheckedChange = { viewModel.processIntent(BookshelfUiIntent.SetUpdateUngroupedBooks(it)) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { if (value > range.first) onChange(value - 1) },
            enabled = value > range.first
        ) { Text("−", fontSize = 18.sp) }
        Text(
            "$value",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        TextButton(
            onClick = { if (value < range.last) onChange(value + 1) },
            enabled = value < range.last
        ) { Text("+", fontSize = 18.sp) }
    }
}

/**
 * 导入选项弹窗
 */
@Composable
fun ImportOptionsDialog(
    groups: List<BookGroupEntity>,
    onConfirm: (mode: BookGroupImportMode, targetGroupId: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(BookGroupImportMode.MERGE) }
    var targetGroupId by remember { mutableStateOf<Long?>(null) }
    val canConfirm = mode == BookGroupImportMode.MERGE || targetGroupId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入书架", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { mode = BookGroupImportMode.MERGE },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == BookGroupImportMode.MERGE,
                        onClick = { mode = BookGroupImportMode.MERGE }
                    )
                    Column {
                        Text("合并分组", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "自动创建备份中本机没有的分组，保留原有归属",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = groups.isNotEmpty()) {
                        mode = BookGroupImportMode.MOVE_TO_EXISTING
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == BookGroupImportMode.MOVE_TO_EXISTING,
                        onClick = { if (groups.isNotEmpty()) mode = BookGroupImportMode.MOVE_TO_EXISTING },
                        enabled = groups.isNotEmpty()
                    )
                    Column {
                        Text("移动到现有分组", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (groups.isEmpty()) "本机暂无分组，不可选"
                            else "不创建新分组，导入的书全部归入所选分组",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (groups.isNotEmpty() && mode == BookGroupImportMode.MOVE_TO_EXISTING) {
                    Spacer(Modifier.height(4.dp))
                    val groupRowState = rememberLazyListState()
                    LazyRow(
                        state = groupRowState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                    ) {
                        items(groups, key = { it.id }) { group ->
                            FilterChip(
                                selected = targetGroupId == group.id,
                                onClick = { targetGroupId = group.id },
                                label = { Text(group.name, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(mode, targetGroupId) }, enabled = canConfirm) {
                Text("选择文件")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 导出书架选项弹窗
 */
@Composable
fun ShelfExportOptionsDialog(
    selectedCount: Int,
    onConfirm: (includeProgress: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var includeProgress by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出书架", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "已选择 $selectedCount 本书籍，请选择备份内容：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { includeProgress = !includeProgress },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeProgress, onCheckedChange = { includeProgress = it == true })
                    Column {
                        Text("包含阅读记录", fontSize = 14.sp)
                        Text(
                            "读到第几章、章节已读标记",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(includeProgress) }) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 分组管理弹窗
 */
@Composable
fun ManageGroupsDialog(
    groups: List<BookGroupEntity>,
    currentStartMask: Long,
    onCreate: (String, Boolean) -> Unit,
    onUpdate: (Long, String, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onSelectStartMask: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newIsAutoUpdate by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingIsAutoUpdate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理分组", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "启动时默认进入的分组",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "选 \"全部\" 表示不筛选。重启 app 后生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectStartMask(0L) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentStartMask == 0L, onClick = { onSelectStartMask(0L) })
                        Spacer(Modifier.width(4.dp))
                        Text("全部", fontSize = 14.sp)
                    }
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelectStartMask(group.mask) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentStartMask == group.mask, onClick = { onSelectStartMask(group.mask) })
                            Spacer(Modifier.width(4.dp))
                            Text(group.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                Text(
                    "已有分组（${groups.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                if (groups.isEmpty()) {
                    Text("还没有分组", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        groups.forEach { group ->
                            if (editingId == group.id) {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = editingName,
                                            onValueChange = { editingName = it },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                if (editingName.isNotBlank()) onUpdate(group.id, editingName, editingIsAutoUpdate)
                                                editingId = null
                                            },
                                            enabled = editingName.isNotBlank()
                                        ) { Text("保存") }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = editingIsAutoUpdate, onCheckedChange = { editingIsAutoUpdate = it == true })
                                        Text("允许自动更新（追更检测）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (group.isAutoUpdate) "支持自动更新" else "不自动更新",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (group.isAutoUpdate) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    IconButton(onClick = {
                                        editingId = group.id
                                        editingName = group.name
                                        editingIsAutoUpdate = group.isAutoUpdate
                                    }) {
                                        Icon(painterResource(Res.drawable.edit_24px), "编辑", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDelete(group.id) }) {
                                        Icon(
                                            painterResource(Res.drawable.delete_24px), "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                Text("新建分组", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("最多20字") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onCreate(newName, newIsAutoUpdate)
                                newName = ""
                                newIsAutoUpdate = false
                            },
                            enabled = newName.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(painterResource(Res.drawable.add_24px), null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { newIsAutoUpdate = !newIsAutoUpdate }
                    ) {
                        Checkbox(checked = newIsAutoUpdate, onCheckedChange = { newIsAutoUpdate = it == true })
                        Text("允许自动更新（刷新时包含该组）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 设置分组归属弹窗
 */
@Composable
fun AssignGroupsDialog(
    targetUrls: List<String>,
    books: List<Book>,
    groups: List<BookGroupEntity>,
    onToggleAll: (checked: Boolean) -> Unit,
    onToggle: (mask: Long, checked: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val targets = remember(targetUrls, books) { books.filter { it.url in targetUrls } }
    val checkedMasks: Set<Long> = remember(targets, groups) {
        if (targets.isEmpty()) emptySet()
        else groups.filter { g -> targets.all { it.groupFlags and g.mask != 0L } }.map { it.mask }.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (targetUrls.size == 1) "设置分组" else "为 ${targetUrls.size} 本书设置分组",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (groups.isEmpty()) {
                Text(
                    "暂无分组\n请先点击书架上方「+ 分组」创建",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                )
            } else {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (targetUrls.size > 1) "勾选对全部选中书本生效" else "勾选即保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(onClick = { onToggleAll(true) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("全选", fontSize = 12.sp)
                            }
                            TextButton(onClick = { onToggleAll(false) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("清空", fontSize = 12.sp)
                            }
                        }
                    }

                    Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        groups.forEach { group ->
                            val checked = group.mask in checkedMasks
                            val hitCount = targets.count { com.zhhz.spider.db.BookGroupFlags.contains(it.groupFlags, group.mask) }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onToggle(group.mask, !checked) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { onToggle(group.mask, it == true) })
                                Text(
                                    group.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "$hitCount / ${targets.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 首启引导对话框
 */
@Composable
fun StartGroupDialog(
    groups: List<BookGroupEntity>,
    onChoose: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMask by remember { mutableStateOf(0L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择默认进入的分组") },
        text = {
            Column {
                Text(
                    "选一个分组作为书架的默认入口。可以在书架顶部分组行随时切换，或在分组管理里调整。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    item("all") {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedMask = 0L }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedMask == 0L, onClick = { selectedMask = 0L })
                            Spacer(Modifier.width(8.dp))
                            Text("全部", fontSize = 15.sp)
                        }
                    }
                    items(groups, key = { it.id }) { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedMask = group.mask }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedMask == group.mask, onClick = { selectedMask = group.mask })
                            Spacer(Modifier.width(8.dp))
                            Text(group.name, fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(selectedMask) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("跳过") }
        }
    )
}
