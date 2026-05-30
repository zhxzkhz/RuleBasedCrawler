package com.zhhz.spider.util

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.manager.imageRequest
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.ReaderRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.viewModel.ChapterBlock
import com.zhhz.spider.viewModel.MangaImage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class BookPackager(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val ruleRepository: RuleRepository,
    private val readerRepository: ReaderRepository,
    private val catalogRepository: CatalogRepository,
    private val platformContext: PlatformContext
) {

    private val fileSystem = FileSystem.SYSTEM
    private val chapterCacheDirectory: Path = KoinJavaComponent.get(Path::class.java, named("bookCacheDir"))
    private val imageLoader = SingletonImageLoader.get(platformContext)

    actual suspend fun packageBookToZip(
        bookUrl: String,
        destinationZipPath: String,
        onlyCached: Boolean,
        concurrencyLimit: Int,
        delayMs: Long,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Boolean {
        val customDispatcher = Dispatchers.IO.limitedParallelism(concurrencyLimit)
        val zipFile = File(destinationZipPath)

        return withContext(Dispatchers.IO) {
            try {
                val localBook = bookDao.getBookByUrl(bookUrl) ?: return@withContext false
                val localChapters = chapterDao.getChaptersByBookUrl(bookUrl).map { it.toDomain() }

                val rule = ruleRepository.getEnabledRules().find { it.id == localBook.ruleId }
                val diskCache = imageLoader.diskCache
                if (rule == null || diskCache == null) return@withContext false

                val isMangaMode = true // 💡 TODO: 根据 rule.type == BookType.image 判定
                val totalChapters = localChapters.size

                coroutineScope {
                    ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->

                        val bookJsonStr = Json.encodeToString(localBook)
                        writeZipEntry(zipOut, "book.json", bookJsonStr.toByteArray(Charsets.UTF_8))

                        val catalogJsonStr = Json.encodeToString(localChapters)
                        writeZipEntry(zipOut, "catalog.json", catalogJsonStr.toByteArray(Charsets.UTF_8))

                        localChapters.forEachIndexed { chapterIndex, chapter ->
                            ensureActive()

                            try {
                                val safeFileName = "${chapter.url.toMd5()}.txt"
                                val cacheFile = chapterCacheDirectory / safeFileName

                                if (!onlyCached || fileSystem.exists(cacheFile)) {

                                    val block = if (fileSystem.exists(cacheFile)) {
                                        if (isMangaMode) {
                                            val jsonStr = fileSystem.read(cacheFile) { readUtf8() }
                                            val mangaImages = Json.decodeFromString<List<MangaImage>>(jsonStr)
                                            ChapterBlock(
                                                chapterUrl = chapter.url,
                                                chapterTitle = chapter.title,
                                                images = mangaImages,
                                                index = chapter.index
                                            )
                                        } else {
                                            val text = fileSystem.read(cacheFile) { readUtf8() }
                                            ChapterBlock(chapterUrl = chapter.url, chapterTitle = chapter.title, text = text, index = chapter.index)
                                        }
                                    } else {
                                        readerRepository.fetchData(chapter.url, rule.id, bookUrl)
                                    }

                                    if (isMangaMode) {
                                        // 💡 1. 通道传递【文件名】和【物理磁盘快照对象】，绝对不传巨大的 ByteArray 污染内存！
                                        val channel = Channel<Pair<String, DiskCache.Snapshot>>(capacity = 4)

                                        val downloadJobs = block.images.mapIndexed { imgIndex, mangaImage ->
                                            launch(customDispatcher) {
                                                if (delayMs > 0) delay(imgIndex * delayMs)
                                                val result = downloadImageAsync(rule, bookUrl, mangaImage, imgIndex, chapter.index)

                                                if (result != null) {
                                                    try {
                                                        channel.send(result)
                                                    } catch (e: Exception) {
                                                        // 💡 2. 极端防泄漏：如果此时消费者崩溃导致通道关闭，发送失败，必须手动释放刚刚拿到的快照句柄！
                                                        result.second.close()
                                                        throw e
                                                    }
                                                }
                                            }
                                        }

                                        launch {
                                            downloadJobs.joinAll()
                                            channel.close()
                                        }

                                        for ((fileName, snapshot) in channel) {
                                            try {
                                                val entry = ZipEntry(fileName)
                                                zipOut.putNextEntry(entry)

                                                // 💡 3. 内存零拷贝级极限写入（Stream to Stream）
                                                // 从磁盘读出一块，立刻写入 ZIP 一块。整个打包过程，内存波动不超过 8KB！
                                                fileSystem.read(snapshot.data) {
                                                    val inputStream = this.inputStream()
                                                    val buffer = ByteArray(8192) // 8KB 极速缓冲块
                                                    var bytesRead: Int
                                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                                        zipOut.write(buffer, 0, bytesRead)
                                                    }
                                                }
                                                zipOut.closeEntry()
                                            } finally {
                                                // 💡 4. 快照所有权转移：消费者在读取并写入 ZIP 完成后，负责安全释放文件锁！
                                                snapshot.close()
                                            }
                                        }
                                    } else {
                                        writeZipEntry(zipOut, "chapters/$safeFileName", block.text.toByteArray(Charsets.UTF_8))
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error { "第 ${chapterIndex + 1} 章 [${chapter.title}] 打包失败！原因: ${e.message}" }
                                e.printStackTrace()
                            } finally {
                                onProgress(chapterIndex + 1, totalChapters)
                            }
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                if (zipFile.exists()) zipFile.delete()
                throw e
            } catch (e: Exception) {
                logger.error { "书籍打包导出发生严重错误: ${e.message}" }
                e.printStackTrace()
                if (zipFile.exists()) zipFile.delete()
                false
            }
        }
    }

    /**
     * 💡 升级：只返回文件名和【物理磁盘快照】，不再读取字节到内存！
     */
    private suspend fun downloadImageAsync(
        rule: SourceRule,
        bookUrl: String,
        mangaImage: MangaImage,
        imgIndex: Int,
        chapterIndex: Int
    ): Pair<String, DiskCache.Snapshot>? { // 👈 泛型改为 Snapshot
        val diskCache = imageLoader.diskCache ?: return null
        val isDecryptImage = rule.content.decryptImage.isNotEmpty()

        var snapshot = diskCache.openSnapshot(mangaImage.url)

        if (snapshot == null) {
            try {
                // 💡 核心注入：最大重试 3 次，基础延迟 1000 毫秒
                retry(maxAttempts = 3, delayBaseMs = 1000L) {
                    val request = imageRequest(rule.id,bookUrl,mangaImage,isDecryptImage, platformContext)
                        .newBuilder()
                        .memoryCachePolicy(CachePolicy.DISABLED) // 导出不吃内存
                        .build()
                    // 执行挂起同步下载
                    val result = imageLoader.execute(request)
                    // 💡 如果 Coil 返回了 ErrorResult，手动抛出异常以激活 retry 的退避重试！
                    if (result is coil3.request.ErrorResult) {
                        throw result.throwable
                    }
                    // 执行到这里说明 result is SuccessResult
                }
                // 💡 重试管线安全结束（代表某一次重试成功了！），立刻去打开它的物理快照！
                snapshot = diskCache.openSnapshot(mangaImage.url)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        if (snapshot != null) {
            val ext = getExtension(mangaImage.url)
            val fileName = "chapters/chapter_${chapterIndex}/$imgIndex.$ext"

            // 💡 重点：把 snapshot 原封不动传出去，千万不要在这里调用 snapshot.close()！
            return fileName to snapshot
        }
        return null
    }

    private fun writeZipEntry(zipOut: ZipOutputStream, fileName: String, bytes: ByteArray) {
        val entry = ZipEntry(fileName)
        zipOut.putNextEntry(entry)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    private fun getExtension(url: String): String {
        val path = url.substringBefore("?").substringBefore("#")
        val ext = path.substringAfterLast(".", "jpg")
        return if (ext.length in 3..4) ext else "jpg"
    }
}