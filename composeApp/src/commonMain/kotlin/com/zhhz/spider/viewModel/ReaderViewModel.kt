package com.zhhz.spider.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.manager.BookSessionManager
import com.zhhz.spider.manager.RuleManager
import com.zhhz.spider.network.*
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

class ReaderViewModel(
    private val book: Book,
    private val ruleDao: RuleDao,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val taskRunner: FetchTaskRunner,
    private val ruleManager: RuleManager,
    private val bookSessionManager: BookSessionManager,
) : ViewModel() {

    private val ruleId: String = book.ruleId
    private val bookUrl: String = book.detailUrl // 用于更新该书的进度

    lateinit var rule: SourceRule

    private val _currentPageIndex = MutableStateFlow(0)

    val currentPageIndex = _currentPageIndex.asStateFlow()

    // 1. 自动从数据库获取目录（用于跳转抽屉）
    // 使用 Flow 确保书架章节状态（如已读标记）实时更新
    private val chapterEntityFlow = chapterDao.getChaptersFlow(bookUrl)

    // 2. 转换为业务对象流 (Domain Model)
    val chapterListFlow: StateFlow<List<Chapter>> = chapterEntityFlow
        .map { entities ->
            entities.map { entity ->
                Chapter(
                    index = entity.indexNum,
                    title = entity.title,
                    url = entity.url
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )


    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            logger.info { "Starting ReaderViewModel > bookUrl: $bookUrl" }
            rule = ruleManager.getRule(ruleId)
            var tmpBook = bookDao.getBook(bookUrl)
            logger.info { "tmpBook: $tmpBook" }
            if (tmpBook == null) {
                //书本不存在就保存 默认历史书架
                bookDao.addToBookshelf(book.toDomain())
                bookSessionManager.getCatalog(bookUrl,chapterDao).collect { catalog ->
                    bookDao.syncChapters(catalog.map { it.toEntity(bookUrl) })
                }
            }
            logger.info { "book.lastReadChapterUrl: ${chapterListFlow.value[book.lastReadChapterIndex].url}" }
            loadChapterContent(chapterListFlow.value[book.lastReadChapterIndex])
        }
    }

    fun loadChapterContent(chapter: Chapter = chapterListFlow.value[book.lastReadChapterIndex]) {
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading
            try {

                val result = withContext(Dispatchers.IO) {
                    val ctx: VariableContext = rule.ctx
                        val html = taskRunner.fetch(rule, rule.content, chapter.url, ctx)

                    // 解析正文 (这里需要根据你的规则是拿 String 还是 List 处理)
                    val body: List<String> = rule.content.getContent(html, ctx).map {
                        it.toString()
                    }
                    val next = RuleParser.parseString(html, rule.content.nextUrlSelector, ctx)

                    // 【关键：保存进度】


                    ReaderContent(
                        title = "加载中...", // 章节名通常从详情页传过来或从正文抓
                        body = body.joinToString("\n"),
                        images = body,
                        nextChapterUrl = next.takeIf { it.isNotBlank() }
                    )
                }

                _uiState.value = ReaderUiState.Success(result)

            } catch (e: Exception) {
                logger.error(e) {}
                _uiState.value = ReaderUiState.Error(e.message ?: "抓取正文失败")
            }
        }
    }

    // 核心：阅读器更新进度的逻辑
    fun saveProgress(chapter: Chapter = chapterListFlow.value[book.lastReadChapterIndex], pageIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 更新书籍总体进度 (BookDao)
            bookDao.updateLastRead(
                bookUrl = bookUrl,
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                chapterUrl = chapter.url,
                pageIndex = pageIndex
            )

            // 2. 更新章节状态 (ChapterDao)
            chapterDao.updateChapterReadStatus(bookUrl, chapter.index, true)
        }
    }

}


sealed class ReaderUiState {
    data object Loading : ReaderUiState()
    data class Success(val content: ReaderContent) : ReaderUiState()
    data class Error(val message: String) : ReaderUiState()
}