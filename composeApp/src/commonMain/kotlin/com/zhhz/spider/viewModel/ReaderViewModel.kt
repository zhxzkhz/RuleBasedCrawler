package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.constant.BookType
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.hasLegacyInvalidTitle
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.ReaderRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import com.zhhz.spider.util.retry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class ReaderViewModel(
    private val readerRepository: ReaderRepository,
    private val catalogRepository: CatalogRepository,
    private val sessionRepository: SessionRepository,
    private val ruleRepository: RuleRepository,
    private val settingsRepository: com.zhhz.spider.settings.SettingsRepository
) : BaseViewModel<ReaderUiState, ReaderUiIntent, ReaderUiEffect>(
    initialState = ReaderUiState()
) {
    companion object {
        private const val PRELOAD_COUNT = 5
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_BASE_MS = 1000L
    }

    /** 最近一次持久化的应用设置（含阅读器），变更时整体写回 */
    private var persistedSettings = com.zhhz.spider.settings.SettingsRepository.AppSettings()

    init {
        // 启动时异步加载阅读设置
        viewModelScope.launch {
            val saved = settingsRepository.load()
            persistedSettings = saved
            updateState {
                copy(settings = ReaderSettings(
                    fontSize = saved.readerFontSize,
                    backgroundColor = saved.readerBackground,
                    textColor = saved.readerTextColor,
                    mangaMode = saved.readerMangaMode,
                    lineSpacing = saved.readerLineSpacing,
                    paragraphSpacing = saved.readerParagraphSpacing,
                    margin = saved.readerMargin,
                    fontFamily = saved.readerFontFamily,
                    brightness = saved.readerBrightness,
                    useAndroidReader = saved.readerUseAndroidReader,
                    pageAnimation = saved.readerPageAnimation
                ))
            }
        }
    }

    /** 更新内存缓存并异步落盘，避免阻塞 UI 线程 */
    private fun persistSettings(transform: (com.zhhz.spider.settings.SettingsRepository.AppSettings) -> com.zhhz.spider.settings.SettingsRepository.AppSettings) {
        persistedSettings = transform(persistedSettings)
        val snapshot = persistedSettings
        viewModelScope.launch { runCatching { settingsRepository.save(snapshot) } }
    }

    @Volatile private var saveChapterIndex = 0

    @Volatile private var saveChapterProgress = 0

    private val chapterJobs = ConcurrentHashMap<String, Deferred<ChapterBlock>>()
    private var saveProgressJob: Job? = null

    override fun handleIntent(intent: ReaderUiIntent) {
        when (intent) {
            is ReaderUiIntent.Init -> handleInitAndFetch(intent.bookUrl, intent.chapterIndex, intent.ruleId)
            is ReaderUiIntent.GoNext -> handleMoveChapter(1)
            is ReaderUiIntent.GoPrev -> handleMoveChapter(-1)
            is ReaderUiIntent.ChangeChapter -> {
                val targetChapter = uiState.value.catalogList.getOrNull(intent.index)
                if (targetChapter != null) {
                    updateState { copy(isMenuVisible = false) } // 点击后瞬间关掉菜单
                    fetchChapterContent(targetChapter.url, intent.index, uiState.value.ruleId, isAppend = false)
                }
            }
            is ReaderUiIntent.ToggleMenu -> {
                updateState {
                    if (isSettingsVisible) {
                        copy(isSettingsVisible = false)
                    } else {
                        copy(isMenuVisible = !isMenuVisible)
                    }
                }
            }
            is ReaderUiIntent.NavigateBack -> {
                handleUpdateReadProgress(uiState.value.currentIndex, uiState.value.currentProgress)
                sendEffect(ReaderUiEffect.NavigateBack)
            }
            is ReaderUiIntent.UpdateReadProgress -> handleUpdateReadProgress(intent.index, intent.progress)
            is ReaderUiIntent.NavigateToCatalog -> {
                viewModelScope.launch {
                    sendEffect(ReaderUiEffect.NavigateToCatalogPage(uiState.value.bookUrl, uiState.value.ruleId))
                }
            }
            is ReaderUiIntent.ToggleSettingsPanel -> {
                updateState { copy(isSettingsVisible = !isSettingsVisible, isMenuVisible = false) }
            }
            is ReaderUiIntent.ChangeFontSize -> {
                updateState {
                    val newSize = (settings.fontSize + intent.delta).coerceIn(12, 36)
                    persistSettings { it.copy(readerFontSize = newSize) }
                    copy(settings = settings.copy(fontSize = newSize))
                }
            }
            is ReaderUiIntent.ChangeTheme -> {
                updateState {
                    persistSettings { it.copy(readerBackground = intent.background, readerTextColor = intent.text) }
                    copy(settings = settings.copy(backgroundColor = intent.background, textColor = intent.text))
                }
            }
            is ReaderUiIntent.ChangeMangaMode -> {
                updateState {
                    persistSettings { it.copy(readerMangaMode = intent.mode) }
                    copy(settings = settings.copy(mangaMode = intent.mode))
                }
            }
            is ReaderUiIntent.ReloadCurrentMangaChapter -> {
                updateState {
                    copy(
                        imageReloadVersion = imageReloadVersion + 1,
                        isMenuVisible = false
                    )
                }
            }
            is ReaderUiIntent.RetryFetchChapter -> handleRetryFetchChapter(intent.chapterUrl, intent.index)
            is ReaderUiIntent.ChangeLineSpacing -> {
                updateState {
                    persistSettings { it.copy(readerLineSpacing = intent.value) }
                    copy(settings = settings.copy(lineSpacing = intent.value))
                }
            }
            is ReaderUiIntent.ChangeParagraphSpacing -> {
                updateState {
                    persistSettings { it.copy(readerParagraphSpacing = intent.value) }
                    copy(settings = settings.copy(paragraphSpacing = intent.value))
                }
            }
            is ReaderUiIntent.ChangeMargin -> {
                updateState {
                    persistSettings { it.copy(readerMargin = intent.value) }
                    copy(settings = settings.copy(margin = intent.value))
                }
            }
            is ReaderUiIntent.ChangeFontFamily -> {
                updateState {
                    persistSettings { it.copy(readerFontFamily = intent.family) }
                    copy(settings = settings.copy(fontFamily = intent.family))
                }
            }
            is ReaderUiIntent.ChangeBrightness -> {
                updateState {
                    persistSettings { it.copy(readerBrightness = intent.value) }
                    copy(settings = settings.copy(brightness = intent.value))
                }
            }
            is ReaderUiIntent.CopyCurrentPage -> {
                val pageText = uiState.value.content.blocks.firstOrNull()?.text ?: ""
                if (pageText.isNotBlank()) {
                    sendEffect(ReaderUiEffect.CopyToClipboard(pageText))
                }
            }
            is ReaderUiIntent.ToggleAndroidReader -> {
                updateState {
                    persistSettings { it.copy(readerUseAndroidReader = intent.enabled) }
                    copy(settings = settings.copy(useAndroidReader = intent.enabled))
                }
            }
        }
    }

    private fun handleInitAndFetch(bookUrl: String, chapterIndex: Int, ruleId: String) {
        updateState { copy(bookUrl = bookUrl, ruleId = ruleId, isLoading = true) }

        viewModelScope.launch {
            var finalIndex = 0
            try {
                // 💡 1. 动态获取规则，精准判定是小说还是漫画
                val rule = ruleRepository.getEnabledRules().find { it.id == ruleId }
                val bookType = rule?.type ?: BookType.text
                updateState { copy(bookType = bookType) }

                // ==========================================
                // 2. 书籍信息装填与【书架状态确认】
                // ==========================================
                val sessionBook = sessionRepository.loadData()
                val localBook = readerRepository.loadData(bookUrl)
                val isBookInLibrary = localBook != null

                updateState { copy(isInBookshelf = isBookInLibrary) }

                val matchingSessionBook = sessionBook?.takeIf {
                    it.url == bookUrl && it.ruleId == ruleId
                }
                var activeBook = localBook ?: matchingSessionBook ?: Book(
                        title = "未知书籍", author = "", cover = "", url = bookUrl, ruleId = ruleId
                    )
                if (matchingSessionBook == null || localBook != null) {
                    sessionRepository.saveData(activeBook)
                }
                updateState { copy(bookTitle = activeBook.title.ifBlank { "目录" }) }

                // ==========================================
                // 3. 确定阅读起点与页内进度初始化
                // ==========================================
                val initialProgress: Int
                if (chapterIndex >= 0) {
                    finalIndex = chapterIndex
                    initialProgress = 0 // 指定章节进入，页内进度强制清零
                    activeBook = activeBook.copy(lastReadChapterIndex = finalIndex, lastReadPageIndex = 0)
                    sessionRepository.saveData(activeBook)

                    if (isBookInLibrary) {
                        readerRepository.saveData(bookUrl, finalIndex, 0, "", null)
                    }
                } else {
                    finalIndex = activeBook.lastReadChapterIndex
                    initialProgress = activeBook.lastReadPageIndex // 继承历史页内进度
                }
                saveChapterIndex = finalIndex
                saveChapterProgress = initialProgress
                // 💡 将精确进度同步给 UI
                updateState { copy(currentProgress = initialProgress, currentIndex = finalIndex) }

                // ==========================================
                // 4. 三层目录恢复防线
                // ==========================================
                var catalogList = sessionRepository.loadCatalog()

                if (catalogList.any(Chapter::hasLegacyInvalidTitle)) {
                    catalogList = emptyList()
                    sessionRepository.saveCatalog(emptyList())
                }

                if (catalogList.isEmpty() && bookUrl.isNotBlank() && isBookInLibrary) {
                    catalogList = catalogRepository.loadData(bookUrl)
                }

                if (catalogList.isEmpty()) {
                    catalogList = catalogRepository.fetchData(bookUrl, ruleId, bookUrl)
                    sessionRepository.saveCatalog(catalogList)
                    if (isBookInLibrary) {
                        catalogRepository.saveData(bookUrl, catalogList)
                    }
                }

                updateState { copy(catalogList = catalogList) }

                // ==========================================
                // 5. 定位并抓取正文
                // ==========================================
                val targetChapter = catalogList.getOrNull(finalIndex)
                if (targetChapter != null) {
                    fetchChapterContent(targetChapter.url, finalIndex, ruleId, initialProgress, isAppend = false)
                } else {
                    throw Exception("章节索引 [$finalIndex] 越界，目录可能已失效")
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState {
                    copy(
                        content = ChapterContent(blocks = listOf(ChapterBlock(chapterUrl = "", text = "加载失败: ${e.message}", index = finalIndex))),
                        isLoading = false
                    )
                }
                sendEffect(ReaderUiEffect.ShowToast("初始化阅读器失败: ${e.message}"))
            }
        }
    }

    private fun fetchChapterContent(url: String, index: Int, ruleId: String, progress: Int = 0, isAppend: Boolean = false) {
        updateState { copy(chapterUrl = url, isLoading = !isAppend) }

        viewModelScope.launch {
            try {
                val deferred = chapterJobs.computeIfAbsent(url) {
                    viewModelScope.async(Dispatchers.IO) {
                        logger.info { "加载URL $url" }
                        readerRepository.fetchData(url, ruleId, uiState.value.bookUrl)
                    }
                }

                val newBlock = deferred.await()
                val isMangaMode = uiState.value.bookType == BookType.image

                val isEmptyChapter = (isMangaMode && newBlock.images.isEmpty()) || (!isMangaMode && newBlock.text.isBlank())
                val currentChapterTitle = uiState.value.catalogList.getOrNull(index)?.title ?: ""

                val finalBlock = if (isEmptyChapter) {
                    newBlock.copy(
                        chapterTitle = currentChapterTitle, chapterUrl = url, index = index,
                        text = "本章未获取到任何内容，可能是源站异常或解析失败。"
                    )
                } else {
                    newBlock.copy(chapterTitle = currentChapterTitle, chapterUrl = url, index = index)
                }

                updateState {
                    val updatedBlocks = if (isMangaMode && isAppend) {
                        content.blocks + finalBlock // 漫画无限流追加
                    } else {
                        listOf(finalBlock) // 小说整页覆盖
                    }

                    copy(
                        // 💡 修复：如果是追加预加载完成，绝对不修改 currentIndex 和 title
                        currentIndex = if (!isAppend) index else this.currentIndex,
                        currentProgress = if (!isAppend) progress else this.currentProgress,
                        title = if (!isAppend) currentChapterTitle else this.title,
                        // 💡 修复：绝对不能乱盖 currentProgress！保留当前滑动进度！
                        content = ChapterContent(isManga = isMangaMode, blocks = updatedBlocks),
                        isLoading = false,
                        hasNext = index < catalogList.size - 1,
                        hasPrev = index > 0
                    )
                }

                if (isEmptyChapter) {
                    sendEffect(ReaderUiEffect.ShowToast("「${currentChapterTitle}」内容为空"))
                    return@launch
                }

                // 成功打开章节 → 标记已读（供目录页/追更红点使用）
                viewModelScope.launch {
                    runCatching { readerRepository.markChapterRead(uiState.value.bookUrl, url) }
                }

                // 预加载与修剪
                preloadChaptersSerially(startIndex = index, ruleId = ruleId)
                pruneChapterJobs(index)

                if (!isAppend) {
                    handleUpdateReadProgress(index, progress)
                }
            } catch (e: CancellationException) {
                throw e // 💡 必须重抛取消信号
            } catch (e: Exception) {
                updateState {
                    copy(
                        content = ChapterContent(blocks = listOf(ChapterBlock(chapterUrl = "", text = "加载失败: ${e.message}", index = index))),
                        isLoading = false
                    )
                }
                logger.error(e) { "加载失败" }
                sendEffect(ReaderUiEffect.ShowToast("内容加载失败: ${e.message}"))
            }
        }
    }

    private fun handleMoveChapter(offset: Int) {
        val targetIndex = uiState.value.currentIndex + offset
        val targetChapter = uiState.value.catalogList.getOrNull(targetIndex)

        if (targetChapter != null) {
            // 💡 修复：对于漫画且向下滑动（offset > 0）时，必须开启 isAppend = true 进行追加！
            // 对于小说或向上滑动，则是覆盖替换（isAppend = false）
            val isAppend = uiState.value.bookType == BookType.image && offset > 0
            fetchChapterContent(targetChapter.url, targetIndex, uiState.value.ruleId, isAppend = isAppend)
        } else {
            sendEffect(ReaderUiEffect.ShowToast("没有更多章节了"))
        }
    }

    /**
     * 重新获取某一章内容（空章节占位 UI 的"重新获取"按钮）。
     * 只原位替换该章节块，不影响其它已加载章节与当前阅读位置；
     * 同时丢弃内存中缓存的旧请求结果，强制走一次网络。
     */
    private fun handleRetryFetchChapter(url: String, index: Int) {
        val state = uiState.value
        if (state.retryingChapterIndex != -1) return // 已有重试在进行中

        updateState { copy(retryingChapterIndex = index) }

        viewModelScope.launch {
            try {
                // 关键：移除旧的 Deferred 缓存（空结果/失败结果），否则会拿到同样的空数据
                chapterJobs.remove(url)?.cancel()

                val block = withContext(Dispatchers.IO) {
                    readerRepository.fetchData(url, state.ruleId, state.bookUrl)
                }

                val isMangaMode = state.bookType == BookType.image
                val isEmpty = (isMangaMode && block.images.isEmpty()) || (!isMangaMode && block.text.isBlank())
                val chapterTitle = state.catalogList.getOrNull(index)?.title ?: ""
                val finalBlock = block.copy(
                    chapterTitle = chapterTitle,
                    chapterUrl = url,
                    index = index,
                    text = if (isEmpty) "本章未获取到任何内容，可能是源站异常或解析失败。" else block.text
                )

                updateState {
                    copy(
                        content = content.copy(
                            blocks = content.blocks.map { if (it.index == index) finalBlock else it }
                        ),
                        retryingChapterIndex = -1
                    )
                }

                if (isEmpty) {
                    sendEffect(ReaderUiEffect.ShowToast("「$chapterTitle」重新获取后仍为空"))
                } else {
                    // 成功的结果放回缓存供后续复用；空结果不缓存以便再次重试
                    chapterJobs[url] = viewModelScope.async(Dispatchers.IO) { finalBlock }
                    sendEffect(ReaderUiEffect.ShowToast("「$chapterTitle」重新获取成功"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState { copy(retryingChapterIndex = -1) }
                logger.error(e) { "重新获取章节失败 url=$url" }
                sendEffect(ReaderUiEffect.ShowToast("重新获取失败: ${e.message}"))
            }
        }
    }

    private fun handleUpdateReadProgress(index: Int, progress: Int = 0) {
        val state = uiState.value

        if (saveChapterIndex == index && saveChapterProgress == progress) return

        val activeChapter = state.catalogList.getOrNull(index)
        updateState {
            copy(
                currentIndex = index,
                currentProgress = progress,
                title = activeChapter?.title ?: title
            )
        }


        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500L)
            saveChapterIndex = index
            saveChapterProgress = progress

            val latestState = uiState.value
            // 防止 0.5 秒内状态已经发生改变导致存错旧数据！
            val latestActiveChapter = latestState.catalogList.getOrNull(latestState.currentIndex)

            if (latestState.isInBookshelf) {
                readerRepository.saveBookReadProgress(
                    bookUrl = latestState.bookUrl,
                    chapterIndex = latestState.currentIndex,
                    pageIndex = latestState.currentProgress,
                    chapterTitle = latestActiveChapter?.title ?: "",
                    chapterUrl = latestActiveChapter?.url ?: ""
                )
            }
        }
    }

    private fun preloadChaptersSerially(startIndex: Int, ruleId: String) {
        viewModelScope.launch() {
            for (i in 1..PRELOAD_COUNT) {
                val targetIndex = startIndex + i
                val targetChapter = uiState.value.catalogList.getOrNull(targetIndex) ?: break
                val url = targetChapter.url

                if (chapterJobs.containsKey(url)) continue

                val deferredJob = async {
                    retry(maxAttempts = MAX_RETRIES, delayBaseMs = RETRY_DELAY_BASE_MS) {
                        readerRepository.fetchData(url, ruleId, uiState.value.bookUrl)
                    }
                }
                chapterJobs[url] = deferredJob

                try {
                    deferredJob.await()
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun pruneChapterJobs(currentIndex: Int) {
        val urlsToKeep = mutableListOf<String>()
        uiState.value.catalogList.getOrNull(currentIndex - 1)?.let { urlsToKeep.add(it.url) }
        uiState.value.catalogList.getOrNull(currentIndex)?.let { urlsToKeep.add(it.url) }
        for (i in 1..PRELOAD_COUNT) {
            uiState.value.catalogList.getOrNull(currentIndex + i)?.let { urlsToKeep.add(it.url) }
        }

        val iterator = chapterJobs.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in urlsToKeep) {
                entry.value.cancel()
                iterator.remove()
            }
        }
    }
}


@Serializable
data class MangaImage(
    // 1. 漫画图片真实的下载/加载 URL
    val url: String,

    // 2. 💡 扁平化的请求头 Map（已在仓库层动态完成了 {bookId}、{chapterId} 占位符和 CSS 选择器提取替换）
    val headers: MutableMap<String, String> = mutableMapOf(),

    // 3. 💡 书源规则 ID（供后台解密线程 MangaDescrambleTransformation 通过 Koin 极速查找 rule）
    val ruleId: String
)

// 1. 独立出一个“章节区块”模型，代表一话漫画或一章小说
data class ChapterBlock(
    val chapterUrl: String,   // 这一章的唯一标识 URL
    val chapterTitle: String = "加载中...", // 这一章的标题 (用于在漫画中间显示 "--- 第 xx 话 ---")
    val index: Int = 0,
    val text: String = "",    // 小说文本
    val isImageDecrypt: Boolean = false,
    val images: List<MangaImage> = emptyList(), // 漫画图片列表
    val inlineImages: List<MangaImage> = emptyList() // 小说正文中的插图，顺序由 text 内的占位符决定
)

data class ChapterContent(
    val isManga: Boolean = false,
    // 从单一内容变成内容块的列表！
    val blocks: List<ChapterBlock> = emptyList()
)

data class ReaderSettings(
    val fontSize: Int = 18,
    val backgroundColor: Long = 0xFFFAF7F0,
    val textColor: Long = 0xFF333333,
    val mangaMode: String = "webtoon",
    val lineSpacing: Int = 10,
    val paragraphSpacing: Int = 5,
    val margin: Float = 40f,
    val fontFamily: String = "serif",
    val brightness: Float = 1.0f,
    val useAndroidReader: Boolean = false,
    val pageAnimation: Boolean = true
)

data class ReaderUiState(
    val bookUrl: String = "",
    val bookTitle: String = "",
    val bookType: Int = BookType.text,
    val title: String = "正在加载...",
    val content: ChapterContent = ChapterContent(),
    val chapterUrl: String = "",
    val ruleId: String = "",
    val currentIndex: Int = -1,
    val currentProgress: Int = 0,
    // 控制上下页按钮状态
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,

    val catalogList: List<Chapter> = emptyList(),

    // 💡 新增：标记这本书是否已经在本地书架中
    val isInBookshelf: Boolean = false,

    val isLoading: Boolean = true,
    val isMenuVisible: Boolean = false, // 点击屏幕中央显示的菜单状态
    val imageReloadVersion: Int = 0,

    // 空章节"重新获取"进行中的章节索引（-1 = 无）
    val retryingChapterIndex: Int = -1,

    val settings: ReaderSettings = ReaderSettings(),
    val isSettingsVisible: Boolean = false // 排版设置面板状态

) : UiState

sealed class ReaderUiIntent : UiIntent {
    // 初始化内容
    data class Init(val bookUrl: String, val chapterIndex: Int, val ruleId: String) : ReaderUiIntent()

    object NavigateToCatalog : ReaderUiIntent()

    // 设置相关意图
    object ToggleSettingsPanel : ReaderUiIntent()
    data class ChangeFontSize(val delta: Int) : ReaderUiIntent()
    data class ChangeTheme(val background: Long, val text: Long) : ReaderUiIntent()

    /** 切换漫画阅读模式：webtoon / paged / rtl */
    data class ChangeMangaMode(val mode: String) : ReaderUiIntent()

    object ReloadCurrentMangaChapter : ReaderUiIntent()

    // 切换章节
    data object GoNext : ReaderUiIntent()
    data object GoPrev : ReaderUiIntent()

    data class ChangeChapter(val index: Int) : ReaderUiIntent() // 💡 目录点击跳转意图

    /** 空章节占位 UI：重新获取本章内容（强制走网络，原位替换） */
    data class RetryFetchChapter(val chapterUrl: String, val index: Int) : ReaderUiIntent()

    // 交互切换
    data object ToggleMenu : ReaderUiIntent()
    data object NavigateBack : ReaderUiIntent()

    data class UpdateReadProgress(val index: Int, val progress: Int = 0) : ReaderUiIntent()
    data class ChangeLineSpacing(val value: Int) : ReaderUiIntent()
    data class ChangeParagraphSpacing(val value: Int) : ReaderUiIntent()
    data class ChangeMargin(val value: Float) : ReaderUiIntent()
    data class ChangeFontFamily(val family: String) : ReaderUiIntent()
    data class ChangeBrightness(val value: Float) : ReaderUiIntent()
    data object CopyCurrentPage : ReaderUiIntent()
    data class ToggleAndroidReader(val enabled: Boolean) : ReaderUiIntent()
}

sealed class ReaderUiEffect : UiEffect {
    data class ShowToast(val message: String) : ReaderUiEffect()
    object NavigateBack : ReaderUiEffect()
    data class NavigateToCatalogPage(val bookUrl: String, val ruleId: String) : ReaderUiEffect()
    data class CopyToClipboard(val text: String) : ReaderUiEffect()
}
