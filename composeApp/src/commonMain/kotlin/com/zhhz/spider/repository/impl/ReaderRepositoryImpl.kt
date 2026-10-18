package com.zhhz.spider.repository.impl

import com.zhhz.spider.constant.BookType
import com.zhhz.spider.db.BookDao
import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.model.CrawlerStage
import com.zhhz.spider.network.Book
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.ReaderRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.util.chapterCacheFileName
import com.zhhz.spider.viewModel.ChapterBlock
import com.zhhz.spider.viewModel.MangaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import org.jsoup.Jsoup
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.get

class ReaderRepositoryImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,// 注入本地数据库，用于保存进度
    private val ruleRepository: RuleRepository, // 注入统一规则库，获取解析规则
    private val fetchTaskRunner: FetchTaskRunner, // 注入网络/JS解析引擎
    private val sessionRepository: SessionRepository,
    private val contextSessionManager: ContextSessionManager
) : ReaderRepository {

    // 💡 1. 声明 KMP 官方推荐的 Okio 文件系统
    private val fileSystem = FileSystem.SYSTEM

    private val textCacheDirectory: Path = get(Path::class.java, named("bookCacheDir"))

    /** 原子写入：先写临时文件再 rename，防止崩溃时产生损坏缓存 */
    private fun atomicWrite(target: Path, content: String) {
        val tmp = target.parent!! / "${target.name}.tmp"
        fileSystem.write(tmp) { writeUtf8(content) }
        fileSystem.atomicMove(tmp, target)
    }

    // 💡 1. 统一命名：通过 fetchData 加载章节内容
    override suspend fun fetchData(chapterUrl: String, ruleId: String, bookUrl: String?): ChapterBlock {
        return withContext(Dispatchers.IO) {

            val rule = ruleRepository.getEnabledRules().find { it.id == ruleId }
                ?: throw Exception("找不到对应的书源规则")
            val isMangaMode = rule.type == BookType.image

            val targetBookUrl = bookUrl ?: sessionRepository.loadData()?.url ?: ""
            val ctx = contextSessionManager.getContext(targetBookUrl,ruleId)

            val safeFileName = chapterCacheFileName(ruleId, targetBookUrl, chapterUrl)
            val cacheFile  = textCacheDirectory / safeFileName

            // 统一离线判定：只要磁盘上有这个文件，说明百分之百缓存成功了！
            if (fileSystem.exists(cacheFile)) {
                // 极速读取文件内容
                val cachedData = fileSystem.read(cacheFile) { readUtf8() }
                val localChapter = chapterDao.getChapterByUrl(targetBookUrl,chapterUrl)
                val title = localChapter?.title ?: "阅读"

                return@withContext if (isMangaMode) {
                    // 💡 漫画离线：从物理文件读出 JSON 字符串，反序列化还原为图片列表！
                    val cachedMangaImages = Json.decodeFromString<List<MangaImage>>(cachedData)
                    ChapterBlock(chapterUrl = chapterUrl, chapterTitle = title, images = cachedMangaImages, isImageDecrypt = rule.content.decryptImage.isNotBlank())
                } else {
                    // 新缓存会保留插图请求信息；旧版纯文本缓存仍可直接读取。
                    val cachedChapter = decodeCachedNovelChapter(cachedData)
                    ChapterBlock(
                        chapterUrl = chapterUrl,
                        chapterTitle = title,
                        text = cachedChapter?.text ?: cachedData,
                        inlineImages = cachedChapter?.inlineImages.orEmpty()
                    )
                }
            }

            val isBookInLibrary = bookDao.getBookByUrl(targetBookUrl) != null

            val data = fetchTaskRunner.fetch(rule, rule.content,chapterUrl , ctx)
            data.throwIfCrawlerError(rule, CrawlerStage.CONTENT)


            // 💡 纠正：直接根据类型返回对应的单一 ChapterBlock，不带任何包装
            if (isMangaMode) {
                val imageUrls = rule.content.getContent(data, ctx).map {
                    ctx["chapterUrl"] = chapterUrl
                    val headers = if (rule.content.imageHeaders.steps.isNotEmpty()){
                        decodeImageHeaders(
                            rawHeaders = RuleParser.parseString(data, rule.content.imageHeaders, ctx),
                            sourceName = rule.name.ifBlank { rule.id },
                            chapterUrl = chapterUrl
                        )
                    } else {
                        mutableMapOf()
                    }

                    MangaImage(
                        url = it.toString(),
                        headers = headers,
                        ruleId = rule.id
                    )
                }

                if (isBookInLibrary && imageUrls.isNotEmpty()) {
                    val serializedJson = Json.encodeToString(imageUrls)
                    atomicWrite(cacheFile, serializedJson)
                }

                ChapterBlock(
                    chapterUrl = chapterUrl,
                    chapterTitle = "", // 留给 ViewModel 去匹配真实的目录标题
                    isImageDecrypt = rule.content.decryptImage.isNotBlank(),
                    images = imageUrls
                )
            } else {
                ctx["chapterUrl"] = chapterUrl
                val rawContent = rule.content.getContent(data, ctx)
                val imageHeaders = if (
                    rawContent.any { containsNovelImageTag(it.toString()) || isStandaloneNovelImageUrl(it.toString()) } &&
                    rule.content.imageHeaders.steps.isNotEmpty()
                ) {
                    decodeImageHeaders(
                        rawHeaders = RuleParser.parseString(data, rule.content.imageHeaders, ctx),
                        sourceName = rule.name.ifBlank { rule.id },
                        chapterUrl = chapterUrl
                    )
                } else {
                    mutableMapOf()
                }
                val novelContent = extractNovelContent(rawContent, chapterUrl, imageHeaders, rule.id)

                if (isBookInLibrary && novelContent.text.isNotBlank()) {
                    val cacheData = if (novelContent.inlineImages.isEmpty()) {
                        novelContent.text
                    } else {
                        Json.encodeToString(CachedNovelChapter(novelContent.text, novelContent.inlineImages))
                    }
                    atomicWrite(cacheFile, cacheData)
                }

                ChapterBlock(
                    chapterUrl = chapterUrl,
                    chapterTitle = "",
                    text = novelContent.text,
                    inlineImages = novelContent.inlineImages
                )
            }
        }
    }

    // 💡 2. 统一命名：通过 saveData 自动保存用户的阅读进度到数据库
    override suspend fun saveData(
        bookUrl: String,
        chapterIndex: Int,
        pageIndex: Int,
        chapterTitle: String,
        chapterUrl: String?
    ) {
        withContext(Dispatchers.IO) {
            // 以实体为准回写阅读进度（含章节序号与页内进度）
            val book = bookDao.getBookByUrl(bookUrl)

            if (book != null) {
                // 更新该书籍的最后阅读章节及时间
                val updatedBook = book.copy(
                    lastReadChapterUrl = chapterUrl?.takeIf { it.isNotBlank() } ?: book.lastReadChapterUrl,
                    lastReadChapterTitle = chapterTitle.ifBlank { book.lastReadChapterTitle },
                    lastReadChapterIndex = chapterIndex, // ⚠️ 原实现忽略该参数，进度未真正落库
                    lastReadPageIndex = pageIndex,
                    updateTime = System.currentTimeMillis() // 记录最后阅读时间用于书架排序
                )

                // 存入数据库 (Room 的 @Insert(onConflict = OnConflictStrategy.REPLACE))
                bookDao.addToBookshelf(updatedBook)
            }
        }
    }

    override suspend fun saveBookReadProgress(bookUrl: String,
                                              chapterIndex: Int,
                                              pageIndex: Int,
                                              chapterTitle: String,
                                              chapterUrl: String?) {
        withContext(Dispatchers.IO) {
            bookDao.updateReadProgress(bookUrl, chapterIndex, pageIndex, chapterTitle, chapterUrl ?: "")
        }
    }

    override suspend fun markChapterRead(bookUrl: String, chapterUrl: String) {
        if (bookUrl.isBlank() || chapterUrl.isBlank()) return
        withContext(Dispatchers.IO) {
            bookDao.markAsRead(bookUrl, chapterUrl)
        }
    }

    override suspend fun loadData(bookUrl: String): Book? {
        return withContext(Dispatchers.IO) {
            bookDao.getBookByUrl(bookUrl)?.toDomain()
        }
    }
}

