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
import com.zhhz.spider.util.toMd5
import com.zhhz.spider.viewModel.ChapterBlock
import com.zhhz.spider.viewModel.MangaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
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

    // 💡 1. 统一命名：通过 fetchData 加载章节内容
    override suspend fun fetchData(chapterUrl: String, ruleId: String, bookUrl: String?): ChapterBlock {
        return withContext(Dispatchers.IO) {

            val rule = ruleRepository.getEnabledRules().find { it.id == ruleId }
                ?: throw Exception("找不到对应的书源规则")
            val isMangaMode = rule.type == BookType.image

            val targetBookUrl = bookUrl ?: sessionRepository.loadData()?.url ?: ""
            val ctx = contextSessionManager.getContext(targetBookUrl,ruleId)

            val safeFileName = "${"$ruleId|$targetBookUrl|$chapterUrl".toMd5()}.txt"
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
                    // 💡 小说离线：直接从物理文件读出纯文本展示！
                    ChapterBlock(chapterUrl = chapterUrl, chapterTitle = title, text = cachedData)
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
                        Json.decodeFromString<Map<String, String>>(RuleParser.parseString(data, rule.content.imageHeaders, ctx)).toMutableMap() // 💡 塞入自动生成的完美 Headers
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
                    val serializedJson = Json.encodeToString(imageUrls) // 序列化
                    fileSystem.write(cacheFile) {
                        writeUtf8(serializedJson) // 写入磁盘
                    }
                }

                ChapterBlock(
                    chapterUrl = chapterUrl,
                    chapterTitle = "", // 留给 ViewModel 去匹配真实的目录标题
                    isImageDecrypt = rule.content.decryptImage.isNotBlank(),
                    images = imageUrls
                )
            } else {
                //拼接List所有值
                val textContent = rule.content.getContent(data, ctx).joinToString("\n") {
                    it.toString()
                }.trimEnd()

                if (isBookInLibrary && textContent.isNotBlank()) {
                    fileSystem.write(cacheFile) {
                        writeUtf8(textContent) // 写入磁盘
                    }
                }

                ChapterBlock(
                    chapterUrl = chapterUrl,
                    chapterTitle = "",
                    text = textContent
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
            // 假设你的 BookDao 里有根据 url 查询书籍的方法
            val book = bookDao.getBookByUrl(bookUrl)

            if (book != null) {
                // 更新该书籍的最后阅读章节及时间
                val updatedBook = book.copy(
                    lastReadChapterUrl = chapterUrl ?: book.lastReadChapterUrl,
                    lastReadChapterTitle = chapterTitle,
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

    override suspend fun loadData(bookUrl: String): Book? {
        return withContext(Dispatchers.IO) {
            bookDao.getBookByUrl(bookUrl)?.toDomain()
        }
    }
}
