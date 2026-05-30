package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.constant.BookType
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.SearchBook
import com.zhhz.spider.network.SearchBookSource
import com.zhhz.spider.repository.BookRepository
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.ReaderRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

class ReaderViewModel(
    private val readerRepository: ReaderRepository,
    private val bookRepository: BookRepository,
    private val catalogRepository: CatalogRepository,
    private val sessionRepository: SessionRepository,
    private val contextSessionManager: ContextSessionManager
) : BaseViewModel<ReaderUiState, ReaderUiIntent, ReaderUiEffect>(
    initialState = ReaderUiState()
) {
    // 💡 1. 伴生对象中声明预加载数量，方便随时修改
    companion object {
        private const val PRELOAD_COUNT = 5 // 👈 预加载 5 章
        private const val MAX_RETRIES = 3            // 💡 最大重试次数：3次
        private const val RETRY_DELAY_BASE_MS = 1000L // 💡 基础退避时间：1000毫秒
    }

    private lateinit var book: Book

    // 内存中的完整目录，用于计算上下章索引
    private var catalogList: List<Chapter> = emptyList()

    // 💡 1. 核心缓存池：以章节 URL 为 Key，存储对应的协程异步任务
    private val chapterJobs = mutableMapOf<String, Deferred<ChapterBlock>>()

    private var saveProgressJob: Job? = null

    override fun handleIntent(intent: ReaderUiIntent) {
        when (intent) {
            is ReaderUiIntent.Init -> handleInitAndFetch(intent.bookUrl, intent.chapterIndex, intent.ruleId)
            is ReaderUiIntent.GoNext -> handleMoveChapter(1)
            is ReaderUiIntent.GoPrev -> handleMoveChapter(-1)
            is ReaderUiIntent.ToggleMenu -> updateState { copy(isMenuVisible = !isMenuVisible) }
            is ReaderUiIntent.NavigateBack -> {
                handleUpdateReadProgress(uiState.value.currentIndex,uiState.value.currentProgress)
                sendEffect(ReaderUiEffect.NavigateBack)
            }
            is ReaderUiIntent.UpdateReadProgress -> handleUpdateReadProgress(intent.index, intent.progress)
        }
    }

    private fun handleInitAndFetch(bookUrl: String, chapterIndex: Int, ruleId: String) {
        updateState { copy(bookUrl = bookUrl, ruleId = ruleId, bookType = BookType.image) }

        viewModelScope.launch {

            var finalIndex = 0
            try {

                // ==========================================
                // 1. 书籍信息装填与【书架状态确认】
                // ==========================================
                var activeBook = sessionRepository.loadData()

                // 去本地数据库查一下，只要查到了，说明在书架里！
                val localBook = readerRepository.loadData(bookUrl)
                val isBookInLibrary = localBook != null

                // 更新全局状态，让整个阅读器都知道当前是否在阅读“临时书籍”
                updateState { copy(isInBookshelf = isBookInLibrary) }

                if (activeBook == null) {
                    activeBook = localBook ?: Book(
                        title = "未知书籍", author = "", cover = "", url = bookUrl, ruleId = ruleId
                    )
                    sessionRepository.saveData(activeBook)
                }

                book = activeBook

                // ==========================================
                // 2. 确定阅读起点，拦截“临时书”的写库操作！
                // ==========================================

                if (chapterIndex >= 0) {
                    finalIndex = chapterIndex

                    activeBook = activeBook.copy(lastReadChapterIndex = finalIndex, lastReadPageIndex = 0)
                    sessionRepository.saveData(activeBook)

                    // 💡 安全拦截：只有在书架里的书，才允许写入历史阅读进度！
                    if (isBookInLibrary) {
                        readerRepository.saveData(bookUrl, finalIndex, 0,"",null)
                    }
                } else {
                    finalIndex = activeBook.lastReadChapterIndex
                }

                // ==========================================
                // 3. 三层目录恢复防线，拦截“临时书”的写库操作！
                // ==========================================
                catalogList = sessionRepository.loadCatalog()

                if (catalogList.isEmpty() && bookUrl.isNotBlank()) {
                    // 💡 安全拦截：由于只有加入书架的书才会把目录存数据库，所以不在书架的书直接跳过查库
                    if (isBookInLibrary) {
                        catalogList = catalogRepository.loadData(bookUrl)
                    }
                }

                if (catalogList.isEmpty()) {
                    catalogList = catalogRepository.fetchData(bookUrl, ruleId)
                    sessionRepository.saveCatalog(catalogList)

                    // 💡 安全拦截：只有在书架里的书，才允许持久化目录！
                    if (isBookInLibrary) {
                        catalogRepository.saveData(bookUrl, catalogList)
                    }
                }

                // ==========================================
                // 4. 定位并抓取正文
                // ==========================================
                val targetChapter = catalogList.getOrNull(finalIndex)
                if (targetChapter != null) {
                    fetchChapterContent(targetChapter.url, finalIndex, ruleId)
                } else {
                    throw Exception("章节索引 [$finalIndex] 越界，目录可能已失效")
                }

            } catch (e: Exception) {
                updateState { copy(content = ChapterContent(blocks = listOf(ChapterBlock(chapterUrl = "",text = "加载失败: ${e.message}", index = finalIndex))), isLoading = false) }
                sendEffect(ReaderUiEffect.ShowToast("初始化阅读器失败: ${e.message}"))
            }
        }
    }


    private fun fetchChapterContent(url: String, index: Int, ruleId: String, isAppend: Boolean = false) {
        // 只有在非追加（比如小说翻页、初始加载）时才显示全屏大 Loading
        updateState { copy(chapterUrl = url, isLoading = !isAppend) }

        viewModelScope.launch {
            try {

                // 如果 chapterJobs 里有（说明正在预加载或者预加载完了），直接复用！
                // 如果没有，在 getOrPut 闭包里当场创建 async 启动请求。
                val deferred = chapterJobs.getOrPut(url) {
                    viewModelScope.async(Dispatchers.IO) {
                        logger.info { "加载URL $url" }
                        readerRepository.fetchData(url, ruleId)
                    }
                }

                // - 如果预加载完成了，直接秒开
                // - 如果正在预加载，前台协程挂起在这里等待它完成，绝不重复发起请求！
                val newBlock = deferred.await()
                val isMangaMode = uiState.value.bookType == BookType.image

                val isEmptyChapter = (isMangaMode && newBlock.images.isEmpty()) ||
                        (!isMangaMode && newBlock.text.isBlank())

                val currentChapterTitle = catalogList.getOrNull(index)?.title ?: ""

                val finalBlock = if (isEmptyChapter) {
                    newBlock.copy(
                        chapterTitle = currentChapterTitle,
                        chapterUrl = url,
                        index = index,
                        text = "本章未获取到任何内容，可能是源站异常或解析失败。" // 给小说用的兜底文本
                    )
                } else {
                    newBlock.copy(chapterTitle = currentChapterTitle, chapterUrl = url ,index = index)
                }


                updateState {
                    val updatedBlocks = if (isMangaMode && isAppend) {
                        // 漫画瀑布流：将新的话追加到旧列表尾部
                        content.blocks + finalBlock
                    } else {
                        // 小说：整页覆盖，清空旧数据
                        listOf(finalBlock)
                    }

                    copy(
                        // 顶部标题始终保持为当前块的标题
                        currentProgress = book.lastReadPageIndex,
                        currentIndex = if (!isAppend) index else this.currentIndex,
                        title = if (!isAppend) currentChapterTitle else title,
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

                // 预加载后续章节
                preloadChaptersSerially(startIndex = index, ruleId = ruleId)

                // 内存优化：修剪缓存，只保留当前、上一章、下一章的协程，防止内存溢出
                pruneChapterJobs(index)

                // 💡 小说模式没有视口监听，加载成功即视为阅读，手动触发进度保存
                if (!isMangaMode || !isAppend) {
                    handleUpdateReadProgress(index, 0)
                }
            } catch (e: Exception) {
                updateState { copy(isLoading = false) }
                e.printStackTrace()
                sendEffect(ReaderUiEffect.ShowToast("内容加载失败: ${e.message}"))
            }
        }
    }

    private fun preloadChaptersSerially(startIndex: Int, ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            for (i in 1..PRELOAD_COUNT) {
                val targetIndex = startIndex + i
                val targetChapter = catalogList.getOrNull(targetIndex) ?: break
                val url = targetChapter.url

                if (chapterJobs.containsKey(url)) continue

                // 💡 1. 核心魔改：将重试逻辑包裹在 async 内部
                val deferredJob = async {
                    retry(maxAttempts = MAX_RETRIES, delayBaseMs = RETRY_DELAY_BASE_MS) {
                        readerRepository.fetchData(url, ruleId)
                    }
                }

                // 2. 将这个带自动重试功能的基本任务存入缓存池
                chapterJobs[url] = deferredJob

                try {
                    // 3. 挂起等待当前章节下载完毕（或者 3 次重试彻底失败）
                    deferredJob.await()
                } catch (e: Exception) {
                    // 💡 如果重试了 3 次依然彻底失败，直接中断后续所有章节（N+2, N+3...）的预加载
                    break
                }
            }
        }
    }

    /**
     * 💡 官方推荐的通用指数退避重试工具函数
     * @param maxAttempts 最大重试次数
     * @param delayBaseMs 基础延迟毫秒数
     * @param block 需要执行的挂起代码块
     */
    suspend fun <T> retry(
        maxAttempts: Int,
        delayBaseMs: Long,
        block: suspend () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block() // 💡 成功时直接返回 T 类型，编译器能完美进行类型推断
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) {
                    throw e // 失败超出次数，抛出原始异常
                }
                // 指数退避延迟
                delay(attempt * delayBaseMs)
            }
        }
    }

    // 确保内存里永远只有 3 个章节的数据
    private fun pruneChapterJobs(currentIndex: Int) {
        val urlsToKeep = mutableListOf<String>()

        // 1. 保留前一章（支持用户往回滑，秒开）
        catalogList.getOrNull(currentIndex - 1)?.let { urlsToKeep.add(it.url) }

        // 2. 保留当前正在读的这一章
        catalogList.getOrNull(currentIndex)?.let { urlsToKeep.add(it.url) }

        // 3. 保留正在后台预加载的 5 章
        for (i in 1..PRELOAD_COUNT) {
            catalogList.getOrNull(currentIndex + i)?.let { urlsToKeep.add(it.url) }
        }

        // 执行修剪
        val iterator = chapterJobs.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in urlsToKeep) {
                // 💡 如果用户看得慢，而后台预加载得快，一旦滑动窗口往前移，
                // 那些被甩在后面的、还在下载的协程会被自动 cancel() 掉，绝不浪费用户流量！
                entry.value.cancel()
                iterator.remove() // 释放内存引用
            }
        }
    }

    private fun handleUpdateReadProgress(index: Int, progress: Int = 0) {
        val state = uiState.value

        // 防抖：如果章和页都没变，直接跳过
        if (state.currentIndex == index && state.currentProgress == progress) return

        // 💡 1. 内存极速更新 UI 状态
        val activeChapter = catalogList.getOrNull(index)
        updateState {
            copy(
                currentIndex = index,
                currentProgress = progress,
                title = activeChapter?.title ?: title
            )
        }

        // 💡 2. 统一的高频防抖落盘策略
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1000L) // 缓冲 1 秒，等待用户手停下
            if (state.isInBookshelf) {
                readerRepository.saveData(
                    bookUrl = state.bookUrl,
                    chapterIndex = state.currentIndex,
                    pageIndex = state.currentProgress,
                    chapterTitle = activeChapter?.title ?: "",
                    chapterUrl = activeChapter?.url ?: ""
                )
            }
        }
    }

    private fun handleMoveChapter(offset: Int) {
        // 💡 极简逻辑：直接使用当前索引 + 偏移量，不需要查找 URL！
        val targetIndex = uiState.value.currentIndex + offset
        val targetChapter = catalogList.getOrNull(targetIndex)

        if (targetChapter != null) {
            fetchChapterContent(targetChapter.url, targetIndex, uiState.value.ruleId)
        } else {
            sendEffect(ReaderUiEffect.ShowToast("没有更多章节了"))
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
    val images: List<MangaImage> = emptyList() // 漫画图片列表
)

data class ChapterContent(
    val isManga: Boolean = false,
    // 从单一内容变成内容块的列表！
    val blocks: List<ChapterBlock> = emptyList()
)

data class ReaderUiState(
    val bookUrl: String = "",
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

    // 💡 新增：标记这本书是否已经在本地书架中
    val isInBookshelf: Boolean = false,

    val isLoading: Boolean = true,
    val isMenuVisible: Boolean = false, // 点击屏幕中央显示的菜单状态

) : UiState

sealed class ReaderUiIntent : UiIntent {
    // 初始化内容
    data class Init(val bookUrl: String, val chapterIndex: Int, val ruleId: String) : ReaderUiIntent()
    // 切换章节
    data object GoNext : ReaderUiIntent()
    data object GoPrev : ReaderUiIntent()
    // 交互切换
    data object ToggleMenu : ReaderUiIntent()
    data object NavigateBack : ReaderUiIntent()

    data class UpdateReadProgress(val index: Int, val progress: Int = 0) : ReaderUiIntent()
}

sealed class ReaderUiEffect : UiEffect {
    data class ShowToast(val message: String) : ReaderUiEffect()
    data object NavigateBack : ReaderUiEffect()
}