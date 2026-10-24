package com.zhhz.spider.viewModel

import androidx.lifecycle.viewModelScope
import com.zhhz.spider.ReaderGraph
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.BookDetail
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.SearchBookSource
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.DetailRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.supportsComments
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
    private val sessionRepository: SessionRepository,
    private val ruleRepository: RuleRepository,
    private val cleanupManager: com.zhhz.spider.manager.BookCleanupManager,
    private val commentRepository: com.zhhz.spider.repository.CommentRepository,
    private val downloadManager: com.zhhz.spider.manager.DownloadManager
) : BaseViewModel<DetailUiState, DetailUiIntent, DetailUiEffect>(
    initialState = DetailUiState()
) {

    init {
        loadBookDetail(this.detailUrl, this.ruleId, forceReload = false)
    }

    override fun handleIntent(intent: DetailUiIntent) {
        when (intent) {
            is DetailUiIntent.LoadDetail -> loadBookDetail(intent.detailUrl, intent.ruleId)
            is DetailUiIntent.RefreshDetail -> {
                // 手动刷新：始终以当前选中书源为准重新走网络
                val source = uiState.value.currentSource
                if (source != null) {
                    loadBookDetail(source.url, source.ruleId, forceReload = true)
                } else {
                    loadBookDetail(this.detailUrl, this.ruleId, forceReload = true)
                }
            }
            is DetailUiIntent.ChangeSource -> handleChangeSource(intent.source)
            is DetailUiIntent.ChapterClicked -> handleChapterClicked(intent.chapter)
            is DetailUiIntent.ToggleBookshelf -> handleToggleBookshelf()
            is DetailUiIntent.GoToReader -> handleGoToReader()
            is DetailUiIntent.NavigateBack -> handleNavigateBack()
            is DetailUiIntent.ToggleComments -> handleToggleComments(intent.show)
            is DetailUiIntent.LoadMoreComments -> handleLoadMoreComments()
            is DetailUiIntent.DownloadSelectedChapters -> handleDownloadSelectedChapters(intent.urls)
        }
    }

    /**
     * 选章下载：把勾选的章节丢给全局下载管理器做物理缓存。
     */
    private fun handleDownloadSelectedChapters(urls: List<String>) {
        val source = uiState.value.currentSource
        val bookUrl = source?.url
        val ruleId = source?.ruleId ?: this.ruleId
        if (bookUrl.isNullOrBlank() || urls.isEmpty()) {
            sendEffect(DetailUiEffect.ShowToast("没有可下载的章节"))
            return
        }

        viewModelScope.launch {
            try {
                downloadManager.downloadChapters(
                    bookUrl = bookUrl,
                    ruleId = ruleId,
                    bookTitle = uiState.value.title,
                    chapterUrls = urls
                )
                sendEffect(DetailUiEffect.ShowToast("已加入下载队列：${urls.size} 章"))
            } catch (e: Exception) {
                sendEffect(DetailUiEffect.ShowToast("加入下载队列失败: ${e.message}"))
            }
        }
    }

    /** 当前书源是否支持评论（决定详情页是否显示评论入口） */
    fun supportsComments(): Boolean {
        val source = uiState.value.currentSource ?: return false
        return ruleRepository.getEnabledRules()
            .find { it.id == source.ruleId }
            ?.supportsComments() == true
    }

    private fun handleToggleComments(show: Boolean) {
        updateState { copy(showComments = show) }
        if (show && uiState.value.comments.isEmpty() && !uiState.value.isLoadingComments) {
            loadCommentsInternal(page = 1)
        }
    }

    private fun handleLoadMoreComments() {
        val state = uiState.value
        val source = state.currentSource ?: return
        if (state.isLoadingComments || !state.hasMoreComments || state.comments.isEmpty()) return

        // 💡 去重键：站点忽略页码原样重复时，没有新评论即判定到底
        val knownKeys = state.comments.mapTo(mutableSetOf()) { "${it.user}|${it.content}|${it.time}" }
        val nextPage = state.commentsPage + 1

        updateState { copy(isLoadingComments = true) }

        viewModelScope.launch {
            try {
                val newComments = commentRepository.fetchComments(source.ruleId, source.url, nextPage)
                    .filter { c -> "${c.user}|${c.content}|${c.time}" !in knownKeys }

                // 换源后丢弃过期响应
                if (uiState.value.currentSource != source) return@launch

                updateState {
                    copy(
                        comments = comments + newComments,
                        commentsPage = nextPage,
                        isLoadingComments = false,
                        hasMoreComments = newComments.isNotEmpty()
                    )
                }
            } catch (e: Exception) {
                if (uiState.value.currentSource != source) return@launch
                updateState { copy(isLoadingComments = false) }
                sendEffect(DetailUiEffect.ShowToast("加载评论失败: ${e.message}"))
            }
        }
    }

    private fun loadCommentsInternal(page: Int) {
        val state = uiState.value
        val source = state.currentSource ?: return
        val bookUrl = source.url

        updateState { copy(isLoadingComments = true) }

        viewModelScope.launch {
            try {
                val newComments = commentRepository.fetchComments(source.ruleId, bookUrl, page)
                updateState {
                    copy(
                        comments = if (page == 1) newComments else comments + newComments,
                        commentsPage = page,
                        isLoadingComments = false,
                        hasMoreComments = newComments.isNotEmpty()
                    )
                }
            } catch (e: Exception) {
                updateState { copy(isLoadingComments = false) }
                sendEffect(DetailUiEffect.ShowToast("加载评论失败: ${e.message}"))
            }
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
            ?: listOf(SearchBookSource(ruleId = ruleId, sourceName = "", url = detailUrl))

        // 💡 用规则库中的真实书源名替换占位名称，避免详情页显示“默认书源”
        val resolvedSources = availableSources.map { source ->
            val ruleName = ruleRepository.getEnabledRules()
                .find { it.id == source.ruleId }?.name?.ifBlank { null } ?: source.ruleId
            if (source.sourceName.isBlank() || source.sourceName == "默认书源") {
                source.copy(sourceName = ruleName)
            } else {
                source
            }
        }

        // 标记当前源
        val source = resolvedSources.find { it.ruleId == ruleId && it.url == detailUrl }

        updateState { copy(currentSource = source, availableSources = resolvedSources, isCatalogLoading = true , isLoading = true, chapters = emptyList()) }

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
                            isInBookshelf = localBook.inLibrary
                        )
                    }
                    if (uiState.value.title.isBlank()) {
                        // 数据库实体不含 availableSources：带上函数开头已解析的书源列表，
                        // 否则加载本地书会清空多书源信息导致"换源"只能用一次
                        sessionRepository.saveData(localBook.copy(availableSources = availableSources))
                    }
                }

            // 💡 缓存优先：书架书籍默认完全使用本地数据展示，绝不发网络请求。
            // 旧数据可能还没有简介/目录，此时显示空内容，由用户手动刷新补全。
            if (!forceReload && localBook != null) {
                val localDetail = detailRepository.loadLocalDetail(detailUrl)
                val localChapters = catalogRepository.loadData(detailUrl)
                if (localChapters.isNotEmpty()) {
                    sessionRepository.saveCatalog(localChapters)
                }
                updateState {
                    copy(
                        bookUrl = detailUrl,
                        title = localDetail?.title?.ifBlank { uiState.value.title } ?: uiState.value.title,
                        author = localDetail?.author?.ifBlank { uiState.value.author } ?: uiState.value.author,
                        cover = localDetail?.cover?.ifBlank { uiState.value.cover } ?: uiState.value.cover,
                        desc = localDetail?.desc?.ifBlank { "暂无简介" } ?: "暂无简介",
                        status = localDetail?.status.orEmpty(),
                        latestChapterTitle = localChapters.lastOrNull()?.title.orEmpty(),
                        catalogUrl = localDetail?.catalogUrl.orEmpty(),
                        labels = localDetail?.labels.orEmpty(),
                        isInBookshelf = localBook.inLibrary,
                        lastReadChapterIndex = localBook.lastReadChapterIndex,
                        lastReadChapterTitle = localBook.lastReadChapterTitle,
                        isLoading = false,
                        isCatalogLoading = false,
                        chapters = localChapters
                    )
                }
                return@launch
            }

            val detailData = detailRepository.fetchData(detailUrl, ruleId, bypassSnapshotCache = forceReload)

            // 💡 自动派发：网页数据回来后，拼装成完整的 Book，自动更新内存 Session 和会话克隆
            // availableSources 必须继承当前列表：换源后仍保留全部书源供再次切换，
            // 并确保当前选择的源在列表中（本地库直进或单源进入时列表可能为空）
            val currentSources = uiState.value.availableSources
            val updatedBook = Book(
                title = detailData.title.ifBlank { uiState.value.title },
                author = detailData.author.ifBlank { uiState.value.author },
                cover = detailData.cover.ifBlank { uiState.value.cover },
                url = detailUrl,
                ruleId = ruleId,
                lastReadChapterIndex = uiState.value.lastReadChapterIndex,
                lastReadChapterTitle = uiState.value.lastReadChapterTitle,
                availableSources = with(currentSources) {
                    if (any { it.ruleId == ruleId && it.url == detailUrl }) this
                    else this + SearchBookSource(ruleId = ruleId, sourceName = "", url = detailUrl)
                }
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
                    labels = detailData.labels,
                    isLoading = false
                )
            }

            // 2. 请求目录（手动刷新/换源时 forceReload=true，同步绕过快照拿最新目录）
            val chapterList = catalogRepository.fetchData(detailData.catalogUrl, ruleId, bypassSnapshotCache = forceReload)
            updateState {
                copy(
                    chapters = chapterList,
                    isCatalogLoading = false,
                    latestChapterTitle = detailData.latestChapterTitle.ifBlank { chapterList.lastOrNull()?.title.orEmpty() }
                )
            }

            // 💡 详情持久化：把简介/标签/目录地址等写回本地，下次进入直接秒开
            // 仅更新数据库中已存在的行（浏览未收藏的书不会生成书架记录）
            detailRepository.saveDetailInfo(detailUrl, detailData)

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
                route = ReaderGraph.Reader(
                    bookUrl = state.bookUrl,
                    chapterIndex = chapter.index,
                    chapterTitle = state.title,
                    ruleId = ruleId
                )
            )
        )
    }

    private fun handleGoToReader() {
        val state = uiState.value
        val ruleId = state.currentSource?.ruleId ?: return

        // 1. 将完整的目录列表存入内存，供阅读器做“上一章/下一章”极速切换
        sessionRepository.saveCatalog(state.chapters)

        // 2. 传递轻量级基本类型路由对象跳转
        // 如果有阅读进度，传 -1 告诉阅读器自动恢复进度；否则传第一章的 index (0)
        val targetIndex = if (state.lastReadChapterIndex >= 0) -1 else 0

        sendEffect(
            DetailUiEffect.NavigateToReader(
                route = ReaderGraph.Reader(
                    bookUrl = state.bookUrl,
                    chapterIndex = targetIndex,
                    chapterTitle = state.title,
                    ruleId = ruleId
                )
            )
        )
    }


    private fun handleToggleBookshelf() {
        val state = uiState.value
        if (state.isLoading || state.isCatalogLoading || state.currentSource == null) {
            sendEffect(DetailUiEffect.ShowToast("书本加载完成后才能操作书架"))
            return
        }
        // 获取当前正在浏览的书源 URL
        val url = state.currentSource.url

        val ruleId = state.currentSource.ruleId

        if (url.isNullOrBlank() || ruleId.isNullOrBlank()) {
            sendEffect(DetailUiEffect.ShowToast("未获取到当前书源链接，无法操作"))
            return
        }

        viewModelScope.launch {
            try {
                if (state.isInBookshelf) {
                    // 💡 情况 A：已经在书架，执行“移出书架”操作
                    // 软移除：清图片磁盘缓存并停掉下载任务，保留章节目录与阅读进度（可在历史记录找回）
                    cleanupManager.cleanup(url, clearTextCache = false)
                    detailRepository.setInLibrary(url, false)
                    sendEffect(DetailUiEffect.ShowToast("已移出书架（阅读历史中可找回）"))
                } else {
                    // 💡 情况 B：不在书架，执行“加入书架”操作
                    // 构建数据库需要的 BookEntity 实体（首次加入，阅读进度保持未读状态）
                    val entity = Book(
                        url = url,
                        title = state.title,
                        author = state.author,
                        cover = state.cover,
                        ruleId = ruleId
                    )

                    // 严格遵循规范命名：保存调用 saveData
                    detailRepository.saveData(entity)
                    catalogRepository.saveData(url, state.chapters)
                    // 💡 加入书架时同步持久化详情信息（简介/标签/目录地址），供离线秒开
                    // 分类标签是书架筛选的数据源：只要抓到了标签就落库，即使简介异常
                    if (state.labels.isNotEmpty() || (state.desc.isNotBlank() && state.desc != "加载失败")) {
                        detailRepository.saveDetailInfo(
                            url,
                            BookDetail(
                                url = url,
                                title = state.title,
                                author = state.author,
                                cover = state.cover,
                                desc = state.desc,
                                status = state.status,
                                latestChapterTitle = state.latestChapterTitle,
                                catalogUrl = state.catalogUrl,
                                labels = state.labels
                            )
                        )
                    }
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
    val labels: List<String> = emptyList(),

    // --- 页面状态 ---
    val isLoading: Boolean = true,
    val isInBookshelf: Boolean = false,
    val lastReadChapterIndex: Int = -1,
    val lastReadChapterTitle: String = "",
    val chapters: List<Chapter> = emptyList(),
    val isCatalogLoading: Boolean = true,

    // --- 评论 ---
    val showComments: Boolean = false,
    val comments: List<com.zhhz.spider.network.BookComment> = emptyList(),
    val isLoadingComments: Boolean = false,
    val commentsPage: Int = 1,
    val hasMoreComments: Boolean = true
) : UiState

// 2. 统一的意图
sealed class DetailUiIntent : UiIntent {
    // 页面初始化加载
    data class LoadDetail(val detailUrl: String, val ruleId: String) : DetailUiIntent()
    // 手动刷新：强制走网络重新拉取详情与目录
    data object RefreshDetail : DetailUiIntent()
    // 打开/关闭评论面板（首次打开自动加载第一页）
    data class ToggleComments(val show: Boolean) : DetailUiIntent()
    // 评论翻页
    data object LoadMoreComments : DetailUiIntent()
    // 切换书源
    data class ChangeSource(val source: SearchBookSource) : DetailUiIntent()
    // 章节点击意图
    data class ChapterClicked(val chapter: Chapter) : DetailUiIntent()
    // 收藏动作
    data object ToggleBookshelf : DetailUiIntent()
    // 点击开始阅读
    data object GoToReader : DetailUiIntent()

    /** 选章下载：把勾选的章节加入后台下载队列 */
    data class DownloadSelectedChapters(val urls: List<String>) : DetailUiIntent()

    data object NavigateBack : DetailUiIntent()
}

// 3. 统一的副作用
sealed class DetailUiEffect : UiEffect {
    data class ShowToast(val message: String) : DetailUiEffect()
    // 跳转到阅读器/目录页，依然只传基本类型
    data class NavigateToReader(val route: ReaderGraph.Reader) : DetailUiEffect()

    data object NavigateBack : DetailUiEffect()
}
