package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import com.zhhz.spider.JsEditorOverlay
import com.zhhz.spider.PlatformBackHandler
import com.zhhz.spider.RuleSelectDialog
import com.zhhz.spider.isDesktopPlatform
import com.zhhz.spider.ruleEditorServerAddress
import com.zhhz.spider.debug.RuleDebugRunner
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.impl.decodeImageHeaders
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.remote.RemoteDebugRequest
import com.zhhz.spider.remote.RemoteDeleteRuleRequest
import com.zhhz.spider.remote.RemoteImageRequest
import com.zhhz.spider.remote.RemoteRuleRequest
import com.zhhz.spider.remote.RuleEditorRemoteClient
import com.zhhz.spider.rule.ParseTraceEvent
import com.zhhz.spider.rule.ParseTraceStatus
import com.zhhz.spider.rule.ParseTraceValue
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import com.zhhz.spider.rule.toDomain
import com.zhhz.spider.rule.toEntity
import com.zhhz.spider.ui.JsEditContext
import com.zhhz.spider.ui.RuleFormWithTabs
import com.zhhz.spider.ui.TopBarSection
import com.zhhz.spider.manager.imageRequest
import com.zhhz.spider.util.DebugLogBuffer
import com.zhhz.spider.viewModel.MangaImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.compose.koinInject

