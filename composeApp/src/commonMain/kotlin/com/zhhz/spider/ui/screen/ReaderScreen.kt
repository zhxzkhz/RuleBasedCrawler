package com.zhhz.spider.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.constant.BookType
import com.zhhz.spider.ui.widget.LoadingBox
import com.zhhz.spider.ui.widget.MangaReaderView
import com.zhhz.spider.ui.widget.NovelReaderView
import com.zhhz.spider.PlatformBackHandler
import com.zhhz.spider.viewModel.ReaderUiEffect
import com.zhhz.spider.viewModel.ReaderUiIntent
import com.zhhz.spider.viewModel.ReaderViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rulebasedcrawler.composeapp.generated.resources.Res
import rulebasedcrawler.composeapp.generated.resources.arrow_back_24px
import rulebasedcrawler.composeapp.generated.resources.auto_stories_24px
import rulebasedcrawler.composeapp.generated.resources.refresh_24px
import rulebasedcrawler.composeapp.generated.resources.settings_24px
import rulebasedcrawler.composeapp.generated.resources.skip_next_24px
import rulebasedcrawler.composeapp.generated.resources.skip_previous_24px


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    // 💡 从导航路由传进来的核心凭证
    bookUrl: String,
    chapterIndex: Int,
    ruleId: String,
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCatalog: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val themeBackground = Color(uiState.settings.backgroundColor)
    val themeText = Color(uiState.settings.textColor)

    LaunchedEffect(chapterIndex, ruleId) {
        viewModel.processIntent(ReaderUiIntent.Init(bookUrl, chapterIndex, ruleId))
    }

    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // 统一处理副作用 (Toast、返回、剪贴板等)
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ReaderUiEffect.NavigateBack -> onNavigateBack()
                is ReaderUiEffect.NavigateToCatalogPage -> onNavigateToCatalog(effect.bookUrl, effect.ruleId)
                is ReaderUiEffect.ShowToast -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(effect.message) }
                }
                is ReaderUiEffect.CopyToClipboard -> {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(effect.text))
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar("已复制当前页内容") }
                }
            }
        }
    }

    // 💡 处理全局返回键/Esc
    PlatformBackHandler(enabled = uiState.isMenuVisible || uiState.isSettingsVisible) {
        viewModel.processIntent(ReaderUiIntent.ToggleMenu)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = themeBackground
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {

            // 阅读内容：铺满全屏，菜单以悬浮 HUD 形式叠加（不挤压内容、无二次布局跳动）
            if (uiState.isLoading) {
                LoadingBox(Modifier.align(Alignment.Center))
            } else {
                when (uiState.bookType) {
                    BookType.text -> {
                        if (uiState.settings.useAndroidReader && !com.zhhz.spider.isDesktopPlatform()) {
                            com.zhhz.spider.ui.widget.novel.AndroidNovelReader(
                                uiState = uiState,
                                onProgressUpdate = { index, progress ->
                                    viewModel.processIntent(ReaderUiIntent.UpdateReadProgress(index, progress))
                                },
                                onToggleMenu = { viewModel.processIntent(ReaderUiIntent.ToggleMenu) },
                                onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                                onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) }
                            )
                        } else {
                            com.zhhz.spider.ui.widget.novel.NovelReaderEngine(
                                uiState = uiState,
                                onProgressUpdate = { index, progress ->
                                    viewModel.processIntent(ReaderUiIntent.UpdateReadProgress(index, progress))
                                },
                                onToggleMenu = { viewModel.processIntent(ReaderUiIntent.ToggleMenu) },
                                onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                                onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) }
                            )
                        }
                    }

                    BookType.image -> {
                        MangaReaderView(
                            ruleId = ruleId,
                            uiState = uiState,
                            onProgressUpdate = { index, progress ->
                                viewModel.processIntent(ReaderUiIntent.UpdateReadProgress(index, progress))
                            },
                            onToggleMenu = { viewModel.processIntent(ReaderUiIntent.ToggleMenu) },
                            onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                            onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) },
                            onRetry = { chapterUrl, index ->
                                viewModel.processIntent(ReaderUiIntent.RetryFetchChapter(chapterUrl, index))
                            }
                        )
                    }
                }
            }

            // ── 顶部菜单 HUD（滑入/滑出动画）──
            AnimatedVisibility(
                visible = uiState.isMenuVisible && !uiState.isLoading,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                ReaderTopBar(
                    title = uiState.title,
                    subtitle = chapterProgressText(uiState.currentIndex, uiState.catalogList.size),
                    background = themeBackground,
                    contentColor = themeText,
                    onBack = { viewModel.processIntent(ReaderUiIntent.NavigateBack) }
                )
            }

            // ── 底部 HUD：章节导航栏 / 排版设置面板（互斥显示）──
            AnimatedVisibility(
                visible = !uiState.isLoading && (uiState.isMenuVisible || uiState.isSettingsVisible),
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                if (uiState.isSettingsVisible) {
                    ReaderSettingsPanel(
                        settings = uiState.settings,
                        isManga = uiState.bookType == BookType.image,
                        onFontSizeChange = { delta -> viewModel.processIntent(ReaderUiIntent.ChangeFontSize(delta)) },
                        onChangeTheme = { bg, text -> viewModel.processIntent(ReaderUiIntent.ChangeTheme(bg, text)) },
                        onChangeMangaMode = { mode -> viewModel.processIntent(ReaderUiIntent.ChangeMangaMode(mode)) },
                        onLineSpacingChange = { viewModel.processIntent(ReaderUiIntent.ChangeLineSpacing(it)) },
                        onMarginChange = { viewModel.processIntent(ReaderUiIntent.ChangeMargin(it)) },
                        onFontFamilyChange = { viewModel.processIntent(ReaderUiIntent.ChangeFontFamily(it)) },
                        onBrightnessChange = { viewModel.processIntent(ReaderUiIntent.ChangeBrightness(it)) },
                        useAndroidReader = uiState.settings.useAndroidReader,
                        onToggleAndroidReader = { viewModel.processIntent(ReaderUiIntent.ToggleAndroidReader(it)) }
                    )
                } else {
                    ReaderBottomBar(
                        hasPrev = uiState.hasPrev,
                        hasNext = uiState.hasNext,
                        currentIndex = uiState.currentIndex,
                        totalChapters = uiState.catalogList.size,
                        background = themeBackground,
                        contentColor = themeText,
                        onPrev = { viewModel.processIntent(ReaderUiIntent.GoPrev) },
                        onNext = { viewModel.processIntent(ReaderUiIntent.GoNext) },
                        onOpenCatalogPage = {
                            viewModel.processIntent(ReaderUiIntent.NavigateToCatalog)
                        },
                        onOpenSettings = {
                            viewModel.processIntent(ReaderUiIntent.ToggleSettingsPanel)
                        },
                        showReloadChapter = uiState.bookType == BookType.image,
                        onReloadChapter = {
                            viewModel.processIntent(ReaderUiIntent.ReloadCurrentMangaChapter)
                        },
                        onChangeChapter = { index ->
                            viewModel.processIntent(ReaderUiIntent.ChangeChapter(index))
                        },
                        showCopy = uiState.bookType == BookType.text,
                        onCopy = { viewModel.processIntent(ReaderUiIntent.CopyCurrentPage) }
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderSettingsPanel(
    settings: com.zhhz.spider.viewModel.ReaderSettings,
    isManga: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onChangeTheme: (Long, Long) -> Unit,
    onChangeMangaMode: (String) -> Unit,
    onLineSpacingChange: (Int) -> Unit = {},
    onMarginChange: (Float) -> Unit = {},
    onFontFamilyChange: (String) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    useAndroidReader: Boolean = false,
    onToggleAndroidReader: (Boolean) -> Unit = {}
) {
    val bg = Color(settings.backgroundColor)
    val text = Color(settings.textColor)

    Surface(
        color = bg.copy(alpha = 0.97f),
        contentColor = text,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // 字号调节
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字号", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { onFontSizeChange(-2) },
                    enabled = settings.fontSize > 12,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = text.copy(alpha = 0.10f),
                        contentColor = text
                    )
                ) {
                    Text("A-", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "${settings.fontSize}",
                    modifier = Modifier.padding(horizontal = 18.dp),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = text
                )
                FilledTonalIconButton(
                    onClick = { onFontSizeChange(2) },
                    enabled = settings.fontSize < 36,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = text.copy(alpha = 0.10f),
                        contentColor = text
                    )
                ) {
                    Text("A+", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = text.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 10.dp))

            // 背景主题：圆形色板
            Text("主题", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f))
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ThemeSwatch(0xFFFAF7F0, 0xFF333333, "默认", settings, onChangeTheme)
                ThemeSwatch(0xFFC8E6C9, 0xFF1B5E20, "护眼", settings, onChangeTheme)
                ThemeSwatch(0xFF1E1E1E, 0xFFAAAAAA, "夜间", settings, onChangeTheme)
                ThemeSwatch(0xFFF3E5AB, 0xFF5D4037, "复古", settings, onChangeTheme)
            }

            // 漫画阅读模式（仅漫画书显示）
            if (isManga) {
                HorizontalDivider(color = text.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 10.dp))
                Text("漫画模式", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f))
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MangaModeChip("webtoon", "条滑", settings.mangaMode, text, onChangeMangaMode, Modifier.weight(1f))
                    MangaModeChip("paged", "翻页", settings.mangaMode, text, onChangeMangaMode, Modifier.weight(1f))
                    MangaModeChip("rtl", "日漫(右开)", settings.mangaMode, text, onChangeMangaMode, Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (settings.mangaMode) {
                        "paged" -> "左右滑动翻页，点击两侧切页"
                        "rtl" -> "从右往左翻，点击左侧下一页"
                        else -> "上下连续滑动，适合条漫"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = text.copy(alpha = 0.5f)
                )
            }

            // 小说专属设置
            if (!isManga) {
                HorizontalDivider(color = text.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 10.dp))

                // 字体选择
                Text("字体", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f))
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val fontOptions = listOf("serif" to "宋体", "sans-serif" to "黑体", "monospace" to "等宽")
                    fontOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = settings.fontFamily == value,
                            onClick = { onFontFamilyChange(value) },
                            label = { Text(label, fontSize = 13.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 行距
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("行距", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                    Slider(
                        value = settings.lineSpacing.toFloat(),
                        onValueChange = { onLineSpacingChange(it.toInt()) },
                        valueRange = 5f..30f,
                        steps = 24,
                        modifier = Modifier.weight(2f)
                    )
                    Text("${settings.lineSpacing}", modifier = Modifier.padding(start = 8.dp), fontSize = 14.sp, color = text)
                }

                // 页边距
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("边距", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                    Slider(
                        value = settings.margin,
                        onValueChange = { onMarginChange(it) },
                        valueRange = 10f..80f,
                        modifier = Modifier.weight(2f)
                    )
                    Text("${settings.margin.toInt()}", modifier = Modifier.padding(start = 8.dp), fontSize = 14.sp, color = text)
                }

                // 亮度
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("亮度", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                    Slider(
                        value = settings.brightness,
                        onValueChange = { onBrightnessChange(it) },
                        valueRange = 0.3f..1f,
                        modifier = Modifier.weight(2f)
                    )
                    Text("${(settings.brightness * 100).toInt()}%", modifier = Modifier.padding(start = 8.dp), fontSize = 14.sp, color = text)
                }

                // Android 原生阅读器开关（仅 Android 端显示）
                if (!com.zhhz.spider.isDesktopPlatform()) {
                    HorizontalDivider(color = text.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("原生阅读引擎", style = MaterialTheme.typography.titleSmall, color = text.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                        Switch(
                            checked = useAndroidReader,
                            onCheckedChange = { onToggleAndroidReader(it) }
                        )
                    }
                    Text(
                        "开启后使用 Android 原生渲染，性能更好但功能较少",
                        style = MaterialTheme.typography.labelSmall,
                        color = text.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaModeChip(
    value: String,
    label: String,
    current: String,
    tint: Color,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = value == current
    FilterChip(
        selected = selected,
        onClick = { onChange(value) },
        label = {
            Text(
                label,
                fontSize = 12.sp,
                maxLines = 1,
                color = if (selected) MaterialTheme.colorScheme.primary else tint.copy(alpha = 0.7f)
            )
        },
        modifier = modifier
    )
}

@Composable
private fun ThemeSwatch(
    bgColor: Long,
    textColor: Long,
    label: String,
    settings: com.zhhz.spider.viewModel.ReaderSettings,
    onChangeTheme: (Long, Long) -> Unit
) {
    val isSelected = bgColor == settings.backgroundColor
    val text = Color(settings.textColor)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(bgColor))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else text.copy(alpha = 0.25f),
                    shape = CircleShape
                )
                .clickable { onChangeTheme(bgColor, textColor) },
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text("✓", color = Color(textColor), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) text else text.copy(alpha = 0.55f)
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    subtitle: String,
    background: Color = Color.Black.copy(alpha = 0.8f),
    contentColor: Color = Color.White,
    onBack: () -> Unit
) {
    Surface(color = background.copy(alpha = 0.97f), contentColor = contentColor) {
        TopAppBar(
            title = {
                Column {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.65f)
                        )
                    }
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back_24px), null) } },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                navigationIconContentColor = contentColor,
                titleContentColor = contentColor
            )
        )
    }
}

