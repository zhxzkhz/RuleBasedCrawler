package com.zhhz.spider.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.model.DownloadStatus
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.viewModel.BookshelfUiIntent
import com.zhhz.spider.viewModel.BookshelfUiState
import com.zhhz.spider.viewModel.BookshelfViewModel
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.*

/**
 * 任务中心内容
 */
@Composable
fun TaskCenterContent(
    uiState: BookshelfUiState,
    viewModel: BookshelfViewModel,
    onDismiss: () -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (DownloadTask) -> Unit,
    onCancelDownload: (String) -> Unit,
    onCancelExport: () -> Unit,
    onRetryFailed: (bookUrl: String) -> Unit = { viewModel.processIntent(BookshelfUiIntent.RetryFailedChapters(it)) }
) {
    val activeCount = uiState.downloadTasks.values.count {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
    } + if (uiState.isExporting) 1 else 0

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("任务中心", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.weight(1f))
            val hasUnfinished = uiState.downloadTasks.values.any { it.status != DownloadStatus.COMPLETED }
            if (hasUnfinished) {
                TextButton(onClick = { viewModel.processIntent(BookshelfUiIntent.ClearUnfinishedDownloads) }) {
                    Text("清除未完成", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            if (activeCount > 0) {
                Text("进行中 $activeCount", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.isExporting) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在导出", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancelExport) { Text("取消", color = MaterialTheme.colorScheme.error) }
                    }
                    Text(uiState.exportBookTitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { uiState.exportProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(uiState.exportChapterProgress, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        val tasks = uiState.downloadTasks.values
            .sortedWith(compareByDescending<DownloadTask> { it.status == DownloadStatus.DOWNLOADING }
                .thenByDescending { it.status == DownloadStatus.PENDING })

        if (tasks.isEmpty() && !uiState.isExporting) {
            Text(
                "暂无任务",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(tasks, key = { it.bookUrl }) { task ->
                    TaskItem(
                        task = task,
                        onPause = { onPauseDownload(task.bookUrl) },
                        onResume = { onResumeDownload(task) },
                        onCancel = { onCancelDownload(task.bookUrl) },
                        onRetryFailed = { onRetryFailed(task.bookUrl) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskItem(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit
) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.bookTitle,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    statusLabel(task.status),
                    fontSize = 12.sp,
                    color = when (task.status) {
                        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                        DownloadStatus.ERROR -> MaterialTheme.colorScheme.error
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (task.totalChapters > 0) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${task.downloadedChapters}/${task.totalChapters} 章" + if (task.failedUrls.isNotEmpty()) "（失败 ${task.failedUrls.size}）" else "",
                    fontSize = 11.sp,
                    color = if (task.failedUrls.isNotEmpty() && task.status == DownloadStatus.COMPLETED)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> TextButton(onClick = onPause) { Text("暂停", fontSize = 12.sp) }
                    DownloadStatus.PAUSED, DownloadStatus.ERROR ->
                        TextButton(onClick = onResume) {
                            Text(if (task.status == DownloadStatus.ERROR) "重试" else "继续", fontSize = 12.sp)
                        }
                    DownloadStatus.COMPLETED -> {
                        if (task.failedUrls.isNotEmpty()) {
                            TextButton(onClick = onRetryFailed, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("重试失败", fontSize = 12.sp)
                            }
                        }
                    }
                    else -> {}
                }
                TextButton(onClick = onCancel) {
                    Text("取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.ERROR -> "失败"
    DownloadStatus.COMPLETED -> "已完成"
    else -> "等待中"
}
