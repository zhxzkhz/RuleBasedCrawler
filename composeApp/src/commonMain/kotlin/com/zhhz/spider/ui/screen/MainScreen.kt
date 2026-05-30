package com.zhhz.spider.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import com.zhhz.spider.JsEditorOverlay
import com.zhhz.spider.RuleSelectDialog
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import com.zhhz.spider.rule.toDomain
import com.zhhz.spider.rule.toEntity
import com.zhhz.spider.ui.JsEditContext
import com.zhhz.spider.ui.RuleFormWithTabs
import com.zhhz.spider.ui.TopBarSection
import com.zhhz.spider.util.MangaDescrambleTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.compose.koinInject


@Composable
fun MainScreen(currentRule: SourceRule,onRuleChange: (SourceRule) -> Unit, onOpen: (Boolean) -> Unit, onUpdate: (SourceRule) -> Unit) {
    val taskRunner = koinInject<FetchTaskRunner>()

    MaterialTheme {
        // 2. 当前选中的 Tab 索引 (提升到 App 级别，因为右侧面板需要根据它切换显示)
        var selectedTabIndex by remember { mutableStateOf(1) } // 默认选登录页

        // 3. HTML 源码池：为每个 Tab 分配独立的 HTML 缓存
        // Key 1: 搜索, Key 2: 详情, Key 3: 正文
        val htmlBuffers = remember {
            mutableStateMapOf(
                1 to "<!-- 请在此粘贴登录结果 -->",
                2 to "<!-- 请在此粘贴内容页/搜索页 HTML -->",
                3 to "<!-- 请在此粘贴内容页/详细页 HTML -->",
                4 to "<!-- 请在此粘贴内容页/目录页 HTML -->",
                5 to "<!-- 请在此粘贴内容页/正文页 HTML -->"
            )
        }

        // 4. 解析结果池：为每个 Tab 分配独立的日志缓存
        val resultBuffers = remember {
            mutableStateMapOf(
                1 to "等待登录...",
                2 to "等待搜索测试...",
                3 to "等待详情测试...",
                4 to "等待目录测试...",
                5 to "等待正文测试...",
            )
        }

        // 顶部输入框的状态
        var topInputText by remember { mutableStateOf("") }

        val dao = koinInject<RuleDao>()
        val sessionRepository = koinInject<SessionRepository>()
        val contextSessionManager = koinInject< ContextSessionManager>()
        val scope = rememberCoroutineScope()

        var ctx: VariableContext = mutableMapOf()

        val context = LocalPlatformContext.current

        val runStepFlow = {
            scope.launch {
                ctx = contextSessionManager.getContext(currentRule.id)
                val log = StringBuilder()

                try {
                    // --- STEP 1: 搜索 ---
                    selectedTabIndex = 2
                    resultBuffers[selectedTabIndex] = ">>> 正在搜索: $topInputText ..."
                    val searchHtml = withContext(Dispatchers.IO) {
                        taskRunner.fetch(currentRule, currentRule.search, topInputText, ctx)
                    }
                    htmlBuffers[selectedTabIndex] = searchHtml
                    val searchList = currentRule.search.getList(searchHtml, ctx)
                    if (searchList.isEmpty()) throw Exception("搜索结果为空，流程中断")

                    val firstBookUrl = currentRule.search.getDetailUrl(searchList.first(), ctx)
                    resultBuffers[selectedTabIndex] = "√ 搜索完成，取第一项: $firstBookUrl\n" + runLocalTest(
                        selectedTabIndex,
                        searchHtml,
                        currentRule,
                        ctx,
                        context
                    )


                    sessionRepository.saveData(Book(
                        url = firstBookUrl,
                        title = "",
                        author = "",
                        cover = "",
                        ruleId = currentRule.id,
                    ))
                    ctx = contextSessionManager.getActiveContext(sessionRepository,currentRule.id)

                    // --- STEP 2: 详情 ---
                    delay(500) // 模拟人类停顿，也方便观察 UI 变化
                    selectedTabIndex = 3
                    resultBuffers[selectedTabIndex] = ">>> 正在抓取详情: $firstBookUrl ..."
                    ctx["bookUrl"] = firstBookUrl // 存入上下文
                    val detailHtml = withContext(Dispatchers.IO) {
                        taskRunner.fetch(currentRule, currentRule.detail, firstBookUrl, ctx)
                    }
                    htmlBuffers[selectedTabIndex] = detailHtml

                    resultBuffers[selectedTabIndex] = resultBuffers[selectedTabIndex] + "\n" + runLocalTest(
                        selectedTabIndex,
                        detailHtml,
                        currentRule,
                        ctx,
                        context
                    )
                    val bookName = currentRule.detail.getBookName(detailHtml, ctx)

                    ctx["bookName"] = bookName // 存入上下文
                    ctx["bookCover"] = currentRule.detail.getBookCover(detailHtml, ctx) // 存入上下文


                    // --- STEP 3: 目录 (处理同页逻辑) ---
                    // 假设你已经按照上一条建议增加了 catalog 字段
                    // 这里演示逻辑：如果 catalogUrlSelector 为空，则认为目录在详情页
                    val catalogUrl = currentRule.detail.getCatalogUrl(detailHtml, ctx) // 实际应从 detail 提取

                    delay(500) // 模拟人类停顿，也方便观察 UI 变化
                    selectedTabIndex = 4
                    ctx["catalogUrl"] = currentRule.detail.getCatalogUrl(detailHtml, ctx) // 存入上下文
                    val tocHtml = if (catalogUrl.isBlank()) {
                        resultBuffers[selectedTabIndex] = "√ 详情与目录同页，跳过目录抓取"
                        // 自动把详情 HTML 填入目录 Buffer (假设目录 Tab 索引是 3)
                        detailHtml
                    } else {
                        resultBuffers[selectedTabIndex] = ">>> 正在抓取目录: $catalogUrl ..."
                        withContext(Dispatchers.IO) {
                            taskRunner.fetch(currentRule, currentRule.catalog, catalogUrl, ctx)
                        }
                    }
                    htmlBuffers[selectedTabIndex] = tocHtml

                    val toc = currentRule.catalog.getChapters(tocHtml, ctx)
                    var contentUrl: String
                    if (toc.isNotEmpty()) {
                        contentUrl = currentRule.catalog.getChapterUrl(toc.first(), ctx)
                        resultBuffers[selectedTabIndex] = "√ 目录获取完成，取第一项: $contentUrl"
                        resultBuffers[selectedTabIndex] = resultBuffers[selectedTabIndex] + "\n" + runLocalTest(
                            selectedTabIndex,
                            tocHtml,
                            currentRule,
                            ctx,
                            context
                        )
                    } else {
                        resultBuffers[selectedTabIndex] =
                            "√ 目录获取失败\n" + runLocalTest(selectedTabIndex, tocHtml, currentRule, ctx, context)
                        return@launch
                    }

                    // --- STEP 4: 正文 (取第一章) ---
                    delay(500) // 模拟人类停顿，也方便观察 UI 变化
                    selectedTabIndex = 5

                    resultBuffers[selectedTabIndex] = ">>> 正文获取中: $contentUrl ..."
                    val contentHtml = withContext(Dispatchers.IO) {
                        taskRunner.fetch(currentRule, currentRule.content, contentUrl, ctx)
                    }
                    htmlBuffers[selectedTabIndex] = contentHtml
                    resultBuffers[selectedTabIndex] = runLocalTest(selectedTabIndex, contentHtml, currentRule, ctx,context)


                    log.append("全链路测试成功！")
                } catch (e: Exception) {
                    e.printStackTrace()
                    resultBuffers[selectedTabIndex] = "ERROR: ${e.localizedMessage}"
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
        // 使用 Flow 实时观察数据库变化
        val savedRules by ruleDao.getAllRulesFlow().collectAsState(initial = emptyList())
        // 2. 启动逻辑：如果数据库有规则，启动时默认弹出选择框（或者你可以设置为自动加载第一个）
        LaunchedEffect(Unit) {
            // 如果你希望启动时自动弹出
            showSelectDialog = true
        }

        // 状态位：如果为 null 说明没弹窗，如果不为 null 说明正在编辑某个脚本
        var activeEditContext by remember { mutableStateOf<JsEditContext?>(null) }

        val onOpenJs = { ctx: JsEditContext -> activeEditContext = ctx }

        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // --- 顶部工具栏 ---
            TopBarSection(
                selectedTabIndex = selectedTabIndex,
                inputText = topInputText,
                currentRule = currentRule,
                onOpen = onOpen,
                onRuleChange = onRuleChange,
                onShowSelectDialogChange = {
                    showSelectDialog = true
                },
                onInputChange = { topInputText = it },
                onLocalTest = {
                    // 执行本地解析测试 (逻辑同前)
                    htmlBuffers[selectedTabIndex]?.let {
                        resultBuffers[selectedTabIndex] = runLocalTest(selectedTabIndex, it, currentRule, ctx, context)
                    }
                },
                // 动作 A：执行网络抓取
                onNetworkFetch = {
                    scope.launch {
                        // 1. 设置 UI 状态为“加载中”
                        val currentTab = selectedTabIndex
                        resultBuffers[currentTab] = ">>> 正在抓取 [${getTabName(currentTab)}] 源码，请稍候...\n"

                        // 如果当前是“目录页”且没有配置 URL 规则
                        if (currentTab == 4 && currentRule.catalog.urlSelector.steps.isEmpty()) {
                            // 自动从“详情页”的 Buffer 里取数据，而不是报错或重新抓取
                            val detailHtml = htmlBuffers[2] // 假设 1 是详情页
                            if (!detailHtml.isNullOrBlank()) {
                                htmlBuffers[2] = detailHtml
                                resultBuffers[2] = "已从详情页同步 HTML 源码"
                                return@launch
                            }
                        }

                        // 2. 切换到 IO 线程执行真正的抓取调度
                        val rawHtml = withContext(Dispatchers.IO) {
                            when (currentTab) {
                                1 -> taskRunner.fetchWithSession(currentRule, currentRule.login, topInputText, ctx)
                                2 -> taskRunner.fetch(currentRule, currentRule.search, topInputText, ctx)
                                3 -> taskRunner.fetch(currentRule, currentRule.detail, topInputText, ctx)
                                4 -> taskRunner.fetch(currentRule, currentRule.catalog, topInputText, ctx)
                                5 -> taskRunner.fetch(currentRule, currentRule.content, topInputText, ctx)
                                else -> ""
                            }

                        }

                        // 3. 处理结果
                        if (rawHtml.startsWith("ERROR")) {
                            resultBuffers[currentTab] = "抓取失败: $rawHtml"
                        } else {
                            // 成功：更新 HTML 源码缓冲区
                            htmlBuffers[currentTab] = rawHtml

                            // 4. 【全自动】抓取成功后直接触发一次本地解析验证
                            val parseResult = runLocalTest(currentTab, rawHtml, currentRule, ctx, context)
                            resultBuffers[currentTab] = "√ 网页获取成功！\n------------------\n$parseResult"
                        }
                    }
                },
                onStepRun = {
                    runStepFlow()
                },
                onSave = onSave
            )

            Box(Modifier.fillMaxSize()) {


                Row(Modifier.fillMaxSize()) {
                    // --- 左侧：规则编辑区 (需要感知 Tab 切换) ---
                    Card(Modifier.weight(1.2f).fillMaxHeight()) {
                        RuleFormWithTabs(
                            rule = currentRule,
                            selectedIndex = selectedTabIndex,
                            scope = scope,
                            onOpenJs = onOpenJs,
                            onTabChange = { selectedTabIndex = it }, // 更新 Tab 索引
                            onRuleChange = { onUpdate(it) }
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // --- 右侧：环境自适应面板 ---
                    // 这里会随着 selectedTabIndex 的改变而自动重绘，切换数据源
                    Column(Modifier.weight(0.8f).fillMaxHeight()) {

                        // 如果是“基础信息”Tab，右侧可以显示说明文档或隐藏
                        if (selectedTabIndex == 0) {
                            InfoPlaceholder()
                        } else {
                            // 1. 动态绑定的 HTML 输入框
                            Text(
                                "当前环境 HTML (${getTabName(selectedTabIndex)})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            OutlinedTextField(
                                value = htmlBuffers[selectedTabIndex] ?: "",
                                onValueChange = { htmlBuffers[selectedTabIndex] = it }, // 实时存入对应的池
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            )

                            Spacer(Modifier.height(8.dp))

                            // 2. 动态绑定的结果输出框
                            Text("解析结果预览", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .background(Color(0xFF2B2B2B), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                BasicTextField(
                                    value = resultBuffers[selectedTabIndex] ?: "",
                                    onValueChange = {},           // 不允许修改
                                    readOnly = true,              // 设为只读
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = Color(0xFFA9B7C6)
                                    ),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF2B2B2B), RoundedCornerShape(4.dp))
                                        .padding(8.dp),
                                    // 关键点：它不需要任何 selectionContainer 包装，
                                    // 它内部已经实现了“长按复制、拖动选取”的健壮逻辑
                                )
                            }
                        }
                    }

                }

                activeEditContext?.let { ctx ->
                    println("弹开")
                    // 黑色半透明背景遮罩
                    Surface(
                        modifier = Modifier.fillMaxSize().clickable(false) { /* 阻止点击穿透到下层 */ },
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        // 真正的编辑器“容器”
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


        // 4. 弹窗控制
        if (showSelectDialog) {
            RuleSelectDialog(
                rules = savedRules,
                onSelect = { entity ->
                    onRuleChange(entity.toDomain()) // 将数据库 JSON 转回对象
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

fun runLocalTest(tabIndex: Int, input: String, rule: SourceRule, ctx: VariableContext, context: PlatformContext?): String {
    val log = StringBuilder()
    try {

        val html = input
        if (html.startsWith("ERROR")) return html

        // 2. 根据不同页面调用对应的辅助方法（强类型调用）
        when (tabIndex) {
            1 -> {
                val token = rule.login.getToken(html)
                if (token.isNotBlank()) {
                    log.appendLine("√ token获取成功")
                    log.appendLine(token)
                }

            }

            2 -> {
                val list = rule.search.getList(html, ctx)
                log.appendLine("√ 找到 ${list.size} 本书")

                list.take(3).forEach {
                    log.appendLine(
                        " • 书名：${rule.search.getName(it, ctx)}\n\t\t\t作者：${
                            rule.search.getAuthor(
                                it,
                                ctx
                            )
                        }\n\t\t\t封面：${
                            rule.search.getCover(
                                it, ctx
                            )
                        }\n\t\t\t详细地址：${rule.search.getDetailUrl(it, ctx)}"
                    )
                }
            }

            3 -> {
                val detail = rule.detail
                val name = detail.getBookName(html, ctx)
                ctx["bookName"] = name // 模拟存入上下文

                log.appendLine("√ 书本详细")
                log.appendLine(
                    "书名：${name}\n作者：${detail.getBookAuthor(html, ctx)}\n封面：${
                        detail.getBookCover(
                            html,
                            ctx
                        )
                    }\n标签：${detail.getBookLabel(html, ctx)}"
                )
                log.appendLine("书本章节URL -> ${detail.getCatalogUrl(html, ctx)}")

            }

            4 -> {
                val chapters = rule.catalog.getChapters(html, ctx)
                log.appendLine("书本章节总数 -> ${chapters.size}")
                chapters.forEachIndexed { i, chapter ->
                    log.appendLine(
                        "${rule.catalog.getChapterName(chapter, ctx)} >> ${
                            rule.catalog.getChapterUrl(
                                chapter,
                                ctx
                            )
                        }"
                    )
                }
            }

            5 -> {
                val text = rule.content.getContent(html, ctx)
                val imageLoader = SingletonImageLoader.get(context!!)
                val headers = NetworkHeaders.Builder().set("X-Internal-Rule-Id", rule.id).build()
                val req = ImageRequest.Builder(context).data(text[0].toString())
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .transformations(MangaDescrambleTransformation(text[0].toString(), rule.id))
                    .httpHeaders(headers)
                    .listener(
                        onSuccess = { _, result ->
                            println("PRE-FETCH SUCCESS: 完成 $｛text[0]｝")
                            saveImageToLocal(imageLoader,text[0].toString(),"D:\\a.png")
                        },
                        onError = { _, result -> println("PRE-FETCH ERROR: ${text[0]} ${result.throwable.message}") }
                    )
                    .build()
                imageLoader.enqueue(req)

                log.appendLine("√ 内容提取成功:\n${text.take(200)}...")
            }
        }
    } catch (e: Exception) {
        log.appendLine("异常: ${e.message}")
    }
    return log.toString()
}

fun saveImageToLocal(
    imageLoader: ImageLoader,
    url: String,
    targetPath: String
) {
    val diskCache = imageLoader.diskCache ?: return

    // 1. 生成 Coil 内部的缓存 Key (必须与 AsyncImage 使用的 key 一致)
    // 如果你在 ImageRequest 里没设 cacheKey，Coil 默认就是 url
    val cacheKey = url

    // 2. 从缓存中获取 Snapshot (读取流)
    val snapshot = diskCache.openSnapshot(cacheKey) ?: return

    snapshot.use { snap ->
        val cacheFilePath = snap.data
        // 3. 将缓存流写入目标文件
        FileSystem.SYSTEM.copy(cacheFilePath, targetPath.toPath())
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
