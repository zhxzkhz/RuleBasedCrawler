package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.ReaderGraph
import com.zhhz.spider.ui.widget.BookCover
import com.zhhz.spider.ui.widget.ChapterListItem
import com.zhhz.spider.viewModel.DetailUiEffect
import com.zhhz.spider.viewModel.DetailUiIntent
import com.zhhz.spider.viewModel.DetailViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px
import rulebasedcrawler.composeapp.generated.resources.check_24px
import rulebasedcrawler.composeapp.generated.resources.refresh_24px

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    detailUrl: String,
    ruleId: String,
    viewModel: DetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToReader: (ReaderGraph.Reader) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 💡 新增：用于控制“换源对话框”显示的本地状态
    var showSourceDialog by remember { mutableStateOf(false) }
    // 💡 选章下载弹层
    var showDownloadSheet by remember { mutableStateOf(false) }
    val canToggleBookshelf = !uiState.isLoading &&
            !uiState.isCatalogLoading &&
            uiState.currentSource != null

    LaunchedEffect(detailUrl, ruleId) {
        viewModel.processIntent(DetailUiIntent.LoadDetail(detailUrl, ruleId))
    }

    // 副作用监听
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is DetailUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is DetailUiEffect.NavigateToReader -> {
                    // 跳转到阅读器
                    onNavigateToReader(effect.route)
                }
                is DetailUiEffect.NavigateBack -> {
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("书籍详情", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.processIntent(DetailUiIntent.NavigateBack) }) {
                        Icon(painterResource(Res.drawable.arrow_back_24px), "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    // 手动刷新：默认进入详情页使用本地缓存，只有点击这里才重新请求网络
                    IconButton(
                        onClick = { viewModel.processIntent(DetailUiIntent.RefreshDetail) },
                        enabled = !uiState.isLoading && !uiState.isCatalogLoading
                    ) {
                        Icon(painterResource(Res.drawable.refresh_24px), "刷新", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { padding ->
        // 💡 整体使用一个干净的 Column 容器
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f) // 占满剩余高度
                    .fillMaxWidth(),
                contentPadding = PaddingValues(20.dp)
            ) {
                // 1. 书籍基本信息头部
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        BookCover(
                            url = uiState.cover,
                            modifier = Modifier
                                .width(110.dp)
                                .height(150.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        Column(
                            modifier = Modifier.height(150.dp),
                            verticalArrangement = Arrangement.SpaceBetween // 均匀分布
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = uiState.title.ifBlank { "正在获取书名..." },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "作者：${uiState.author.ifBlank { "未知" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "状态：${uiState.status.ifBlank { "未知" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                            }

                            // 展示最新章节
                            if (uiState.latestChapterTitle.isNotBlank()) {
                                Text(
                                    text = "最新：${uiState.latestChapterTitle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (uiState.labels.isNotEmpty()) {
                    item {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.labels.forEach { label ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }


                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        // 点击直接拉起换源对话框
                        onClick = { showSourceDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "当前书源",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = uiState.currentSource?.sourceName ?: "默认书源",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // 右侧气泡提示
                            Surface(
                                shape = RoundedCornerShape(100.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "共 ${uiState.availableSources.size} 个源 〉",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 💡 评论入口：书源配置了评论规则时才显示
                if (viewModel.supportsComments()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            onClick = { viewModel.processIntent(DetailUiIntent.ToggleComments(true)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "本书评论",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = if (uiState.comments.isEmpty()) "查看书源评论" else "共 ${uiState.comments.size} 条评论",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "〉",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 2. 💡 黄金交互：并排的三按钮操作组
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 开始阅读（主按钮）
                        Button(
                            onClick = { viewModel.processIntent(DetailUiIntent.GoToReader) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (uiState.lastReadChapterIndex >= 0) "继续阅读" else "开始阅读",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 加入/移出书架（辅助按钮）
                        OutlinedButton(
                            onClick = { viewModel.processIntent(DetailUiIntent.ToggleBookshelf) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            enabled = canToggleBookshelf
                        ) {
                            Text(
                                text = if (uiState.isInBookshelf) "移出书架" else "加入书架",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 选章下载（辅助按钮）
                        OutlinedButton(
                            onClick = { showDownloadSheet = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !uiState.isCatalogLoading && uiState.chapters.isNotEmpty()
                        ) {
                            Text("下载", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

                // 3. 简介区域
                item {
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Text(
                        text = uiState.desc.ifBlank { "暂无简介信息" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

                // 4. 目录标题（含已读进度统计）
                item {
                    val readCount = uiState.chapters.count { it.isRead }
                    Text(
                        text = if (uiState.chapters.isEmpty()) "目录" else "目录 (${uiState.chapters.size} 章 · 已读 $readCount)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

                // 5. 目录列表加载状态与渲染
                if (uiState.isCatalogLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    itemsIndexed(uiState.chapters, key = { _, it -> it.url }) { index, chapter ->
                        // 如果有卷名，且是第一个元素或者和上一个元素的卷名不同，则显示卷名分类头
                        val previousChapter = uiState.chapters.getOrNull(index - 1)
                        if (chapter.group.isNotBlank() && (previousChapter == null || previousChapter.group != chapter.group)) {
                            Text(
                                text = chapter.group,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        ChapterListItem(
                            chapter = chapter,
                            isRead = chapter.isRead,
                            onClick = { viewModel.processIntent(DetailUiIntent.ChapterClicked(chapter)) }
                        )
                    }
                }
            }
        }
    }

    // 💡 选章下载弹层
    if (showDownloadSheet) {
        ModalBottomSheet(onDismissRequest = { showDownloadSheet = false }) {
            ChapterDownloadSheet(
                chapters = uiState.chapters,
                onConfirm = { urls ->
                    showDownloadSheet = false
                    viewModel.processIntent(DetailUiIntent.DownloadSelectedChapters(urls))
                }
            )
        }
    }

    if (showSourceDialog && uiState.availableSources.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = {
                Text(
                    text = "切换书源",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                // 限制对话框最大高度，防止书源过多时撑爆屏幕
                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    LazyColumn {
                        items(uiState.availableSources, key = { "${it.ruleId}|${it.url}" }) { source ->
                            val isCurrent = source.ruleId == uiState.currentSource?.ruleId &&
                                    source.url == uiState.currentSource?.url

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        // 💡 发送换源意图给 ViewModel！
                                        viewModel.processIntent(DetailUiIntent.ChangeSource(source))
                                        showSourceDialog = false // 关闭弹窗
                                    }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = source.sourceName,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 16.sp
                                )
                                if (isCurrent) {
                                    Icon(
                                        painter = painterResource(Res.drawable.check_24px),
                                        contentDescription = "当前源",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    // 💡 评论弹层
    if (uiState.showComments) {
        val clipboard = LocalClipboardManager.current
        CommentsSheet(
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { viewModel.processIntent(DetailUiIntent.ToggleComments(false)) },
            onCopyComment = { text ->
                clipboard.setText(AnnotatedString(text))
                scope.launch { snackbarHostState.showSnackbar("评论已复制") }
            }
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(
    uiState: com.zhhz.spider.viewModel.DetailUiState,
    viewModel: DetailViewModel,
    onDismiss: () -> Unit,
    onCopyComment: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "评论",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            when {
                uiState.isLoadingComments && uiState.comments.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.comments.isEmpty() -> {
                    Text(
                        "暂无评论",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(uiState.comments, key = { it.hashCode() }) { comment ->
                            CommentCard(
                                comment = comment,
                                depth = 0,
                                onCopy = { onCopyComment("${comment.user}：${comment.content}") }
                            )
                        }

                        if (uiState.hasMoreComments) {
                            item {
                                TextButton(
                                    onClick = { viewModel.processIntent(DetailUiIntent.LoadMoreComments) },
                                    enabled = !uiState.isLoadingComments,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (uiState.isLoadingComments) "加载中..." else "加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单条评论卡片：有回复时默认折叠，点击「展开/收起 N 条回复」切换；长按内容复制全文 */
@Composable
private fun CommentCard(
    comment: com.zhhz.spider.network.BookComment,
    depth: Int,
    onCopy: () -> Unit
) {
    var expanded by remember(comment.hashCode()) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = (depth * 16).dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(comment) {
                    detectTapGestures(onLongPress = { onCopy() })
                }
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.user.ifBlank { "匿名" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (depth > 0) 12.sp else 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (comment.score.isNotBlank()) {
                    Text(
                        comment.score,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (comment.time.isNotBlank()) {
                Text(
                    comment.time,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                comment.content,
                fontSize = if (depth > 0) 13.sp else 14.sp,
                lineHeight = 20.sp
            )

            if (comment.replys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = (if (expanded) "收起" else "展开") + "  条回复 " + if (expanded) "▴" else "▾",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp)
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                comment.replys.forEach { reply ->
                    CommentCard(
                        comment = reply,
                        depth = depth + 1,
                        onCopy = onCopy
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

/**
 * 💡 选章下载弹层：勾选章节（支持全选/反选/仅未读），确认后加入后台下载队列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterDownloadSheet(
    chapters: List<com.zhhz.spider.network.Chapter>,
    onConfirm: (urls: List<String>) -> Unit
) {
    // 默认全选未读章节
    var selected by remember(chapters) {
        mutableStateOf(chapters.filter { !it.isRead }.map { it.url }.toSet())
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "选择要下载的章节",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                selected = if (selected.size == chapters.size) emptySet() else chapters.map { it.url }.toSet()
            }) {
                Text(if (selected.size == chapters.size) "取消全选" else "全选", fontSize = 12.sp)
            }
            TextButton(onClick = {
                selected = chapters.filter { it.url !in selected }.map { it.url }.toSet()
            }) {
                Text("反选", fontSize = 12.sp)
            }
            TextButton(onClick = {
                selected = chapters.filter { !it.isRead && it.url !in selected }.map { it.url }.toSet()
            }) {
                Text("未读", fontSize = 12.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(chapters, key = { _, c -> c.url }) { _, chapter ->
                val checked = chapter.url in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (checked) selected - chapter.url else selected + chapter.url
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            selected = if (it == true) selected + chapter.url else selected - chapter.url
                        }
                    )
                    Text(
                        text = "${chapter.index + 1}. ${chapter.title}",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (chapter.isRead) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (chapter.isRead) {
                        Text("已读", fontSize = 10.sp, color = Color.Gray)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }

        Button(
            onClick = { onConfirm(chapters.filter { it.url in selected }.map { it.url }) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            Text("下载选中的 ${selected.size} 章")
        }
        Spacer(Modifier.height(8.dp))
    }
}