@Serializable
internal data class CachedNovelChapter(
    val text: String,
    val inlineImages: List<MangaImage>
)

internal data class ExtractedNovelContent(
    val text: String,
    val inlineImages: List<MangaImage>
)

private val novelImageTagRegex = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val novelImageAttributeRegex = Regex(
    """\b(data-src|data-original|data-lazy-src|data-echo|data-url|src)\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s\"'=<>`]+))""",
    RegexOption.IGNORE_CASE
)
private val standaloneNovelImageUrlRegex = Regex(
    """\.(?:avif|bmp|gif|jpe?g|png|webp)(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE
)

internal fun containsNovelImageTag(content: String): Boolean = novelImageTagRegex.containsMatchIn(content)

internal fun extractNovelContent(
    rawContent: List<*>,
    chapterUrl: String,
    imageHeaders: Map<String, String>,
    ruleId: String
): ExtractedNovelContent {
    val inlineImages = mutableListOf<MangaImage>()
    fun imageMarker(imageUrl: String): String {
        inlineImages += MangaImage(
            url = imageUrl,
            headers = imageHeaders.toMutableMap(),
            ruleId = ruleId
        )
        return "▩$imageUrl▩"
    }
    val text = rawContent.joinToString("\n") { value ->
        val content = value?.toString().orEmpty()
        if (isStandaloneNovelImageUrl(content)) {
            imageMarker(resolveNovelImageUrl(content, chapterUrl))
        } else {
            val contentWithMarkers = content.replace(novelImageTagRegex) { match ->
                extractNovelImageUrl(match.value, chapterUrl)?.let(::imageMarker).orEmpty()
            }
            if (containsNovelImageTag(content)) {
                Jsoup.parseBodyFragment(contentWithMarkers).text()
            } else {
                contentWithMarkers
            }
        }
    }.trimEnd()
    return ExtractedNovelContent(text, inlineImages)
}

private fun extractNovelImageUrl(imageTag: String, chapterUrl: String): String? {
    val attributes = novelImageAttributeRegex.findAll(imageTag).mapNotNull { match ->
        val name = match.groupValues[1].lowercase()
        val value = match.groupValues.drop(2).firstOrNull { it.isNotBlank() }?.trim()
        value?.takeIf { it.isNotBlank() }?.let { name to it }
    }.toList()
    val rawUrl = attributes.firstOrNull { it.first != "src" }?.second
        ?: attributes.firstOrNull { it.first == "src" }?.second
        ?: return null

    return resolveNovelImageUrl(rawUrl, chapterUrl)
}

private fun isStandaloneNovelImageUrl(content: String): Boolean {
    val candidate = content.trim()
    return candidate.startsWith("data:image/", ignoreCase = true) ||
        standaloneNovelImageUrlRegex.containsMatchIn(candidate)
}

private fun resolveNovelImageUrl(rawUrl: String, chapterUrl: String): String {
    return runCatching { java.net.URI(chapterUrl).resolve(rawUrl).toString() }
        .getOrDefault(rawUrl)
}

internal fun decodeCachedNovelChapter(cachedData: String): CachedNovelChapter? =
    runCatching { Json.decodeFromString<CachedNovelChapter>(cachedData) }.getOrNull()

internal fun decodeImageHeaders(
    rawHeaders: String,
    sourceName: String,
    chapterUrl: String
): MutableMap<String, String> {
    val normalizedHeaders = rawHeaders.trim()
    if (normalizedHeaders.isBlank()) {
        throw IllegalArgumentException("图片 Headers 解析失败：规则返回为空 source=$sourceName chapter=$chapterUrl")
    }

    return try {
        Json.decodeFromString<Map<String, String>>(normalizedHeaders).toMutableMap()
    } catch (e: IllegalArgumentException) {
        throw invalidImageHeadersException(sourceName, chapterUrl, normalizedHeaders, e)
    } catch (e: SerializationException) {
        throw invalidImageHeadersException(sourceName, chapterUrl, normalizedHeaders, e)
    }
}

private fun invalidImageHeadersException(
    sourceName: String,
    chapterUrl: String,
    rawHeaders: String,
    cause: Throwable
): IllegalArgumentException {
    val preview = rawHeaders.take(300)
    return IllegalArgumentException(
        "图片 Headers 解析失败：必须返回 JSON 对象且键和值都为字符串 source=$sourceName chapter=$chapterUrl raw=$preview",
        cause
    )
}
