package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.ReaderRoute
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.SearchBookSource
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.DetailRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.ui.base.BaseViewModel
import com.zhhz.spider.ui.base.UiEffect
import com.zhhz.spider.ui.base.UiIntent
import com.zhhz.spider.ui.base.UiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DetailViewModel(
    private val detailUrl: String,
    private val ruleId: String,
    private val detailRepository: DetailRepository,
    private val catalogRepository: CatalogRepository,
    private val sessionRepository: SessionRepository
) : BaseViewModel<DetailUiState, DetailUiIntent, DetailUiEffect>(
    initialState = DetailUiState()
) {

    init {
        loadBookDetail(this.detailUrl, this.ruleId, forceReload = false)
    }

    override fun handleIntent(intent: DetailUiIntent) {
        when (intent) {
            is DetailUiIntent.LoadDetail -> loadBookDetail(intent.detailUrl, intent.ruleId)
            is DetailUiIntent.ChangeSource -> handleChangeSource(intent.source)
            is DetailUiIntent.ChapterClicked -> handleChapterClicked(intent.chapter)
            is DetailUiIntent.ToggleBookshelf -> handleToggleBookshelf()
            is DetailUiIntent.GoToReader -> handleGoToReader()
            is DetailUiIntent.NavigateBack -> handleNavigateBack()
        }
    }

    private fun loadBookDetail(detailUrl: String, ruleId: String, forceReload: Boolean = false) {

        // 💡 核心哨兵防线：阻止导航返回时旧路由参数的“强行篡改”
        // 如果我们当前已经有选中的书源（currentSource 不为空），且目录不为空（说明已经加载成功），
        // 此时被 LaunchedEffect 再次触发，说明一定是用户看完了书“返回”详情页。
        // 我们直接 return 拦截掉它，保护在内存中的“换源状态”不被旧的路由参数重置！
        if (uiState.value.currentSource != null && uiState.value.chapters.isNotEmpty() && !forceReload) {
            return
        }
        val availableSources = sessionRepository.loadData()?.availableSources?.ifEmpty { null }
            ?: listOf(SearchBookSource(ruleId = ruleId, sourceName = "默认书源", url = detailUrl))

        // 标记当前源
        val source = availableSources.find { it.ruleId == ruleId && it.url == detailUrl }

        updateState { copy(currentSource = source, availableSources = availableSources, isCatalogLoading = true , isLoading = true, chapters = emptyList()) }

        // 监听本地书架状态变更（是否已收藏）
        viewModelScope.launch {
            detailRepository.loadBookshelfStatus(detailUrl).collectLatest { isFavorite ->
                updateState { copy(isInBookshelf = isFavorite) }
            }
        }

        viewModelScope.launch {
            try {
                //查询书本是否存在于数据库
                val localBook = detailRepository.loadData(detailUrl)
                if (localBook != null) {
                    updateState {
                        copy(
                            title = localBook.title,
                            author = localBook.author,
                            cover = localBook.cover,
                            isInBookshelf = true
                        )
                    }
                    if (uiState.value.title.isBlank()) {
                        sessionRepository.saveData(localBook)
                    }
                }

                val detailData = detailRepository.fetchData(detailUrl, ruleId)

                // 💡 自动派发：网页数据回来后，拼装成完整的 Book，自动更新内存 Session 和会话克隆
                val updatedBook = Book(
                    title = detailData.title.ifBlank { uiState.value.title },
                    author = detailData.author.ifBlank { uiState.value.author },
                    cover = detailData.cover.ifBlank { uiState.value.cover },
                    url = detailUrl,
                    ruleId = ruleId
                )
                sessionRepository.saveData(updatedBook)

                updateState {
                    copy(
                        bookUrl = detailUrl,
                        title = detailData.title.ifBlank { title },
                        author = detailData.author.ifBlank { author },
                        cover = detailData.cover.ifBlank { cover },
                        desc = detailData.desc,
                        status = detailData.status,
                        latestChapterTitle = detailData.latestChapterTitle,
                        catalogUrl = detailData.catalogUrl,
                        isLoading = false
                    )
                }

                // 2. 请求目录
                val chapterList = catalogRepository.fetchData(detailData.catalogUrl, ruleId)
                updateState {
                    copy(chapters = chapterList, isCatalogLoading = false)
                }

                // 💡 节点 A 自动同步：如果这本书已经在书架中，网络更新完目录后立刻写入本地 DB 保底！
                if (uiState.value.isInBookshelf) {
                    catalogRepository.saveData(detailUrl, chapterList) // 👈 统一命名 saveData
                }

            } catch (e: Exception) {
                updateState { copy(desc = "加载失败", isLoading = false) }
                sendEffect(DetailUiEffect.ShowToast(e.message ?: "获取详情失败"))
            }
        }
    }

    private fun handleChangeSource(newSource: SearchBookSource) {
        // 切换书源：直接复用 LoadDetail 的逻辑重新拉取新源的网络数据
        loadBookDetail(newSource.url, newSource.ruleId, forceReload = true)
        sendEffect(DetailUiEffect.ShowToast("已切换至：${newSource.sourceName}"))
    }

    private fun handleChapterClicked(chapter: Chapter) {
        val state = uiState.value
        val ruleId = state.currentSource?.ruleId ?: return

        // 1. 将完整的目录列表存入内存，供阅读器做“上一章/下一章”极速切换
        sessionRepository.saveCatalog(state.chapters)

        // 2. 传递轻量级基本类型路由对象跳转
        sendEffect(
            DetailUiEffect.NavigateToReader(
                route = ReaderRoute(
                    bookUrl = state.bookUrl,
                    chapterIndex = chapter.index,
                    chapterTitle = state.title,
                    ruleId = ruleId
                )
            )
        )
    }

    private fun handleGoToReader() {
        // 如果点击底部的“开始阅读”按钮，默认打开第一章
        val firstChapter = uiState.value.chapters.firstOrNull()
        if (firstChapter != null) {
            handleChapterClicked(firstChapter)
        } else {
            sendEffect(DetailUiEffect.ShowToast("目录正在加载中，请稍候"))
        }
    }


    private fun handleToggleBookshelf() {
        val state = uiState.value
        // 获取当前正在浏览的书源 URL
        val url = state.currentSource?.url

        val ruleId = state.currentSource?.ruleId

        if (url.isNullOrBlank() || ruleId.isNullOrBlank()) {
            sendEffect(DetailUiEffect.ShowToast("未获取到当前书源链接，无法操作"))
            return
        }

        viewModelScope.launch {
            try {
                if (state.isInBookshelf) {
                    // 💡 情况 A：已经在书架，执行“移出书架”操作
                    // 严格遵循规范命名：删除调用 deleteData
                    detailRepository.deleteData(url)
                    sendEffect(DetailUiEffect.ShowToast("已从书架中移出"))
                } else {
                    // 💡 情况 B：不在书架，执行“加入书架”操作
                    // 构建数据库需要的 BookEntity 实体
                    val entity = Book(
                        url = url,
                        title = state.title,
                        author = state.author,
                        cover = state.cover,
                        ruleId = ruleId,
                        lastReadChapterTitle = state.latestChapterTitle
                    )

                    // 严格遵循规范命名：保存调用 saveData
                    detailRepository.saveData(entity)
                    catalogRepository.saveData(url, state.chapters)
                    sendEffect(DetailUiEffect.ShowToast("已成功加入书架"))
                }

            } catch (e: Exception) {
                // 操作异常，统一抛出 Toast 提示
                sendEffect(DetailUiEffect.ShowToast("书架操作失败: ${e.message}"))
            }
        }
    }

    private fun handleNavigateBack() {
        sendEffect(DetailUiEffect.NavigateBack)
    }

}

