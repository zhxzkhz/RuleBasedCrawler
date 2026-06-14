package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhhz.spider.JsEditorOverlay
import com.zhhz.spider.RuleSelectDialog
import com.zhhz.spider.debug.RuleDebugRunner
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.ParseTraceEvent
import com.zhhz.spider.rule.ParseTraceStatus
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import com.zhhz.spider.rule.toDomain
import com.zhhz.spider.rule.toEntity
import com.zhhz.spider.ui.JsEditContext
import com.zhhz.spider.ui.RuleFormWithTabs
import com.zhhz.spider.ui.TopBarSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.compose.koinInject

@Composable
fun MainScreen(currentRule: SourceRule, onRuleChange: (SourceRule) -> Unit, onOpen: (Boolean) -> Unit, onUpdate: (SourceRule) -> Unit) {
    val taskRunner = koinInject<FetchTaskRunner>()

    MaterialTheme {
        var selectedTabIndex by remember { mutableStateOf(1) } // 默认选登录页
        var highlightedSelectorName by remember { mutableStateOf<String?>(null) }

        // HTML 源码池
        val htmlBuffers = remember {
            mutableStateMapOf(
                1 to "<!-- 请在此粘贴登录结果 -->",
                2 to "<!-- 请在此粘贴内容页/搜索页 HTML -->",
                3 to "<!-- 请在此粘贴内容页/详细页 HTML -->",
                4 to "<!-- 请在此粘贴内容页/目录页 HTML -->",
                5 to "<!-- 请在此粘贴内容页/正文页 HTML -->",
            )
        }

        // 解析结果池
        val resultBuffers = remember {
            mutableStateMapOf(
                1 to "等待登录...",
                2 to "等待搜索测试...",
                3 to "等待详情测试...",
                4 to "等待目录测试...",
                5 to "等待正文测试...",
            )
        }
        val traceBuffers = remember {
            mutableStateMapOf<Int, List<ParseTraceEvent>>(
                1 to emptyList(),
                2 to emptyList(),
                3 to emptyList(),
                4 to emptyList(),
                5 to emptyList(),
            )
        }

        var topInputText by remember { mutableStateOf("") }

        val dao = koinInject<RuleDao>()
        val sessionRepository = koinInject<SessionRepository>()
        val contextSessionManager = koinInject<ContextSessionManager>()
        val scope = rememberCoroutineScope()

        var ctx: VariableContext = mutableMapOf()

        fun runLocalAndStore(tabIndex: Int, html: String, prefix: String = ""): String {
            val result = RuleDebugRunner.runLocal(tabIndex, html, currentRule, ctx)
            traceBuffers[tabIndex] = result.traces
            val output = if (prefix.isBlank()) result.output else prefix + result.output
            resultBuffers[tabIndex] = output
            return output
        }

        val runStepFlow = {
            scope.launch {
                ctx = contextSessionManager.getContext(currentRule.id)
                val result = RuleDebugRunner.runFullChain(
                    input = topInputText,
                    rule = currentRule,
                    initialContext = ctx,
                    taskRunner = taskRunner,
                    contextSessionManager = contextSessionManager,
                    sessionRepository = sessionRepository
                ) { update ->
                    selectedTabIndex = update.tabIndex
                    resultBuffers[update.tabIndex] = update.message
                    update.html?.let { htmlBuffers[update.tabIndex] = it }
                    traceBuffers[update.tabIndex] = update.localResult?.traces ?: emptyList()
                }
                ctx = result.context
                if (!result.success && result.errorTabIndex != null) {
                    selectedTabIndex = result.errorTabIndex
                    resultBuffers[result.errorTabIndex] = "ERROR: ${result.errorMessage}"
                }
            }
        }

        val onSave = {
            scope.launch(Dispatchers.IO) {
                try {
                    dao.saveRule(currentRule.toEntity())
                    println("√ 规则 [${currentRule.name}] 已保存到本地数据库")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        var showSelectDialog by remember { mutableStateOf(false) }
        val ruleDao = koinInject<RuleDao>()
        val savedRules by ruleDao.getAllRulesFlow().collectAsState(initial = emptyList())

        LaunchedEffect(Unit) {
            showSelectDialog = true
        }

        var activeEditContext by remember { mutableStateOf<JsEditContext?>(null) }
        val onOpenJs = { ctx: JsEditContext -> activeEditContext = ctx }

        // 💡 1. 用于手机端管理“配置表单”与“HTML/结果”切换的临时状态
        var mobileLayoutTab by remember { mutableStateOf(0) } // 0: 编辑规则, 1: 调试源码

        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // --- 顶部工具栏 ---
            TopBarSection(
                selectedTabIndex = selectedTabIndex,
                inputText = topInputText,
                currentRule = currentRule,
                onOpen = onOpen,
                onRuleChange = onRuleChange,
                onShowSelectDialogChange = { showSelectDialog = true },
                onInputChange = { topInputText = it },
                onLocalTest = {
                    htmlBuffers[selectedTabIndex]?.let {
                        runLocalAndStore(selectedTabIndex, it)
                    }
                },
                onNetworkFetch = {
                    scope.launch {
                        val currentTab = selectedTabIndex
                        resultBuffers[currentTab] = ">>> 正在抓取 [${getTabName(currentTab)}] 源码，请稍候...\n"

                        if (currentTab == 4 && currentRule.catalog.urlSelector.steps.isEmpty()) {
                            val detailHtml = htmlBuffers[2]
                            if (!detailHtml.isNullOrBlank()) {
                                htmlBuffers[2] = detailHtml
                                resultBuffers[2] = "已从详情页同步 HTML 源码"
                                return@launch
                            }
                        }

                        val debugResult = withContext(Dispatchers.IO) {
                            RuleDebugRunner.runNetwork(currentTab, topInputText, currentRule, ctx, taskRunner)
                        }

                        if (debugResult.isError) {
                            traceBuffers[currentTab] = emptyList()
                            resultBuffers[currentTab] = debugResult.localResult.output
                        } else {
                            htmlBuffers[currentTab] = debugResult.html
                            traceBuffers[currentTab] = debugResult.localResult.traces
                            resultBuffers[currentTab] = "√ 网页获取成功！\n------------------\n${debugResult.localResult.output}"
                        }
                    }
                },
                onStepRun = { runStepFlow() },
                onSave = onSave
            )

            Spacer(Modifier.height(8.dp))

            // 💡 2. 引入 BoxWithConstraints 动态检测容器宽度，实现真正的多端响应式！
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isMobile = maxWidth < 768.dp // 判定是否为窄屏/手机端
                val navigateToTraceSelector: (String) -> Unit = { selectorName ->
                    highlightedSelectorName = null
                    selectorName.toRuleTabIndex()?.let { selectedTabIndex = it }
                    if (isMobile) mobileLayoutTab = 0
                    scope.launch {
                        yield()
                        highlightedSelectorName = selectorName
                    }
                }

                if (isMobile) {
                    // ==========================================
                    // 📱 手机端：Tab 分流滑动门布局（极佳的单手操作体验）
                    // ==========================================
                    Column(Modifier.fillMaxSize()) {
                        SecondaryTabRow(
                            selectedTabIndex = mobileLayoutTab,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Tab(selected = mobileLayoutTab == 0, onClick = { mobileLayoutTab = 0 }) {
                                Text("编辑规则", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Tab(selected = mobileLayoutTab == 1, onClick = { mobileLayoutTab = 1 }) {
                                Text("调试与结果", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (mobileLayoutTab == 0) {
                                // 手机端：编辑区 (高内聚无损渲染)
                                Card(Modifier.fillMaxSize()) {
                                    RuleFormWithTabs(
                                        rule = currentRule,
                                        selectedIndex = selectedTabIndex,
                                        scope = scope,
                                        highlightedSelectorName = highlightedSelectorName,
                                        onOpenJs = onOpenJs,
                                        onTabChange = { selectedTabIndex = it },
                                        onRuleChange = { onUpdate(it) }
                                    )
                                }
                            } else {
                                // 手机端：调试输出区 (上下分栏，输入与结果一目了然)
                                Column(Modifier.fillMaxSize()) {
                                    RightAdaptivePanel(
                                        selectedTabIndex = selectedTabIndex,
                                        htmlBuffers = htmlBuffers,
                                        resultBuffers = resultBuffers,
                                        traceBuffers = traceBuffers,
                                        onTraceNavigate = navigateToTraceSelector
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ==========================================
                    // 💻 电脑桌面端：保持你原有的完美 3:2 分栏布局
                    // ==========================================
                    Row(Modifier.fillMaxSize()) {
                        Card(Modifier.weight(1.2f).fillMaxHeight()) {
                            RuleFormWithTabs(
                                rule = currentRule,
                                selectedIndex = selectedTabIndex,
                                scope = scope,
                                highlightedSelectorName = highlightedSelectorName,
                                onOpenJs = onOpenJs,
                                onTabChange = { selectedTabIndex = it },
                                onRuleChange = { onUpdate(it) }
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Column(Modifier.weight(0.8f).fillMaxHeight()) {
                            RightAdaptivePanel(
                                selectedTabIndex = selectedTabIndex,
                                htmlBuffers = htmlBuffers,
                                resultBuffers = resultBuffers,
                                traceBuffers = traceBuffers,
                                onTraceNavigate = navigateToTraceSelector
                            )
                        }
                    }
                }

                // 统一的 JS 代码漂浮编辑窗覆盖
                activeEditContext?.let { ctx ->
                    Surface(
                        modifier = Modifier.fillMaxSize().clickable(false) {},
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            JsEditorOverlay(
                                title = ctx.title,
                                initialCode = ctx.initialCode,
                                onDismiss = { activeEditContext = null },
                                onSave = { newCode ->
                                    ctx.onSave(newCode)
                                    activeEditContext = null
                                }
                            )
                        }
                    }
                }
            }
        }

        // 统一的书源选择弹窗
        if (showSelectDialog) {
            RuleSelectDialog(
                rules = savedRules,
                onSelect = { entity ->
                    onRuleChange(entity.toDomain())
                    showSelectDialog = false
                },
                onDelete = { entity ->
                    scope.launch(Dispatchers.IO) { ruleDao.deleteRule(entity) }
                },
                onDismiss = { showSelectDialog = false }
            )
        }
    }
}

/**
 * 💡 提取出的右侧自适应调试面板 (Stateless 完美复用组件)
 */
@Composable
fun ColumnScope.RightAdaptivePanel(
    selectedTabIndex: Int,
    htmlBuffers: MutableMap<Int, String>,
    resultBuffers: MutableMap<Int, String>,
    traceBuffers: MutableMap<Int, List<ParseTraceEvent>>,
    onTraceNavigate: (String) -> Unit
) {
    if (selectedTabIndex == 0) {
        InfoPlaceholder()
    } else {
        var debugTabIndex by remember { mutableStateOf(0) }

        Text(
            "当前环境 HTML (${getTabName(selectedTabIndex)})",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = htmlBuffers[selectedTabIndex] ?: "",
            onValueChange = { htmlBuffers[selectedTabIndex] = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        )

        Spacer(Modifier.height(8.dp))

        SecondaryTabRow(
            selectedTabIndex = debugTabIndex,
            modifier = Modifier.fillMaxWidth().height(36.dp)
        ) {
            Tab(selected = debugTabIndex == 0, onClick = { debugTabIndex = 0 }) {
                Text("结果", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = debugTabIndex == 1, onClick = { debugTabIndex = 1 }) {
                Text("Trace", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF2B2B2B), RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            if (debugTabIndex == 0) {
                BasicTextField(
                    value = resultBuffers[selectedTabIndex] ?: "",
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFA9B7C6)
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2B2B2B), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
            } else {
                TracePanel(
                    events = traceBuffers[selectedTabIndex] ?: emptyList(),
                    onTraceNavigate = onTraceNavigate
                )
            }
        }
    }
}

@Composable
private fun TracePanel(
    events: List<ParseTraceEvent>,
    onTraceNavigate: (String) -> Unit
) {
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无 Trace，先执行一次本地解析或网络抓取", color = Color(0xFFA9B7C6), fontSize = 12.sp)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        events.groupBy { it.selectorName }.forEach { (selectorName, selectorEvents) ->
            val groupStatus = when {
                selectorEvents.any { it.status == ParseTraceStatus.ERROR } -> ParseTraceStatus.ERROR
                selectorEvents.any { it.status == ParseTraceStatus.EMPTY } -> ParseTraceStatus.EMPTY
                selectorEvents.any { it.status == ParseTraceStatus.SKIPPED } -> ParseTraceStatus.SKIPPED
                else -> ParseTraceStatus.OK
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(traceStatusColor(groupStatus).copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .clickable { onTraceNavigate(selectorName) }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectorName,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        groupStatus.name,
                        color = traceStatusColor(groupStatus),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                selectorEvents.forEach { event ->
                    TraceStepRow(
                        event = event,
                        onClick = { onTraceNavigate(event.selectorName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceStepRow(
    event: ParseTraceEvent,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F1F1F), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Step ${event.stepIndex}/${event.stepCount}",
            color = Color(0xFFA9B7C6),
            fontSize = 11.sp,
            modifier = Modifier.width(72.dp)
        )
        Text(
            event.type.name,
            color = traceStatusColor(event.status),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(64.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(event.rule.ifBlank { "<empty>" }, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(
                "input=${event.inputCount} output=${event.outputCount}" +
                        event.message.takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty(),
                color = Color(0xFFA9B7C6),
                fontSize = 10.sp
            )
        }
        Text(event.status.name, color = traceStatusColor(event.status), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun String.toRuleTabIndex(): Int? {
    return when {
        startsWith("LoginPage.") -> 1
        startsWith("SearchPage.") -> 2
        startsWith("DetailPage.") -> 3
        startsWith("CatalogPage.") -> 4
        startsWith("ContentPage.") -> 5
        else -> null
    }
}

private fun traceStatusColor(status: ParseTraceStatus): Color {
    return when (status) {
        ParseTraceStatus.OK -> Color(0xFF81C784)
        ParseTraceStatus.EMPTY -> Color(0xFFFFD54F)
        ParseTraceStatus.ERROR -> Color(0xFFE57373)
        ParseTraceStatus.SKIPPED -> Color(0xFF90A4AE)
    }
}

/**
 * 获取当前 Tab 的友好名称
 */
fun getTabName(index: Int) = when (index) {
    1 -> "登录页"
    2 -> "搜索页"
    3 -> "详情页"
    4 -> "目录页"
    5 -> "正文页"
    else -> "未知"
}

/**
 * 当切换到“基础信息”时，右侧显示的占位引导
 */
@Composable
fun InfoPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            //Icon(MaterialSymbols.Rounded.Info, null, Modifier.size(64.dp), Color.LightGray)
            Text("基础信息页不需要 HTML 源码", color = Color.Gray)
            Text("请点击其他标签进行解析调试", color = Color.Gray, fontSize = 12.sp)
        }
    }
}