// 💡 底部菜单栏：图标+文字等宽分布，章节导航 + 全书进度条，颜色跟随阅读主题
@Composable
fun ReaderBottomBar(
    hasPrev: Boolean,
    hasNext: Boolean,
    currentIndex: Int,
    totalChapters: Int,
    background: Color = Color.Black.copy(alpha = 0.85f),
    contentColor: Color = Color.White,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenCatalogPage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    showReloadChapter: Boolean = false,
    onReloadChapter: () -> Unit = {},
    onChangeChapter: (Int) -> Unit = {},
    showCopy: Boolean = false,
    onCopy: () -> Unit = {}
) {
    val progress = if (totalChapters > 0) {
        ((currentIndex + 1).coerceIn(0, totalChapters)).toFloat() / totalChapters
    } else {
        0f
    }
    Surface(
        color = background.copy(alpha = 0.97f),
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = contentColor.copy(alpha = 0.8f),
                trackColor = contentColor.copy(alpha = 0.15f)
            )

            // 💡 快速跳章滑条（多章时显示，拖动松手即跳转）
            if (totalChapters > 1) {
                var dragging by remember { mutableStateOf(false) }
                var dragIndex by remember { mutableIntStateOf(currentIndex) }
                val shownIndex = (if (dragging) dragIndex else currentIndex).coerceIn(0, totalChapters - 1)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = shownIndex / (totalChapters - 1f),
                        onValueChange = {
                            dragging = true
                            dragIndex = (it * (totalChapters - 1)).toInt().coerceIn(0, totalChapters - 1)
                        },
                        onValueChangeFinished = {
                            dragging = false
                            val target = dragIndex.coerceIn(0, totalChapters - 1)
                            if (target != currentIndex) onChangeChapter(target)
                        },
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = contentColor,
                            activeTrackColor = contentColor.copy(alpha = 0.7f),
                            inactiveTrackColor = contentColor.copy(alpha = 0.18f)
                        )
                    )
                    Text(
                        text = "${shownIndex + 1} / $totalChapters",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.65f),
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars) // 适配全面屏底部小白条
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomAction(
                    icon = { Icon(painterResource(Res.drawable.skip_previous_24px), null, modifier = Modifier.size(22.dp)) },
                    label = "上一章",
                    enabled = hasPrev,
                    tint = contentColor,
                    modifier = Modifier.weight(1f),
                    onClick = onPrev
                )
                BottomAction(
                    icon = { Icon(painterResource(Res.drawable.auto_stories_24px), null, modifier = Modifier.size(22.dp)) },
                    label = "目录",
                    enabled = true,
                    tint = contentColor,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCatalogPage
                )
                BottomAction(
                    icon = { Icon(painterResource(Res.drawable.settings_24px), null, modifier = Modifier.size(22.dp)) },
                    label = "设置",
                    enabled = true,
                    tint = contentColor,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenSettings
                )
                if (showCopy) {
                    BottomAction(
                        icon = { Text("📋", fontSize = 18.sp) },
                        label = "复制",
                        enabled = true,
                        tint = contentColor,
                        modifier = Modifier.weight(1f),
                        onClick = onCopy
                    )
                }
                if (showReloadChapter) {
                    BottomAction(
                        icon = { Icon(painterResource(Res.drawable.refresh_24px), null, modifier = Modifier.size(22.dp)) },
                        label = "重载",
                        enabled = true,
                        tint = contentColor,
                        modifier = Modifier.weight(1f),
                        onClick = onReloadChapter
                    )
                }
                BottomAction(
                    icon = { Icon(painterResource(Res.drawable.skip_next_24px), null, modifier = Modifier.size(22.dp)) },
                    label = "下一章",
                    enabled = hasNext,
                    tint = contentColor,
                    modifier = Modifier.weight(1f),
                    onClick = onNext
                )
            }
        }
    }
}

/** 底部菜单的单个动作项：图标在上、文字在下，等宽居中 */
@Composable
private fun BottomAction(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Box(modifier = Modifier.padding(2.dp)) { icon() }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint.copy(alpha = alpha)
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
