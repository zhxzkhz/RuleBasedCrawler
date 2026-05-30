package com.zhhz.spider.util

import coil3.Bitmap
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
import com.zhhz.spider.rule.SCRIPT_ENGINE
import io.github.oshai.kotlinlogging.KotlinLogging
import okio.Buffer
import org.jetbrains.skia.Image
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import javax.script.ScriptException
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

            val cleanBytes = descrambleAndEncode(bitmap, decryptRule, bindings) ?: scrambledBytes

            // 💡 4. 【核心大招】：由我们自己接管写盘，并强行向 journal 注册！

            val diskCacheKey = options.diskCacheKey ?: url

            // 如果磁盘缓存可用，且本次请求允许写入
            if (diskCache != null && options.diskCachePolicy.writeEnabled) {
                // 打开磁盘缓存编辑器

                val editor = diskCache.openEditor(diskCacheKey)
                if (editor != null) {
                    try {
                        // 💡 A. 将解密拼好后的完美成品 JPG，使用 Okio 直接写入磁盘缓存的物理临时文件！
                        options.fileSystem.write(editor.data) {
                            write(cleanBytes)
                        }

                        // 💡 B. 极其重要：手动调用 commit()！
                        // 这个 commit() 会在底层向 journal 索引文件写入一条标准的 "CLEAN" 记录，
                        // 只有写入了这条记录，Coil 3 才能真正建立该文件的缓存索引，下次离线时才能命中！
                        editor.commit()

                        // 💡 C. 完美闭环：返回一个【指向该磁盘缓存文件】的 SourceResult！
                        // 并指定其 DataSource 为 DISK（或者 NETWORK 均可，此时因为有了 journal 索引，无所谓了），
                        // 后续的解码器可以直接从这个刚刚写好的磁盘物理文件里极速读取！
                        return SourceFetchResult(
                            source = ImageSource(
                                file = editor.data,
                                fileSystem = options.fileSystem,
                                diskCacheKey = diskCacheKey
                            ),
                            mimeType = result.mimeType,
                            dataSource = DataSource.NETWORK
                        )
                    } catch (e: Exception) {
                        // 💡 容错：如果在写入或 commit 时发生异常，必须调用 abort() 回滚，
                        // 这样可以立刻清除临时文件并防止损坏 journal 索引文件！
                        editor.abort()
                        e.printStackTrace()
                    }
                }
            }

            val cleanSource = Buffer().write(cleanBytes)

            // 💡 3. 终极魔法：我们将来源标记为 NETWORK，且返回最原始的、允许写盘的 options
            return SourceFetchResult(
                source = ImageSource(cleanSource, options.fileSystem),
                mimeType = "image/jpeg",
                // 👈 核心：告诉 Coil 引擎“这是刚下下来的网络新数据，但还没写过盘”
                dataSource = DataSource.NETWORK
            )

        }
        return result
    }

    fun descrambleAndEncode(
        bitmap: Bitmap,
        decryptRule: String,
        bindings: SimpleBindings
    ): ByteArray? {
        synchronized(jvmDecryptLock) {
            return try {
                var output: Bitmap = bitmap

                if (decryptRule.isNotBlank()) {
                    try {
                        output =
                            JsExtensionClass.jsToJavaObject(SCRIPT_ENGINE.eval(decryptRule, bindings)) as Bitmap
                    } catch (e: ScriptException) {
                        val errorDetail = """
                JS执行失败！
                错误原因: ${e.message}
                错误行号: ${e.lineNumber}
                错误源码: ${e.columnNumber}
                错误堆栈: ${e.stackTrace.joinToString("\n")}
                """.lines().joinToString("\n") { it.trimStart() }
                        logger.error { errorDetail }
                    } catch (e: Exception) {
                        // 捕获 Java 层的 NPE 或其他异常
                        logger.error { e }
                    }
                }

                Image.makeFromBitmap(output).encodeToData()!!.bytes
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

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