@Composable
fun MainScreen(
    currentRule: SourceRule,
    onRuleChange: (SourceRule) -> Unit,
    onOpen: (Boolean) -> Unit,
    onUpdate: (SourceRule) -> Unit,
    allowRuleSelection: Boolean = true
) {
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

        var ctx by remember(currentRule.id) { mutableStateOf<VariableContext>(mutableMapOf()) }
        var remoteAddress by remember { mutableStateOf("http://192.168.1.2:8765") }
        var remoteClient by remember { mutableStateOf<RuleEditorRemoteClient?>(null) }
        var remoteRules by remember { mutableStateOf<List<RuleEntity>>(emptyList()) }
        var remoteStatus by remember { mutableStateOf<String?>(null) }
        var showRemoteDialog by remember { mutableStateOf(false) }

        suspend fun refreshRemoteRules(client: RuleEditorRemoteClient) {
            remoteRules = client.rules().map {
                RuleEntity(it.id, it.name, it.jsonContent, it.isEnabled, it.updateTime)
            }
        }

        fun runLocalAndStore(tabIndex: Int, html: String, prefix: String = ""): String {
            val result = RuleDebugRunner.runLocal(tabIndex, html, currentRule, ctx)
            traceBuffers[tabIndex] = result.traces
            val output = if (prefix.isBlank()) result.output else prefix + result.output
            resultBuffers[tabIndex] = output
            return output
        }

        val runStepFlow = {
            scope.launch {
                remoteClient?.let { client ->
                    runCatching {
                        client.runFullChain(RemoteDebugRequest(input = topInputText, rule = currentRule, context = ctx))
                    }.onSuccess { response ->
                        response.updates.forEach { update ->
                            selectedTabIndex = update.tabIndex
                            resultBuffers[update.tabIndex] = update.message
                            update.html?.let { htmlBuffers[update.tabIndex] = it }
                            traceBuffers[update.tabIndex] = update.localResult?.traces ?: emptyList()
                        }
                        ctx = response.result.context.toMutableMap()
                        if (!response.result.success && response.result.errorTabIndex != null) {
                            selectedTabIndex = response.result.errorTabIndex
                            resultBuffers[response.result.errorTabIndex] = "ERROR: ${response.result.errorMessage}"
                        }
                    }.onFailure {
                        resultBuffers[selectedTabIndex] = "ERROR: Android 远程测试失败: ${it.message}"
                        DebugLogBuffer.append("Android 远程全链路测试失败: ${it.message}")
                    }
                    return@launch
                }
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
                    val client = remoteClient
                    if (client == null) {
                        val wasEnabled = dao.getRuleById(currentRule.id)?.isEnabled ?: true
                        dao.saveRule(currentRule.toEntity(isEnabled = wasEnabled))
                        DebugLogBuffer.append("√ 规则 [${currentRule.name}] 已保存到本地数据库")
                    } else {
                        val response = client.save(RemoteRuleRequest(currentRule))
                        check(response.success) { response.message.ifBlank { "Android 保存失败" } }
                        refreshRemoteRules(client)
                        remoteStatus = response.message.ifBlank { "已保存到 Android" }
                        DebugLogBuffer.append("√ 规则 [${currentRule.name}] 已保存到 Android")
                    }
                } catch (e: Exception) {
                    remoteStatus = "保存失败: ${e.message}"
                    DebugLogBuffer.append("规则保存失败: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        var showSelectDialog by remember { mutableStateOf(false) }
        var showLogDialog by remember { mutableStateOf(false) }
        val ruleDao = koinInject<RuleDao>()
        val localRules by ruleDao.getAllRulesFlow().collectAsState(initial = emptyList())
        val savedRules = if (remoteClient == null) localRules else remoteRules

        LaunchedEffect(allowRuleSelection) {
            if (allowRuleSelection) showSelectDialog = true
        }

        var activeEditContext by remember { mutableStateOf<JsEditContext?>(null) }
        val onOpenJs = { ctx: JsEditContext -> activeEditContext = ctx }

        PlatformBackHandler {
            when {
                activeEditContext != null -> activeEditContext = null
                showSelectDialog -> showSelectDialog = false
                else -> onOpen(false)
            }
        }

        // 💡 1. 用于手机端管理“配置表单”与“HTML/结果”切换的临时状态
        var mobileLayoutTab by remember { mutableStateOf(0) } // 0: 编辑规则, 1: 调试源码

        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                // --- 顶部工具栏 ---
                TopBarSection(
                    selectedTabIndex = selectedTabIndex,
                    inputText = topInputText,
                    currentRule = currentRule,
                    onOpen = onOpen,
                    onRuleChange = onRuleChange,
                    onShowSelectDialogChange = if (allowRuleSelection) {
                        { showSelectDialog = true }
                    } else null,
                    onInputChange = { topInputText = it },
                    onLocalTest = {
                        val currentTab = selectedTabIndex
                        htmlBuffers[currentTab]?.let { html ->
                            val client = remoteClient
                            if (client == null) {
                                runLocalAndStore(currentTab, html)
                            } else {
                                scope.launch {
                                    runCatching {
                                        client.runLocal(RemoteDebugRequest(currentTab, html = html, rule = currentRule, context = ctx))
                                    }.onSuccess { response ->
                                        ctx = response.context.toMutableMap()
                                        traceBuffers[currentTab] = response.result.traces
                                        resultBuffers[currentTab] = response.result.output
                                    }.onFailure {
                                        resultBuffers[currentTab] = "ERROR: Android 远程解析失败: ${it.message}"
                                        DebugLogBuffer.append("Android 远程解析失败: ${it.message}")
                                    }
                                }
                            }
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

                            val client = remoteClient
                            val debugResult = if (client == null) {
                                withContext(Dispatchers.IO) {
                                    RuleDebugRunner.runNetwork(currentTab, topInputText, currentRule, ctx, taskRunner)
                                }
                            } else {
                                runCatching {
                                    client.runNetwork(RemoteDebugRequest(currentTab, topInputText, rule = currentRule, context = ctx))
                                }.onSuccess { ctx = it.context.toMutableMap() }
                                    .getOrElse {
                                        resultBuffers[currentTab] = "ERROR: Android 远程抓取失败: ${it.message}"
                                        DebugLogBuffer.append("Android 远程抓取失败: ${it.message}")
                                        return@launch
                                    }.result
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
                                        currentRule = currentRule,
                                        ctx = ctx,
                                        htmlBuffers = htmlBuffers,
                                        resultBuffers = resultBuffers,
                                        traceBuffers = traceBuffers,
                                        remoteClient = remoteClient,
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
                                currentRule = currentRule,
                                ctx = ctx,
                                htmlBuffers = htmlBuffers,
                                resultBuffers = resultBuffers,
                                traceBuffers = traceBuffers,
                                remoteClient = remoteClient,
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

            FloatingActionButton(
                onClick = { showLogDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Text("日志")
            }

            if (isDesktopPlatform()) {
                Button(
                    onClick = { showRemoteDialog = true },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (remoteClient == null) MaterialTheme.colorScheme.secondary else Color(0xFF2E7D32)
                    )
                ) {
                    Text(if (remoteClient == null) "连接 Android" else "Android 已连接")
                }
            } else {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        "PC 规则服务: ${ruleEditorServerAddress() ?: "请连接 Wi-Fi"}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 12.sp
                    )
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
                    scope.launch(Dispatchers.IO) {
                        val client = remoteClient
                        if (client == null) {
                            ruleDao.deleteRule(entity)
                        } else {
                            runCatching {
                                client.delete(RemoteDeleteRuleRequest(entity.id))
                                refreshRemoteRules(client)
                            }.onFailure { remoteStatus = "删除失败: ${it.message}" }
                        }
                    }
                },
                onDismiss = { showSelectDialog = false }
            )
        }

        if (showLogDialog) {
            LoggerDialog(remoteClient = remoteClient, onDismiss = { showLogDialog = false })
        }

        if (showRemoteDialog) {
            AlertDialog(
                onDismissRequest = { showRemoteDialog = false },
                title = { Text("连接 Android 规则服务") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = remoteAddress,
                            onValueChange = { remoteAddress = it },
                            label = { Text("Android 地址") },
                            singleLine = true
                        )
                        Text("确保 PC 与 Android 在同一局域网，服务端口为 8765。", fontSize = 12.sp)
                        remoteStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            remoteStatus = "连接中..."
                            val client = RuleEditorRemoteClient(remoteAddress)
                            runCatching {
                                val health = client.health()
                                refreshRemoteRules(client)
                                health
                            }.onSuccess {
                                remoteClient = client
                                remoteAddress = client.baseUrl
                                remoteStatus = "已连接 ${it.name}"
                                showSelectDialog = true
                            }.onFailure {
                                remoteStatus = "连接失败: ${it.message}"
                            }
                        }
                    }) { Text("连接") }
                },
                dismissButton = {
                    Row {
                        if (remoteClient != null) {
                            TextButton(onClick = {
                                remoteClient = null
                                remoteRules = emptyList()
                                remoteStatus = "已断开，使用 PC 本地模式"
                            }) { Text("断开") }
                        }
                        TextButton(onClick = { showRemoteDialog = false }) { Text("关闭") }
                    }
                }
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
    currentRule: SourceRule,
    ctx: VariableContext,
    htmlBuffers: MutableMap<Int, String>,
    resultBuffers: MutableMap<Int, String>,
    traceBuffers: MutableMap<Int, List<ParseTraceEvent>>,
    remoteClient: RuleEditorRemoteClient?,
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
            when (debugTabIndex) {
                0 -> {
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
                }

                1 -> {
                    TracePanel(
                        currentRule = currentRule,
                        ctx = ctx,
                        html = htmlBuffers[selectedTabIndex].orEmpty(),
                        events = traceBuffers[selectedTabIndex] ?: emptyList(),
                        remoteClient = remoteClient,
                        onTraceNavigate = onTraceNavigate
                    )
                }
            }
        }
    }
}

