package com.zhhz.spider.util

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.Options
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.repository.RuleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import okio.Buffer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

class DecryptingFetcher(
    private val bookUrl: String,
    private val url: String,
    private val ruleId: String,
    private val options: Options,
    private val delegate: Fetcher,
    private val diskCache: DiskCache?
) : Fetcher, KoinComponent {

    companion object {
        // 💡 1. 核心：定义一个全局唯一的静态锁（JVM 级）
        // 用于绝对保护非线程安全的 SCRIPT_ENGINE 和 AWT 拼图过程
        private val jvmDecryptLock = Any()
    }

    private val ruleRepository: RuleRepository by inject()
    private val contextSessionManager: ContextSessionManager by inject()

    override suspend fun fetch(): FetchResult? {

        // 拿到原始混淆流
        val result = delegate.fetch() // 💡 传入只读选项

        val rule = ruleRepository.getEnabledRules().find { it.id == ruleId }

        if (rule == null || rule.content.decryptImage.isEmpty()) return result

        if (result is SourceFetchResult && result.dataSource == DataSource.NETWORK) {

            val scrambledBytes = result.source.source().readByteArray()

            val bitmap = scrambledBytes.toCoilBitmap()
            val bindings = SimpleBindings()
            val ctx = contextSessionManager.getContext(bookUrl, ruleId)
            bindings.put("java", JsExtensionClass)
            bindings.put("java_ctx", ctx)
            bindings.put("java_url", url)
            bindings.put("java_log", logger)
            bindings.put("bitmap", bitmap)

            // 2. 内存解密拼图还原
            val decryptRule = rule.content.decryptImage
            //val cleanBytes = MangaDescrambler.descrambleAndEncode(rule,scrambledBytes, decryptRule, bindings)
            //val detectedFormat = ImageFormatDetector.detectFormat(scrambledBytes)
            val detectedFormat = ImageFormatDetector.detectFormat(scrambledBytes)

            val cleanBytes = bitmap.descrambleAndEncode(decryptRule, detectedFormat, bindings) ?: scrambledBytes

            // 💡 4. 【核心大招】：由我们自己接管写盘，并强行向 journal 注册！

            val diskCacheKey = options.diskCacheKey ?: url

            // 如果磁盘缓存可用，且本次请求允许写入
            if (diskCache != null && options.diskCachePolicy.writeEnabled) {
                // 打开磁盘缓存编辑器

                val editor = diskCache.openEditor(diskCacheKey)
                if (editor != null) {
                    var committed = false
                    try {
                        // 💡 A. 将解密拼好后的完美成品 JPG，使用 Okio 直接写入磁盘缓存的物理临时文件！
                        options.fileSystem.write(editor.data) {
                            write(cleanBytes)
                        }

                        // 💡 B. 极其重要：手动调用 commit()！
                        // 这个 commit() 会在底层向 journal 索引文件写入一条标准的 "CLEAN" 记录，
                        // 只有写入了这条记录，Coil 3 才能真正建立该文件的缓存索引，下次离线时才能命中！
                        val snapshot = editor.commitAndOpenSnapshot()
                        committed = true // 标记写入成功
                        // 💡 C. 完美闭环：返回一个【指向该磁盘缓存文件】的 SourceResult！
                        // 并指定其 DataSource 为 DISK（或者 NETWORK 均可，此时因为有了 journal 索引，无所谓了），
                        // 后续的解码器可以直接从这个刚刚写好的磁盘物理文件里极速读取！
                        if (snapshot != null) {
                            return SourceFetchResult(
                                source = ImageSource(
                                    file = snapshot.data,
                                    fileSystem = options.fileSystem,
                                    diskCacheKey = diskCacheKey
                                ),
                                mimeType = result.mimeType,
                                dataSource = DataSource.NETWORK
                            )
                        }
                    } catch (e: CancellationException) {
                        // 💡 1. 协程取消：立刻安全回滚，并【强行重抛】取消信号
                        try { editor.abort() } catch (ignored: Exception) {}
                        throw e
                    } catch (e: Exception) {
                        // 💡 2. 其他写入异常（如磁盘满、文件锁死）：安全回滚，不准抛出！
                        if (!committed) {
                            try {
                                editor.abort() // 💡 保护：即使 abort 报错，也直接吃掉，绝对不准它逃逸出去卡死 UI！
                            } catch (_: Exception) {}
                        }
                        e.printStackTrace()
                        logger.error { "写入磁盘缓存失败: ${e.message}" }
                    }
                }
            }

            val cleanSource = Buffer().write(cleanBytes)

            // 💡 3. 终极魔法：我们将来源标记为 NETWORK，且返回最原始的、允许写盘的 options
            return SourceFetchResult(
                source = ImageSource(cleanSource, options.fileSystem),
                mimeType = result.mimeType ?: "image/jpeg",
                // 👈 核心：告诉 Coil 引擎“这是刚下下来的网络新数据，但还没写过盘”
                dataSource = DataSource.NETWORK
            )

        }
        return result
    }


    class Factory(
        private val ktorFactory: Fetcher.Factory<Uri> // 💡 对齐上一问修改的 Uri 泛型
    ) : Fetcher.Factory<Uri> {

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ruleId = options.httpHeaders["X-Internal-Rule-Id"] ?: return null
            val bookUrl = options.httpHeaders["X-Internal-Book-Url"] ?: return null

            // 💡 1. 核心：在【创建期】就将只读的 options 强行塞给原生的下载器！
            val readOnlyOptions = options.copy(
                diskCachePolicy = CachePolicy.READ_ONLY
            )

            // 使用只读参数，构建原生 delegate。此时原生下载器一出生就被阉割了写盘权
            val delegate = ktorFactory.create(data, readOnlyOptions, imageLoader) ?: return null

            // 💡 2. 构建我们自己的解密获取器时，依然把【允许写盘】的原始 options 塞给它
            return DecryptingFetcher(bookUrl, data.toString(), ruleId, options, delegate, imageLoader.diskCache)
        }
    }

}