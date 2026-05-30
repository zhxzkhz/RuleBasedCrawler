package com.zhhz.spider.util

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.toBitmap
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.manager.imageRequest
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.viewModel.MangaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.toBufferedImage
import org.jetbrains.skiko.toImage
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// 💡 使用 actual 关键字，并补全 Koin 注入需要的构造参数
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class BookPackager(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val ruleRepository: RuleRepository, // 💡 注入规则仓库，判断是否是漫画
    private val platformContext: PlatformContext        // 💡 注入 Coil 引擎，用于捞取图片二进制
) {
    private val fileSystem = FileSystem.SYSTEM
    private val chapterCacheDirectory: Path = KoinJavaComponent.get(Path::class.java, named("bookCacheDir"))

    private val imageLoader = SingletonImageLoader.get(platformContext)

    // 💡 具体的打包压缩实现
    actual suspend fun packageBookToZip(bookUrl: String, destinationZipPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val localBook = bookDao.getBookByUrl(bookUrl) ?: return@withContext false
                val localChapters = chapterDao.getChaptersByBookUrl(bookUrl).map {
                    it.toDomain()
                }

                val rule = ruleRepository.getEnabledRules().find { it.id == localBook.ruleId }
                val diskCache = imageLoader.diskCache
                //如果获取规则失败就停止执行
                if (rule == null || diskCache == null) {
                    return@withContext false
                }

                // 💡 1. 判断这本书是不是漫画
                val isMangaMode = true //rule.type == BookType.image

                val zipFile = File(destinationZipPath)
                ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->

                    // A. 写入书架元数据
                    val bookJsonStr = Json.encodeToString(localBook)
                    writeZipEntry(zipOut, "book.json", bookJsonStr.toByteArray(Charsets.UTF_8))

                    // B. 写入目录结构
                    val catalogJsonStr = Json.encodeToString(localChapters)
                    writeZipEntry(zipOut, "catalog.json", catalogJsonStr.toByteArray(Charsets.UTF_8))

                    // C. 遍历章节并写入正文/物理图片
                    localChapters.forEach { chapter ->
                        val safeFileName = "${chapter.url.toMd5()}.txt"
                        val cacheFile = chapterCacheDirectory / safeFileName

                        if (fileSystem.exists(cacheFile)) {
                            if (isMangaMode) {
                                // 💡 2. 核心大杀器：如果是漫画，执行物理图片提取！
                                val jsonStr = fileSystem.read(cacheFile) { readUtf8() }
                                // 解析出该章节所有的图片模型
                                val mangaImages = Json.decodeFromString<List<MangaImage>>(jsonStr)

                                mangaImages.forEachIndexed { imgIndex, mangaImage ->
                                    val isDecryptImage = rule.content.decryptImage.isNotEmpty()

                                    // 1. 尝试直接从本地磁盘缓存打开快照
                                    var snapshot = diskCache.openSnapshot(mangaImage.url)

                                    // 💡 2. 核心重构：如果缓存没有，现场执行挂起式下载（execute）！
                                    // 这保证了下载过程是顺序、受控、单线程安全的，绝对不会发生并发写 ZIP 导致的损坏崩溃！
                                    if (snapshot == null) {
                                        try {
                                            val request = imageRequest(rule.id, bookUrl, mangaImage, isDecryptImage, platformContext)
                                                .newBuilder()
                                                .memoryCachePolicy(CachePolicy.DISABLED) // 导出不占内存
                                                .build()

                                            // 💡 挂起等待 Coil 自动下载并完成流解密写盘！
                                            val result = imageLoader.execute(request)

                                            if (result is coil3.request.SuccessResult) {
                                                // 重新打开刚刚下载、解密并写好盘的磁盘快照
                                                snapshot = diskCache.openSnapshot(mangaImage.url)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    // 💡 3. 统一的写入逻辑：无论原本就有缓存，还是刚才现场下好的，
                                    // 现在它们都统一在这一条安全的流水线上进行 ZIP 写入！
                                    if (snapshot != null) {
                                        try {
                                            // 读取图片的真实成品二进制文件
                                            val imageBytes = fileSystem.read(snapshot.data) {
                                                readByteArray()
                                            }

                                            // 自动获取图片后缀名（jpg/png）
                                            val ext = getExtension(mangaImage.url)

                                            // 💡 写入压缩包
                                            writeZipEntry(
                                                zipOut = zipOut,
                                                fileName = "chapters/chapter_${chapter.index}/$imgIndex.$ext",
                                                bytes = imageBytes
                                            )
                                        } finally {
                                            // 💡 4. 使用 try-finally 确保无论写包成功还是报错，
                                            // 快照都能百分之百被安全关闭，释放文件锁！
                                            snapshot.close()
                                        }
                                    }
                                }
                            } else {
                                // 小说：直接打包纯文本
                                val fileBytes = fileSystem.read(cacheFile) { readByteArray() }
                                writeZipEntry(zipOut, "chapters/$safeFileName", fileBytes)
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }


    private fun writeZipEntry(zipOut: ZipOutputStream, fileName: String, bytes: ByteArray) {
        val entry = ZipEntry(fileName)
        zipOut.putNextEntry(entry)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    // 💡 辅助工具：从 URL 中安全提取图片后缀名
    private fun getExtension(url: String): String {
        val path = url.substringBefore("?").substringBefore("#")
        val ext = path.substringAfterLast(".", "jpg")
        return if (ext.length in 3..4) ext else "jpg"
    }

    fun saveImageFromSkia(image: Image, targetPath: String) {
        // 1. 获取图片的数据 (压缩为 PNG 或 JPG)
        // makeEncoded(format) 会把 Image 转换为二进制流
        val data = image.encodeToData(EncodedImageFormat.PNG)

        // 2. 写入文件
        val file = File(targetPath)
        file.writeBytes(data!!.bytes)

    }

}