@Composable
private fun LoggerDialog(remoteClient: RuleEditorRemoteClient?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val localLines by DebugLogBuffer.lines.collectAsState()
    var remoteLines by remember(remoteClient) { mutableStateOf<List<String>>(emptyList()) }
    var remoteError by remember(remoteClient) { mutableStateOf<String?>(null) }
    val lines = if (remoteClient == null) localLines else remoteLines

    LaunchedEffect(remoteClient) {
        val client = remoteClient ?: return@LaunchedEffect
        while (true) {
            runCatching { client.logs() }
                .onSuccess {
                    remoteLines = it.lines
                    remoteError = null
                }
                .onFailure { remoteError = it.message ?: "读取 Android 日志失败" }
            delay(1_000)
        }
    }
    val logText = remember(lines) {
        if (lines.isEmpty()) "暂无 logger 日志" else lines.joinToString("\n")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .widthIn(max = 980.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (remoteClient == null) "本机 Logger 日志" else "Android Logger 日志",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "记录 ${lines.size} 行，最多保留 1000 行",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        if (remoteClient == null) {
                            DebugLogBuffer.clear()
                        } else {
                            remoteLines = emptyList()
                            scope.launch {
                                runCatching { remoteClient.clearLogs() }
                                    .onFailure { remoteError = it.message ?: "清空 Android 日志失败" }
                            }
                        }
                    }, enabled = lines.isNotEmpty()) {
                        Text("清空")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                remoteError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }

                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF1F1F1F), RoundedCornerShape(6.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = logText,
                            color = Color(0xFFA9B7C6),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TracePanel(
    currentRule: SourceRule,
    ctx: VariableContext,
    html: String,
    events: List<ParseTraceEvent>,
    remoteClient: RuleEditorRemoteClient?,
    onTraceNavigate: (String) -> Unit
) {
    var valueDialog by remember { mutableStateOf<TraceValueDialogState?>(null) }

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
                        onClick = { onTraceNavigate(event.selectorName) },
                        onShowInput = {
                            valueDialog = TraceValueDialogState(
                                title = "${event.selectorName} · Step ${event.stepIndex} · Input",
                                selectorName = event.selectorName,
                                isOutput = false,
                                values = event.inputValues,
                                totalCount = event.inputCount
                            )
                        },
                        onShowOutput = {
                            valueDialog = TraceValueDialogState(
                                title = "${event.selectorName} · Step ${event.stepIndex} · Output",
                                selectorName = event.selectorName,
                                isOutput = true,
                                values = event.outputValues,
                                totalCount = event.outputCount
                            )
                        }
                    )
                }
            }
        }
    }

    valueDialog?.let { state ->
        TraceValueDialog(
            state = state,
            currentRule = currentRule,
            ctx = ctx,
            html = html,
            remoteClient = remoteClient,
            onDismiss = { valueDialog = null }
        )
    }
}

