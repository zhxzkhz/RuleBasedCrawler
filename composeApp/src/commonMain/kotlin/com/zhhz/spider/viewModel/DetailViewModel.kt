package com.zhhz.spider.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhhz.spider.db.*
import com.zhhz.spider.manager.BookSessionManager
import com.zhhz.spider.manager.RuleManager
import com.zhhz.spider.network.*
import com.zhhz.spider.network.RuleApi.runBookDetailLogic
import com.zhhz.spider.network.RuleApi.runCatalogLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DetailViewModel(
    private val ruleId: String,
    private val detailUrl: String,
    private val ruleDao: RuleDao,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val taskRunner: FetchTaskRunner,
    private val ruleManager: RuleManager,
    private val bookSessionManager: BookSessionManager,
) : ViewModel() {

    // 【关键】数据库状态：实时观察这本书在不在书架上
    // 如果存在，它就是 Book；如果不存在，它就是 null
    val bookInLibrary: StateFlow<Book?> = bookDao.getBookFlow(detailUrl)
        .map { it?.toDomain() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // UI 状态：只处理加载和错误信息
    private val _detailState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val detailState = _detailState.asStateFlow()

    private val _catalogNetworkState = MutableStateFlow<Resource<List<Chapter>>>(Resource.Loading)
    val catalogNetworkState = _catalogNetworkState.asStateFlow()

    // 目录流：统一从数据库获取。无论是否在书架，都应该有一致的数据获取流
    // 如果未收藏，则从 Repository 获取临时目录
    val uiCatalogState: StateFlow<Resource<List<Chapter>>> =
        combine(
            chapterDao.getChaptersFlow(detailUrl), // 1. 监听数据库
            _catalogNetworkState              // 2. 监听网络请求状态 (Loading/Error/Success)
        ) { dbChapters, networkState ->
            // 逻辑：如果数据库有数据，优先展示数据（乐观更新）
            if (dbChapters.isNotEmpty()) {
                Resource.Success(dbChapters.map { it.toDomain() })
            } else {
                // 如果数据库空，直接透传网络状态
                networkState
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Resource.Loading)

    // UI 显示的加载状态（网络抓取中）
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()


    init {
        loadBookDetail()
    }


    fun loadBookDetail() {
        viewModelScope.launch {
            _detailState.value = DetailUiState.Loading
            try {
                val rule = ruleManager.getRule(ruleId)
                val detail = runBookDetailLogic(taskRunner, rule, detailUrl)
                _detailState.value = DetailUiState.Success(detail)

                // 自动同步目录
                loadCatalog(detail)
            } catch (e: Exception) {
                _detailState.value = DetailUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // --- 任务 2：加载章节目录 ---
    fun loadCatalog(detail: BookDetail? = null) {
        viewModelScope.launch {
            _catalogNetworkState.value = Resource.Loading
            try {
                val rule = ruleManager.getRule(ruleId)
                val list = runCatalogLogic(taskRunner, rule, detailUrl)
                _catalogNetworkState.value = Resource.Success(list)
                // 如果已经在书架，持久化；否则存入内存仓库
                if (bookInLibrary.value != null) {
                    bookDao.syncChapters(list.map {
                        it.toEntity(detail?.url ?: detailUrl)
                    })
                } else {
                    bookSessionManager.setTransientCatalog(list)
                }
            } catch (e: Exception) {
                _catalogNetworkState.value = Resource.Error(e.message ?: "网络异常")
            }
        }
    }

    fun addToBookshelf(detail: BookDetail) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = BookEntity(
                detailUrl = detailUrl,
                title = detail.title,
                author = detail.author,
                cover = detail.cover,
                ruleId = ruleId
            )
            bookDao.addToBookshelf(entity)
            bookSessionManager.getCatalog(detailUrl, chapterDao).collect { catalog ->
                bookDao.syncChapters(catalog.map { it.toEntity(detail.url) })
            }
        }
    }

    fun getBook(): Book {
        if (bookInLibrary.value != null) return bookInLibrary.value!!
        val detail: BookDetail = (_detailState.value as DetailUiState.Success).detail
        return Book(
            detailUrl = detailUrl,
            title = detail.title,
            author = detail.author,
            cover = detail.cover,
            ruleId = ruleId,
        )
    }
}

sealed class DetailUiState {
    data object Loading : DetailUiState()
    data class Success(val detail: BookDetail) : DetailUiState()
    data class Error(val message: String) : DetailUiState()
}


// 目录状态
sealed class CatalogState {
    data object Idle : CatalogState() // 初始状态，等待信息加载
    data object Loading : CatalogState()
    data class Success(val chapters: List<Chapter>) : CatalogState()
    data class Error(val msg: String) : CatalogState()
}

// 封装网络请求的结果
sealed class Resource<out T> {
    data object Loading : Resource<Nothing>()

    // 关键：Success 增加一个 isLoading 标记，允许“带着旧数据刷新”
    data class Success<T>(val data: T, val isRefreshing: Boolean = false) : Resource<T>()
    data class Error(val message: String) : Resource<Nothing>()
}