// 1. 统一的状态类：包含从 Session 拿到的基础信息，和从网络拉取的详细信息
data class DetailUiState(
    // --- 基础信息 (秒开显示) ---
    val bookUrl: String = "",
    val title: String = "",
    val author: String = "",
    val cover: String = "",
    val availableSources: List<SearchBookSource> = emptyList(),
    val currentSource: SearchBookSource? = null,

    // --- 详细信息 (网络请求后显示) ---
    val desc: String = "加载中...",
    val status: String = "", // 连载/完结
    val latestChapterTitle: String = "",
    val catalogUrl: String = "", // 有些源的目录页和详情页不在同一个URL

    // --- 页面状态 ---
    val isLoading: Boolean = true,
    val isInBookshelf: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val isCatalogLoading: Boolean = true,
) : UiState

// 2. 统一的意图
sealed class DetailUiIntent : UiIntent {
    // 页面初始化加载
    data class LoadDetail(val detailUrl: String, val ruleId: String) : DetailUiIntent()
    // 切换书源
    data class ChangeSource(val source: SearchBookSource) : DetailUiIntent()
    // 章节点击意图
    data class ChapterClicked(val chapter: Chapter) : DetailUiIntent()
    // 收藏动作
    data object ToggleBookshelf : DetailUiIntent()
    // 点击开始阅读
    data object GoToReader : DetailUiIntent()

    data object NavigateBack : DetailUiIntent()
}

// 3. 统一的副作用
sealed class DetailUiEffect : UiEffect {
    data class ShowToast(val message: String) : DetailUiEffect()
    // 跳转到阅读器/目录页，依然只传基本类型
    data class NavigateToReader(val route: ReaderRoute) : DetailUiEffect()

    data object NavigateBack : DetailUiEffect()
}