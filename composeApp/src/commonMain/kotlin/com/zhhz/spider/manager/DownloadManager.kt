package com.zhhz.spider.manager

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import com.zhhz.spider.model.DownloadStatus
import com.zhhz.spider.model.DownloadTask
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.ReaderRepository
import com.zhhz.spider.util.MangaDescrambleTransformation
import com.zhhz.spider.viewModel.MangaImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.collections.set

class DownloadManager(
    private val catalogRepository: CatalogRepository,
    private val readerRepository: ReaderRepository,
    private val contextSessionManager: ContextSessionManager,
    private val platformContext: PlatformContext   // 💡 注入跨平台的 Context
) {

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
     * 开始或恢复下载某本书
     */
    suspend fun startDownload(bookUrl: String, ruleId: String, bookTitle: String) = lock.withLock {
        // 防止重复启动
        if (activeJobs[bookUrl]?.isActive == true) return

        // 1. 初始化或更新任务状态
        _downloadTasks.update { current ->
            val task = current[bookUrl] ?: DownloadTask(bookUrl, bookTitle)
            current + (bookUrl to task.copy(status = DownloadStatus.DOWNLOADING))
        }

        // 2. 💡 在常驻作用域中启动实质下载任务
        val job = scope.launch {
            try {
                contextSessionManager.forkContext(
                    fromKey = ruleId,
                    bookUrl = bookUrl
                )

                val ctx = contextSessionManager.getContext(bookUrl)
                ctx["bookUrl"] = bookUrl

                // A. 获取最新目录 (默认走双层保底：内存 -> 本地 -> 网络)
                val catalog = catalogRepository.loadData(bookUrl).ifEmpty {
                    catalogRepository.fetchData(bookUrl, ruleId,bookUrl)
                }

                _downloadTasks.update {
                    it + (bookUrl to it.getValue(bookUrl).copy(totalChapters = catalog.size))
                }

                // B. 开始遍历下载章节
                for ((index, chapter) in catalog.withIndex()) {
                    // 检查协程是否已被取消（比如用户点击了暂停）
                    ensureActive()

                    // 💡 C. 核心：调用 ReaderRepository 抓取正文，实现静默下载缓存！
                    // 这里由于 fetchData 已经在实现类里做好了防重/写库，这其实就是一个预读。
                    try {
                        // 1. 获取章节区块 (拿到文字，或者图片 URLs)
                        val block = readerRepository.fetchData(chapter.url, ruleId,bookUrl)

                        // 💡 2. 核心进阶：如果是漫画，执行实质物理下载！
                        if (block.images.isNotEmpty()) {
                            // 为了不把服务器打挂，也可以用 forEach 串行下载；这里为了速度，可以使用受限的并发
                            coroutineScope {
                                for (mangaImage in block.images) {
                                    ensureActive() // 时刻响应暂停信号

                                    launch {
                                        // 💡 构建静默缓存请求
                                        val request = imageRequest(ruleId,bookUrl,mangaImage,block.isImageDecrypt, platformContext)

                                        // 💡 魔力操作：使用 execute！
                                        // 它会在后台默默地下载图片，写入我们配置的 DiskCache。
                                        // 如果之前已经下载过了，它会自动秒回，绝不浪费流量。
                                        imageLoader.execute(request)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 某个章节下载失败，可以记录错误并跳过，或者直接终止
                        e.printStackTrace()
                    }

                    // D. 每下载完一章(所有文字/图片都落盘了)，更新进度 UI
                    _downloadTasks.update { currentMap ->
                        val task = currentMap.getValue(bookUrl)
                        currentMap + (bookUrl to task.copy(downloadedChapters = index + 1))
                    }
                }

                // 下载全部完成
                _downloadTasks.update {
                    it + (bookUrl to it.getValue(bookUrl).copy(status = DownloadStatus.COMPLETED))
                }

            } catch (e: CancellationException) {
                // 用户暂停或系统取消，属于正常流转
                _downloadTasks.update {
                    it + (bookUrl to it.getValue(bookUrl).copy(status = DownloadStatus.PAUSED))
                }
            } catch (e: Exception) {
                // 网络崩溃等真异常
                _downloadTasks.update {
                    it + (bookUrl to it.getValue(bookUrl).copy(status = DownloadStatus.ERROR))
                }
            } finally {
                activeJobs.remove(bookUrl)
            }
        }
        activeJobs[bookUrl] = job
    }

    /**
     * 暂停下载
     */
    suspend fun pauseDownload(bookUrl: String) = lock.withLock {
        activeJobs[bookUrl]?.cancel() // 💡 取消协程即为暂停
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
        .size(Size.ORIGINAL)

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