package com.zhhz.spider.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.model.DownloadStatus
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.network.Book
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.viewModel.unreadChapterCount
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*

/**
 * 空书架占位
 */
@Composable
fun EmptyBookshelfPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(Res.drawable.auto_stories_24px),
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.LightGray
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "书架空空如也",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "通过【规则测试】抓取书籍后，点击\"加入书架\"即可在这里看到它们。",
                fontSize = 13.sp,
                color = Color.Gray.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}

/**
 * 网格模式书籍卡片
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookItemCard(
    book: Book,
    downloadTask: DownloadTask?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleDownload: () -> Unit,
    onAssignGroups: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column {
            Box {
                BookCover(url = book.cover, modifier = Modifier.fillMaxWidth().aspectRatio(0.7f))

                val unreadCount = if (book.totalChapters > 0) {
                    (book.totalChapters - book.lastReadChapterIndex - 1).coerceAtLeast(0)
                } else 0
                if (unreadCount > 0 && book.lastReadChapterIndex >= 0) {
                    Surface(
                        color = Color(0xFFE53935),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(bottomEnd = 8.dp, topStart = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else "$unreadCount",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (book.lastReadChapterIndex == -1) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(bottomStart = 8.dp, topEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = "NEW",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                if (downloadTask != null) {
                    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f))) {
                        IconButton(onClick = onToggleDownload, modifier = Modifier.align(Alignment.Center)) {
                            val icon = when (downloadTask.status) {
                                DownloadStatus.DOWNLOADING -> painterResource(Res.drawable.pause_24px)
                                DownloadStatus.PAUSED, DownloadStatus.ERROR -> painterResource(Res.drawable.play_arrow_24px)
                                DownloadStatus.COMPLETED -> painterResource(Res.drawable.check_24px)
                                else -> painterResource(Res.drawable.cloud_download_24px)
                            }
                            Icon(icon, contentDescription = null, tint = Color.White)
                        }
                        if (downloadTask.status == DownloadStatus.DOWNLOADING || downloadTask.status == DownloadStatus.PAUSED) {
                            LinearProgressIndicator(
                                progress = { downloadTask.progress },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                if (isSelectionMode) {
                    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(30.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .clickable { onAssignGroups() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(Res.drawable.bookmark_add_24px),
                            contentDescription = "设置分组",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            Text(
                text = book.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * 列表模式书籍行
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookItemRow(
    book: Book,
    downloadTask: DownloadTask?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleDownload: () -> Unit,
    onAssignGroups: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(width = 60.dp, height = 80.dp).clip(RoundedCornerShape(4.dp))
            ) {
                BookCover(url = book.cover, modifier = Modifier.fillMaxSize())
                if (downloadTask != null) {
                    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f))) {
                        IconButton(onClick = onToggleDownload, modifier = Modifier.align(Alignment.Center).size(32.dp)) {
                            val icon = when (downloadTask.status) {
                                DownloadStatus.DOWNLOADING -> painterResource(Res.drawable.pause_24px)
                                DownloadStatus.PAUSED, DownloadStatus.ERROR -> painterResource(Res.drawable.play_arrow_24px)
                                DownloadStatus.COMPLETED -> painterResource(Res.drawable.check_24px)
                                else -> painterResource(Res.drawable.cloud_download_24px)
                            }
                            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                val unreadCount = if (book.totalChapters > 0) (book.totalChapters - book.lastReadChapterIndex - 1).coerceAtLeast(0) else 0
                if (unreadCount > 0 && book.lastReadChapterIndex >= 0) {
                    Surface(
                        color = Color(0xFFE53935),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(bottomEnd = 6.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else "$unreadCount",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (book.lastReadChapterIndex == -1) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(bottomStart = 4.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = "NEW",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                val readProgress = if (book.lastReadChapterIndex >= 0) "读至: ${book.lastReadChapterTitle}" else "尚未开始"
                Text(readProgress, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val updateTimeText = if (book.updateTime > 0) "最近阅读: " + formatReadTime(book.updateTime) else ""
                Text(updateTimeText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                if (downloadTask != null && (downloadTask.status == DownloadStatus.DOWNLOADING || downloadTask.status == DownloadStatus.PAUSED)) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { downloadTask.progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = null)
            } else {
                IconButton(onClick = onAssignGroups) {
                    Icon(painterResource(Res.drawable.bookmark_add_24px), "设置分组", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 阅读历史内容
 */
@Composable
fun HistoryContent(
    books: List<Book>,
    viewModel: com.zhhz.spider.viewModel.BookshelfViewModel,
    onBookClick: (Book) -> Unit
) {
    var purgeTarget by remember { mutableStateOf<Book?>(null) }
    if (purgeTarget != null) {
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("彻底删除？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将删除《${purgeTarget!!.title}》的全部记录、目录与缓存，且不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.processIntent(com.zhhz.spider.viewModel.BookshelfUiIntent.PurgeHistoryBook(purgeTarget!!.url))
                    purgeTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { purgeTarget = null }) { Text("取消") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("阅读历史", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("${books.size} 条", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (books.isEmpty()) {
            Text(
                "暂无阅读记录",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(books, key = { it.url }) { book ->
                    HistoryItem(
                        book = book,
                        onClick = { onBookClick(book) },
                        onRestore = { viewModel.processIntent(com.zhhz.spider.viewModel.BookshelfUiIntent.RestoreToShelf(book.url)) },
                        onPurge = { purgeTarget = book }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    book: Book,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            BookCover(url = book.cover, modifier = Modifier.fillMaxSize())
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = book.lastReadChapterTitle.ifBlank { "尚未开始" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!book.inLibrary) {
                Text(text = "已移出书架", fontSize = 10.sp, color = Color.Gray)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatReadTime(book.updateTime),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!book.inLibrary) {
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("加回书架", fontSize = 12.sp)
            }
        }
        IconButton(onClick = onPurge, modifier = Modifier.size(32.dp)) {
            Icon(
                painterResource(Res.drawable.delete_24px),
                contentDescription = "彻底删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 相对时间展示
 */
fun formatReadTime(time: Long): String {
    if (time <= 0L) return ""
    val diff = System.currentTimeMillis() - time
    val minute = 60_000L
    val hour = 3_600_000L
    val day = 86_400_000L
    return when {
        diff < hour -> "${(diff / minute).coerceAtLeast(1)} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < day * 30 -> "${diff / day} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(time))
    }
}

/**
 * 桌面端辅助：让 LazyRow 支持鼠标拖拽和滚轮横向滚动
 */
@Composable
fun Modifier.desktopDragScroll(state: LazyListState): Modifier {
    if (!com.zhhz.spider.isDesktopPlatform()) return this
    return this
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll) {
                        val scrollDelta = event.changes.firstOrNull()?.scrollDelta
                        if (scrollDelta != null && scrollDelta.y != 0f) {
                            state.dispatchRawDelta(scrollDelta.y * 24f)
                            event.changes.firstOrNull()?.consume()
                        }
                    }
                }
            }
        }
        .pointerInput(state) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                state.dispatchRawDelta(-dragAmount.x)
            }
        }
}

/**
 * 读取筛选标签
 */
fun readFilterLabel(filter: String): String = when (filter) {
    "unread" -> "尚未阅读"
    "reading" -> "正在阅读"
    else -> "全部进度"
}

/**
 * 排序标签
 */
fun shelfSortLabel(mode: String): String = when (mode) {
    "created" -> "加入时间"
    "title" -> "书名"
    else -> "最近阅读"
}