@Composable
private fun TraceStepRow(
    event: ParseTraceEvent,
    onClick: () -> Unit,
    onShowInput: () -> Unit,
    onShowOutput: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F1F1F), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                event.rule.ifBlank { "<empty>" },
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
            Text(event.status.name, color = traceStatusColor(event.status), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        event.message.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, color = traceStatusColor(event.status), fontSize = 10.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onShowInput, enabled = event.inputValues.isNotEmpty()) {
                Text("Input (${event.inputCount})", fontSize = 10.sp)
            }
            TextButton(onClick = onShowOutput, enabled = event.outputValues.isNotEmpty()) {
                Text("Output (${event.outputCount})", fontSize = 10.sp)
            }
        }
    }
}

private data class TraceValueDialogState(
    val title: String,
    val selectorName: String,
    val isOutput: Boolean,
    val values: List<ParseTraceValue>,
    val totalCount: Int
)

@Composable
private fun TraceValueDialog(
    state: TraceValueDialogState,
    currentRule: SourceRule,
    ctx: VariableContext,
    html: String,
    remoteClient: RuleEditorRemoteClient?,
    onDismiss: () -> Unit
) {
    var currentIndex by remember(state) { mutableIntStateOf(0) }
    val currentValue = state.values.getOrNull(currentIndex)
    val currentImageUrl = currentValue?.text?.trim().orEmpty()
        .takeIf { state.isOutput && state.selectorName == "ContentPage.contentSelector" && it.isImageUrl() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .widthIn(max = 900.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "记录 ${state.values.size} / 总计 ${state.totalCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = currentValue?.text?.ifBlank { "<empty>" } ?: "<no value>",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    currentImageUrl?.let { imageUrl ->
                        TraceImagePreview(
                            imageUrl = imageUrl,
                            currentRule = currentRule,
                            ctx = ctx,
                            html = html,
                            remoteClient = remoteClient,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }

                val omittedCount = (state.totalCount - state.values.size).coerceAtLeast(0)
                Text(
                    buildString {
                        append("字符数 ${currentValue?.text?.length ?: 0}")
                        if (currentValue?.truncated == true) append(" · 内容已截断")
                        if (omittedCount > 0) append(" · 另有 $omittedCount 项未记录")
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { currentIndex-- },
                        enabled = currentIndex > 0
                    ) { Text("上一项") }
                    Text(
                        "${if (state.values.isEmpty()) 0 else currentIndex + 1} / ${state.values.size}",
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    TextButton(
                        onClick = { currentIndex++ },
                        enabled = currentIndex < state.values.lastIndex
                    ) { Text("下一项") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun TraceImagePreview(
    imageUrl: String,
    currentRule: SourceRule,
    ctx: VariableContext,
    html: String,
    remoteClient: RuleEditorRemoteClient?,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    var reloadVersion by remember(imageUrl) { mutableIntStateOf(-1) }
    val bookUrl = ctx["bookUrl"].orEmpty().ifBlank { ctx["catalogUrl"].orEmpty() }.ifBlank { currentRule.id }
    val decryptVersion = currentRule.content.decryptImage.hashCode().toString()
    val shouldLoad = reloadVersion >= 0
    var remoteImageBytes by remember(imageUrl, remoteClient) { mutableStateOf<ByteArray?>(null) }
    var remoteImageError by remember(imageUrl, remoteClient) { mutableStateOf<String?>(null) }

    LaunchedEffect(remoteClient, reloadVersion, imageUrl, currentRule, html, ctx) {
        val client = remoteClient ?: return@LaunchedEffect
        if (!shouldLoad) return@LaunchedEffect
        remoteImageBytes = null
        remoteImageError = null
        runCatching {
            client.loadImage(
                RemoteImageRequest(
                    imageUrl = imageUrl,
                    html = html,
                    rule = currentRule,
                    context = ctx
                )
            )
        }.onSuccess {
            remoteImageBytes = it
        }.onFailure {
            remoteImageError = it.message ?: "Android 图片加载失败"
            DebugLogBuffer.append("Android 图片预览失败: ${it.message}")
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1F1F1F), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (currentRule.content.decryptImage.isBlank()) "图片预览 · 未配置解密" else "图片预览 · 已启用解密",
                color = Color(0xFFA9B7C6),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { reloadVersion++ }) {
                Text(if (shouldLoad) "重新加载" else "加载图片", fontSize = 12.sp)
            }
        }

        if (!shouldLoad) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .background(Color.Black, RoundedCornerShape(4.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("点击“加载图片”后再请求图片", color = Color(0xFFA9B7C6), fontSize = 12.sp)
            }
            return@Column
        }

        if (remoteClient != null) {
            remoteImageError?.let {
                Text("Android 图片预览失败\n$it", color = Color(0xFFE57373), fontSize = 12.sp)
                return@Column
            }
            val bytes = remoteImageBytes
            if (bytes == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                AsyncImage(
                    model = bytes,
                    contentDescription = "Android Trace 输出图片预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 520.dp)
                        .background(Color.Black, RoundedCornerShape(4.dp))
                )
            }
            return@Column
        }

        val headerResult = remember(currentRule.content.imageHeaders, html, ctx, bookUrl, reloadVersion) {
            runCatching {
                if (currentRule.content.imageHeaders.steps.isEmpty()) {
                    mutableMapOf()
                } else {
                    decodeImageHeaders(
                        rawHeaders = RuleParser.parseString(html, currentRule.content.imageHeaders, ctx),
                        sourceName = currentRule.name.ifBlank { currentRule.id },
                        chapterUrl = ctx["chapterUrl"].orEmpty().ifBlank { bookUrl }
                    )
                }
            }
        }

        headerResult.exceptionOrNull()?.let { error ->
            Text(
                "图片 Headers 解析失败，已停止加载图片\n${error.message.orEmpty()}",
                color = Color(0xFFE57373),
                fontSize = 12.sp
            )
            return@Column
        }

        val headers = headerResult.getOrDefault(mutableMapOf()).toMutableMap()
        headers["X-Internal-Debug-Reload"] = reloadVersion.toString()
        headers["X-Internal-Decrypt-Version"] = decryptVersion

        val cacheKey = "$imageUrl|rule=${currentRule.id}|decrypt=$decryptVersion|reload=$reloadVersion"
        val request = remember(currentRule.id, bookUrl, imageUrl, headers, decryptVersion, reloadVersion) {
            imageRequest(
                ruleId = currentRule.id,
                bookUrl = bookUrl,
                image = MangaImage(
                    url = imageUrl,
                    headers = headers,
                    ruleId = currentRule.id
                ),
                isImageDecrypt = currentRule.content.decryptImage.isNotBlank(),
                context = context
            ).newBuilder()
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .build()
        }

        AsyncImage(
            model = request,
            contentDescription = "Trace 输出图片预览",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 520.dp)
                .background(Color.Black, RoundedCornerShape(4.dp))
        )
    }
}

private fun String.isImageUrl(): Boolean {
    return startsWith("http://") || startsWith("https://")
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
