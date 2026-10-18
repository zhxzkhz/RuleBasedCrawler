package com.zhhz.spider.manager

import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.repository.impl.decodeCachedNovelChapter
import com.zhhz.spider.viewModel.MangaImage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import org.koin.core.component.KoinComponent
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.get
import com.zhhz.spider.util.chapterCacheFileName

private val logger = KotlinLogging.logger {}

/**
 * 删除书本时的缓存清理器：
 * ① 取消该书进行中的下载任务
 * ② 遍历章节，清理正文文本缓存（小说正文 / 漫画图片清单）
 * ③ 漫画清单中的图片 URL 同步从 Coil 磁盘缓存移除
 *
 * 调用方在 cleanup 之后执行删书操作（章节目录由外键级联删除）。
 */
class BookCleanupManager(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val downloadManager: DownloadManager,
    private val platformContext: PlatformContext
) : KoinComponent {

    private val logger = KotlinLogging.logger {}

    private val textCacheDirectory: Path = get(Path::class.java, named("bookCacheDir"))
    private val fileSystem = FileSystem.SYSTEM
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val chapters: Int,
        val removedImages: Int,
        val removedCacheFiles: Int
    )

    suspend fun cleanup(bookUrl: String, clearTextCache: Boolean = true): Result = withContext(Dispatchers.IO) {
        // 1) 停掉该书的下载任务（取消而非暂停，不保留续传记录）
        runCatching { downloadManager.cancelDownload(bookUrl) }
            .onFailure { logger.warn(it) { "取消下载任务失败 bookUrl=$bookUrl" } }

        // 2) 必须在删书前取 ruleId（定位缓存文件名）与章节列表
        val ruleId = bookDao.getBookByUrl(bookUrl)?.ruleId.orEmpty()
        val chapters = chapterDao.getChaptersByBookUrl(bookUrl)

        var removedImages = 0
        var removedFiles = 0

        if (ruleId.isNotBlank()) {
            val diskCache = SingletonImageLoader.get(platformContext).diskCache

            for (chapter in chapters) {
                val safeFileName = chapterCacheFileName(ruleId, bookUrl, chapter.url)
                val cacheFile = textCacheDirectory / safeFileName
                if (!fileSystem.exists(cacheFile)) continue

                // 漫画清单 / 小说内联插图：解析出图片 URL 后从 Coil 磁盘缓存移除
                runCatching {
                    val content = fileSystem.read(cacheFile) { readUtf8() }
                    val trimmed = content.trimStart()
                    val images = when {
                        trimmed.startsWith("[") -> json.decodeFromString<List<MangaImage>>(content)
                        trimmed.startsWith("{") -> decodeCachedNovelChapter(content)?.inlineImages.orEmpty()
                        else -> emptyList()
                    }
                    images.forEach { img ->
                        val removed = diskCache?.remove(img.url)
                        if (removed == true) removedImages++
                    }
                }.onFailure { logger.warn(it) { "解析章节缓存清单失败 chapter=${chapter.url}" } }

                // 软移除（clearTextCache=false）时仅清图片缓存，保留清单/正文文件，
                // 这样重新阅读无需重新抓取目录解析结果
                if (clearTextCache) {
                    runCatching {
                        fileSystem.delete(cacheFile)
                        removedFiles++
                    }
                }
            }
        }

        Result(chapters = chapters.size, removedImages = removedImages, removedCacheFiles = removedFiles)
    }
}
