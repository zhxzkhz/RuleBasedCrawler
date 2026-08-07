package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhhz.spider.ui.widget.LoadingBox
import com.zhhz.spider.viewModel.ReaderViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px
import rulebasedcrawler.composeapp.generated.resources.close_24px
import rulebasedcrawler.composeapp.generated.resources.filter_list_24px
import rulebasedcrawler.composeapp.generated.resources.search_24px

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit,
    onChapterSelected: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isReversed by rememberSaveable { mutableStateOf(false) }
    var keyword by rememberSaveable { mutableStateOf("") }
    val normalizedKeyword = keyword.trim()

    suspend fun scrollToCurrentChapter(animated: Boolean = false) {
        if (uiState.currentIndex < 0 || uiState.catalogList.isEmpty() || normalizedKeyword.isNotBlank()) return
        val targetIndex = if (isReversed) {
            uiState.catalogList.size - 1 - uiState.currentIndex
        } else {
            uiState.currentIndex
        }
        val scrollIndex = maxOf(0, targetIndex - 5)
        if (animated) {
            listState.animateScrollToItem(scrollIndex)
        } else {
            listState.scrollToItem(scrollIndex)
        }
    }

    LaunchedEffect(uiState.currentIndex, isReversed, normalizedKeyword) {
        scrollToCurrentChapter()
    }

    val indexedCatalog = remember(uiState.catalogList, isReversed, normalizedKeyword) {
        val base = if (isReversed) {
            uiState.catalogList.asReversed().mapIndexed { displayIndex, chapter ->
                uiState.catalogList.size - 1 - displayIndex to chapter
            }
        } else {
            uiState.catalogList.mapIndexed { index, chapter -> index to chapter }
        }

        if (keyword.isBlank()) {
            base
        } else {
            base.filter { (realIndex, chapter) ->
                chapter.title.contains(normalizedKeyword, ignoreCase = true) ||
                    (realIndex + 1).toString().contains(normalizedKeyword)
            }
        }
    }

    val subtitle = chapterProgressText(uiState.currentIndex, uiState.catalogList.size)
    val title = uiState.bookTitle.ifBlank { uiState.title.ifBlank { "目录" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(Res.drawable.arrow_back_24px), contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        enabled = normalizedKeyword.isBlank() && uiState.currentIndex >= 0,
                        onClick = { scope.launch { scrollToCurrentChapter(animated = true) } }
                    ) {
                        Text("当前")
                    }
                    IconButton(onClick = { isReversed = !isReversed }) {
                        Icon(
                            painterResource(Res.drawable.filter_list_24px),
                            contentDescription = if (isReversed) "切换为正序" else "切换为倒序"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("搜索章节") },
                leadingIcon = {
                    Icon(painterResource(Res.drawable.search_24px), contentDescription = "搜索")
                },
                trailingIcon = {
                    if (keyword.isNotBlank()) {
                        IconButton(onClick = { keyword = "" }) {
                            Icon(painterResource(Res.drawable.close_24px), contentDescription = "清除搜索")
                        }
                    }
                }
            )

            CatalogStatusRow(
                totalCount = uiState.catalogList.size,
                visibleCount = indexedCatalog.size,
                isFiltering = normalizedKeyword.isNotBlank(),
                isReversed = isReversed
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.isLoading && uiState.catalogList.isEmpty()) {
                    LoadingBox(Modifier.align(Alignment.Center))
                } else if (uiState.catalogList.isEmpty()) {
                    Text(
                        "暂无目录信息",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Gray
                    )
                } else if (indexedCatalog.isEmpty()) {
                    Text(
                        "未找到匹配章节：$normalizedKeyword",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Gray
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(indexedCatalog, key = { _, item -> item.first }) { _, item ->
                            val (realIndex, chapter) = item
                            val isSelected = realIndex == uiState.currentIndex

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isSelected) { onChapterSelected(realIndex) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chapter.title,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isSelected) {
                                        Text(
                                            "正在阅读",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogStatusRow(
    totalCount: Int,
    visibleCount: Int,
    isFiltering: Boolean,
    isReversed: Boolean
) {
    val countText = if (isFiltering) {
        "匹配 $visibleCount / $totalCount 章"
    } else {
        "共 $totalCount 章"
    }
    val orderText = if (isReversed) "倒序" else "正序"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = countText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = orderText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun chapterProgressText(currentIndex: Int, totalChapters: Int): String {
    return if (totalChapters > 0 && currentIndex >= 0) {
        "第 ${currentIndex + 1} / $totalChapters 章"
    } else {
        ""
    }
}
