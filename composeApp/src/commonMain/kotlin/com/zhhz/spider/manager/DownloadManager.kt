package com.zhhz.spider.manager

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.zhhz.spider.model.DownloadStatus
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.ReaderRepository
import com.zhhz.spider.viewModel.MangaImage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.set

private val logger = KotlinLogging.logger {}

class DownloadManager(
    private val catalogRepository: CatalogRepository,
    private val readerRepository: ReaderRepository,
    private val contextSessionManager: ContextSessionManager,
    private val platformContext: PlatformContext,   // 💡 注入跨平台的 Context
    settingsRepository: com.zhhz.spider.settings.SettingsRepository
) {

    private val initialSettings = settingsRepository.loadBlocking()

    /**
     * 可动态调整上限的并发控制器。
     * - 新任务使用当前 limit 排队
     * - limit 降低时，已持有的许可不受影响（自然耗尽），新任务按新上限等待
     * - 替换信号量不会泄漏已分配的许可
     */
    private class ResizeableSemaphore(initialLimit: Int) {
        @Volatile private var semaphore = Semaphore(initialLimit)
        private val activePermits = AtomicInteger(0)
        @Volatile private var limit = initialLimit

        fun resize(newLimit: Int) {
            if (newLimit < 1) return
            limit = newLimit
            // 始终用新实例接收新任务；旧实例的 withPermit 仍在其生命周期内正常释放
            semaphore = Semaphore(newLimit)
        }

        suspend fun <T> withPermit(block: suspend () -> T): T {
            val currentSem = semaphore
            currentSem.acquire()
            activePermits.incrementAndGet()
            try {
                return block()
            } finally {
                activePermits.decrementAndGet()
                currentSem.release()
            }
        }
    }

    private val bookSlots = ResizeableSemaphore(initialSettings.downloadBooks)
    private val imageSlots = ResizeableSemaphore(initialSettings.downloadImages)

    /** 设置界面调用：更新全局并发上限（对排队中的任务即时生效） */
    fun updateConcurrency(books: Int, images: Int) {
        bookSlots.resize(books)
        imageSlots.resize(images)
    }

    private val imageLoader = SingletonImageLoader.get(platformContext)
    // 💡 1. 独立于任何 ViewModel 的常驻协程作用域 (守护进程)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 💡 2. 状态流：供 UI (如书架页或专门的下载管理页) 实时观察所有的下载进度
    private val _downloadTasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val downloadTasks: StateFlow<Map<String, DownloadTask>> = _downloadTasks.asStateFlow()

    // 存储真正的协程 Job，用于支持“暂停/取消”功能
    private val activeJobs = mutableMapOf<String, Job>()
    private val lock = Mutex()

    /**
     * 开始或恢复下载某本书（全本）
     */
    suspend fun startDownload(bookUrl: String, ruleId: String, bookTitle: String) =
        launchDownload(bookUrl, ruleId, bookTitle, selectedUrls = null)

    /**
     * 只下载指定章节（选章下载 / 失败章节重试共用）
     */
    suspend fun downloadChapters(
        bookUrl: String,
        ruleId: String,
        bookTitle: String,
        chapterUrls: List<String>
    ) {
        if (chapterUrls.isEmpty()) return
        launchDownload(bookUrl, ruleId, bookTitle, selectedUrls = chapterUrls)
    }

    private suspend fun launchDownload(
        bookUrl: String,
        ruleId: String,
        bookTitle: String,
        selectedUrls: List<String>?
    ) = lock.withLock {
        // 防止重复启动
        if (activeJobs[bookUrl]?.isActive == true) return@withLock

        // 1. 初始化或更新任务状态：先标记为"等待中"，拿到全局书槽位后才转"下载中"
        _downloadTasks.update { current ->
            val task = current[bookUrl] ?: DownloadTask(bookUrl, bookTitle)
            current + (bookUrl to task.copy(status = DownloadStatus.PENDING))
        }

        // 2. 💡 在常驻作用域中启动实质下载任务
        val job = scope.launch {
            try {
                // 💡 全局书级并发上限：排队等待期间可被暂停/取消正常打断
                bookSlots.withPermit {
                    _downloadTasks.update { current ->
                        val task = current[bookUrl] ?: return@update current
                        current + (bookUrl to task.copy(status = DownloadStatus.DOWNLOADING))
                    }

                    contextSessionManager.forkContext(
                        fromKey = ruleId,
                        bookUrl = bookUrl
                    )

                    val ctx = contextSessionManager.getContext(bookUrl)
                    ctx["bookUrl"] = bookUrl

                    // A. 获取最新目录 (默认走双层保底：内存 -> 本地 -> 网络)
                    val catalog = catalogRepository.loadData(bookUrl).ifEmpty {
                        catalogRepository.fetchData(bookUrl, ruleId, bookUrl)
                    }

                    // 💡 选章过滤：保持目录顺序，只下载所选 URL；selectedUrls=null 表示全本
                    val chaptersToFetch = if (selectedUrls != null) {
                        val wanted = selectedUrls.toSet()
                        catalog.filter { it.url in wanted }
                    } else catalog

                    _downloadTasks.update { current ->
                        val task = current[bookUrl] ?: return@update current
                        current + (bookUrl to task.copy(
                            totalChapters = chaptersToFetch.size,
                            downloadedChapters = 0,
                            failedUrls = emptyList()
                        ))
                    }

                    // 本轮失败章节清单（供"重试失败章节"）
                    val failedChapterUrls = mutableSetOf<String>()

                    // B. 开始遍历下载章节
                    for ((index, chapter) in chaptersToFetch.withIndex()) {
                        // 检查协程是否已被取消（比如用户点击了暂停）
                        ensureActive()

                        // 💡 C. 核心：调用 ReaderRepository 抓取正文，实现静默下载缓存！
                        try {
                            val block = readerRepository.fetchData(chapter.url, ruleId, bookUrl)

                            // 💡 2. 核心进阶：如果是漫画，执行实质物理下载！
                            if (block.images.isNotEmpty()) {
                                coroutineScope {
                                    for (mangaImage in block.images) {
                                        ensureActive() // 时刻响应暂停信号

                                        launch {
                                            imageSlots.withPermit {
                                                ensureActive()

                                                val request = imageRequest(
                                                    ruleId, bookUrl, mangaImage, block.isImageDecrypt, platformContext
                                                )
                                                imageLoader.execute(request)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            // 暂停/取消必须向上传播，不能被当成"单章失败"吞掉
                            throw e
                        } catch (e: Exception) {
                            // 某个章节下载失败：记录 URL 供重试，继续后续章节
                            failedChapterUrls.add(chapter.url)
                            logger.error(e) { "章节下载失败: ${chapter.url}" }
                        }

                        // D. 每下载完一章(所有文字/图片都落盘了)，更新进度 UI
                        _downloadTasks.update { currentMap ->
                            val task = currentMap[bookUrl] ?: return@update currentMap
                            currentMap + (bookUrl to task.copy(downloadedChapters = index + 1))
                        }
                    }

                    // 下载全部完成（附带失败清单供重试）
                    _downloadTasks.update { current ->
                        val task = current[bookUrl] ?: return@update current
                        current + (bookUrl to task.copy(
                            status = DownloadStatus.COMPLETED,
                            failedUrls = failedChapterUrls.toList()
                        ))
                    }
                }
            } catch (_: CancellationException) {
                // 用户暂停或系统取消，属于正常流转；任务被"取消移除"时不复活条目
                _downloadTasks.update { current ->
                    val task = current[bookUrl] ?: return@update current
                    current + (bookUrl to task.copy(status = DownloadStatus.PAUSED))
                }
            } catch (e: Exception) {
                logger.error { e }
                // 网络崩溃等真异常
                _downloadTasks.update { current ->
                    val task = current[bookUrl] ?: return@update current
                    current + (bookUrl to task.copy(status = DownloadStatus.ERROR))
                }
            } finally {
                lock.withLock { activeJobs.remove(bookUrl) }
            }
        }
        // 必须在锁内写入，确保 cancelDownload/pauseDownload 不会遗漏
        activeJobs[bookUrl] = job
    }

    /**
     * 暂停下载
     */
    suspend fun pauseDownload(bookUrl: String) = lock.withLock {
        activeJobs[bookUrl]?.cancel() // 💡 取消协程即为暂停
    }

    /**
     * 取消下载：终止任务并从任务列表移除（区别于暂停——不保留续传记录）
     */
    suspend fun cancelDownload(bookUrl: String) = lock.withLock {
        val job = activeJobs.remove(bookUrl)
        // 先移除条目再取消协程：取消回调发现条目不存在就不会把 PAUSED 写回来
        _downloadTasks.update { it - bookUrl }
        job?.cancel()
    }

    /**
     * 清除所有未完成任务（等待中/下载中/暂停/失败），已完成任务保留
     */
    suspend fun cancelUnfinished() = lock.withLock {
        val unfinished = _downloadTasks.value.filterValues { it.status != DownloadStatus.COMPLETED }
        unfinished.keys.forEach { url ->
            activeJobs.remove(url)?.cancel()
        }
        _downloadTasks.update { current -> current.filterValues { it.status == DownloadStatus.COMPLETED } }
    }
}

fun imageRequest(
    ruleId: String,
    bookUrl: String,
    image: MangaImage, // 💡 1. 传入我们自定义的干净数据对象
    isImageDecrypt: Boolean = false,
    context: PlatformContext
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(image.url)
        .memoryCacheKey(image.url)
        .diskCacheKey(image.url)

    val headers = NetworkHeaders.Builder().set("X-Internal-Rule-Id", ruleId)
        .set("X-Internal-Book-Url", bookUrl)

    image.headers.forEach { k, v ->
        headers[k] = v
    }

    builder.httpHeaders(headers.build())

    //builder.fetcherFactory(DecryptingFetcher.Factory(image.ruleId))

    // 💡 3. 如果需要解密，传入解密参数
    if (isImageDecrypt) {
        //builder.transformations(MangaDescrambleTransformation(image.url, ruleId))
    }

    return builder.build()
}
