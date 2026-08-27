package com.zhhz.spider.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.DetailRoute
import com.zhhz.spider.PlatformBackHandler
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.ExploreTab
import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.network.SearchBookSource
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.ui.widget.SearchResultItem
import com.zhhz.spider.viewModel.ExploreSourceState
import com.zhhz.spider.viewModel.ExploreUiEffect
import com.zhhz.spider.viewModel.ExploreUiIntent
import com.zhhz.spider.viewModel.ExploreViewModel
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px
import rulebasedcrawler.composeapp.generated.resources.chevron_right_24px

/** 结果页导航参数 */
private data class SelectedCategory(
    val ruleId: String,
    val ruleName: String,
    val bookType: Int,
    val tabTitle: String,
    val tabUrl: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    viewModel: ExploreViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (DetailRoute) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 手风琴展开的书源；结果页选中的分类（null = 分类选择视图）
    var expandedRuleId by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<SelectedCategory?>(null) }

    // 结果页时拦截系统返回：先回到分类选择，再退出榜单
    PlatformBackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ExploreUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    val current = uiState.currentState

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedCategory?.tabTitle?.ifBlank { "分类浏览" } ?: "榜单",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedCategory != null) selectedCategory = null else onNavigateBack()
                    }) {
                        Icon(painterResource(Res.drawable.arrow_back_24px), "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                uiState.isLoadingRules -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                selectedCategory != null -> {
                    val cat = selectedCategory!!
                    val selectedRule = uiState.rules.find { it.id == cat.ruleId }
                    val sorts = selectedRule?.explore?.sorts.orEmpty()
                    CategoryResultView(
                        category = cat,
                        state = current,
                        sorts = sorts,
                        onLoadMore = { viewModel.processIntent(ExploreUiIntent.LoadMore) },
                        onSelectSort = { sortParam ->
                            viewModel.processIntent(ExploreUiIntent.SelectSort(sortParam))
                        },
                        onBookClick = { book ->
                            onNavigateToDetail(DetailRoute(
                                title = book.title,
                                author = book.author,
                                cover = book.cover,
                                detailUrl = book.url,
                                ruleId = cat.ruleId
                            ))
                        }
                    )
                }
                else -> {
                    SourceAccordionList(
                        rules = uiState.rules,
                        sourceStates = uiState.sourceStates,
                        expandedRuleId = expandedRuleId,
                        onToggleExpand = { rule ->
                            val target = if (expandedRuleId == rule.id) null else rule.id
                            expandedRuleId = target
                            // 展开时确保该源的分类已初始化加载（每个源独立）
                            if (target != null && uiState.sourceStates[target] == null) {
                                viewModel.processIntent(ExploreUiIntent.SelectRule(target))
                            }
                        },
                        onSelectCategory = { rule, tab ->
                            // 先确保选中该源，再触发该分类的加载，然后跳转结果页
                            viewModel.processIntent(ExploreUiIntent.SelectRule(rule.id))
                            viewModel.processIntent(ExploreUiIntent.SelectTab(tab))
                            selectedCategory = SelectedCategory(
                                ruleId = rule.id,
                                ruleName = rule.name.ifBlank { "未命名书源" },
                                bookType = rule.type,
                                tabTitle = tab.title,
                                tabUrl = tab.url
                            )
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 视图一：书源手风琴折叠列表 + 分组分类网格
// ==========================================

@Composable
private fun SourceAccordionList(
    rules: List<SourceRule>,
    sourceStates: Map<String, ExploreSourceState>,
    expandedRuleId: String?,
    onToggleExpand: (SourceRule) -> Unit,
    onSelectCategory: (SourceRule, ExploreTab) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(rules, key = { it.id }) { rule ->
            SourceAccordionCard(
                rule = rule,
                state = sourceStates[rule.id],
                expanded = expandedRuleId == rule.id,
                onToggleExpand = { onToggleExpand(rule) },
                onSelectCategory = { onSelectCategory(rule, it) }
            )
        }
    }
}

@Composable
private fun SourceAccordionCard(
    rule: SourceRule,
    state: ExploreSourceState?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectCategory: (ExploreTab) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // 卡片头：书源名 + 箭头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rule.name.ifBlank { "未命名书源" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(Res.drawable.chevron_right_24px),
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                // 展开区用背景色形成层次：外层灰调卡片 + 内层干净面板
                Column(
                    modifier = Modifier
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp, topStart = 10.dp, topEnd = 10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {

                    when {
                        state == null || state.isLoadingTabs -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                        state.tabs.isEmpty() -> {
                            Text(
                                text = state.errorMessage ?: "未解析到任何分类",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                            )
                        }
                        else -> {
                            // 按分组顺序展示；无分组的标签不显示分组标题
                            state.tabs.groupBy { it.group }.forEach { (group, tabs) ->
                                if (group.isNotBlank()) {
                                    GroupTitlePill(group)
                                }
                                tabs.chunked(3).forEachIndexed { rowIndex, rowTabs ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    ) {
                                        rowTabs.forEach { tab ->
                                            CategoryPillChip(
                                                tab = tab,
                                                modifier = Modifier.weight(1f),
                                                onClick = { onSelectCategory(tab) }
                                            )
                                        }
                                        repeat(3 - rowTabs.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 分组标题：铺满一行的胶囊形标签条，文字居中 */
@Composable
private fun GroupTitlePill(group: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp)
    ) {
        Text(
            text = group,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}

/** 分类胶囊按钮 */
@Composable
private fun CategoryPillChip(
    tab: ExploreTab,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = tab.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 11.dp)
        )
    }
}

// ==========================================
// 视图二：搜索样式的分类结果页
// ==========================================

@Composable
private fun CategoryResultView(
    category: SelectedCategory,
    state: ExploreSourceState?,
    sorts: List<com.zhhz.spider.rule.ExploreSortOption>,
    onLoadMore: () -> Unit,
    onSelectSort: (String) -> Unit,
    onBookClick: (Book) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        // 当前分类大标题 + 数量副标题
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = category.tabTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val state = state
            if (state != null && !state.isLoadingBooks && state.books.isNotEmpty()) {
                Text(
                    text = "共 ${state.books.size} 本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 排序 Chip 行（规则配置了 sorts 时显示）
        if (sorts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val selectedParam = state?.selectedSortParam.orEmpty()
                sorts.forEach { sort ->
                    FilterChip(
                        selected = sort.param == selectedParam ||
                                (sort.param.isBlank() && selectedParam.isBlank()),
                        onClick = { onSelectSort(sort.param) },
                        label = { Text(sort.title.ifBlank { "默认" }, maxLines = 1) }
                    )
                }
            }
        }

        when {
            state == null || (state.isLoadingBooks && state.books.isEmpty()) -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.books.isEmpty() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.errorMessage ?: "该分类暂无内容",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }
            else -> {
                val listState = rememberLazyListState()

                // 触底自动翻页（ViewModel 内部有防重入与去重守卫）
                LaunchedEffect(listState, state.books.size) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
                    }.collect { (lastVisibleIndex, totalItemsCount) ->
                        if (totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 4) {
                            onLoadMore()
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.books, key = { it.url }) { book ->
                        SearchResultItem(
                            book = SearchBook(
                                title = book.title,
                                author = book.author,
                                cover = book.cover,
                                type = category.bookType,
                                sources = listOf(
                                    SearchBookSource(ruleId = category.ruleId, sourceName = category.ruleName, url = book.url)
                                )
                            ),
                            onClick = { onBookClick(book) },
                            onAddBookshelfClick = { }
                        )
                    }

                    // 底部加载更多指示器
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                state.isLoadingBooks -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                !state.hasMore -> Text(
                                    "没有更多了",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
