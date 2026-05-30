package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.db.ChapterEntity
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.network.Chapter
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.repository.CatalogRepository
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CatalogRepositoryImpl(
    private val chapterDao: ChapterDao,
    private val ruleRepository: RuleRepository,
    private val fetchTaskRunner: FetchTaskRunner, // 注入网络执行器
    private val sessionRepository: SessionRepository,
    private val contextSessionManager: ContextSessionManager
) : CatalogRepository {

    override suspend fun fetchData(catalogUrl: String, ruleId: String, bookUrl: String?): List<Chapter> {
        return withContext(Dispatchers.IO) {
            // 1. 0 毫秒极速从内存缓存中捞出已启用的规则
            val rule = ruleRepository.getEnabledRules().find { it.id == ruleId }
                ?: throw Exception("找不到对应的书源规则")
            // 如果获取失败就是使用ruleId获取
            val targetBookUrl = bookUrl ?: sessionRepository.loadData()?.url ?: ""
            val ctx = contextSessionManager.getContext(targetBookUrl,ruleId)

            // 2. 搬运原本 RuleApi 的网络抓取逻辑：获取目录页面的 HTML 源码
            val html = fetchTaskRunner.fetch(rule, rule.catalog, catalogUrl, ctx,ctx["bookDetailHtml"])

            // 3. 调用爬虫规则引擎：从 HTML 中提取出原始章节列表
            val rawChapters = rule.catalog.getChapters(html, ctx)

            // 4. 💡 核心映射：将爬虫规则抓出的原始章节，转换为 UI 认识的干净 ChapterBean 对象
            rawChapters.mapIndexed { index,chapter ->
                Chapter(
                    index = index,
                    title = rule.catalog.getChapterName(chapter, ctx).trim(),
                    url = rule.catalog.getChapterUrl(chapter, ctx).trim()
                )
            }
        }
    }

    override suspend fun loadData(bookUrl: String): List<Chapter> {
        return withContext(Dispatchers.IO) {
            // 假设你的 ChapterDao 中有根据 bookUrl 查找所有缓存章节的方法
            val localChapters = chapterDao.getChaptersByBookUrl(bookUrl)

            // 映射为统一的 ChapterBean 业务对象
            localChapters.map { entity ->
                entity.toDomain()
            }
        }
    }

    override suspend fun saveData(bookUrl: String, chapters: List<Chapter>) {
        withContext(Dispatchers.IO) {
            // 1. 毫秒级查出本地已有的章节数量 N
            val localCount = chapterDao.getChaptersCount(bookUrl)
            val fetchedCount = chapters.size

            when {
                // 💡 算法 A：网络章节数大于本地。说明有新章节，我们只增量追加本地没有的部分！
                fetchedCount > localCount -> {
                    // 截取第 N 到 M-1 章（只拿新出的章节）
                    val newChapters = chapters.subList(localCount, fetchedCount)

                    val entities = newChapters.mapIndexed { index, bean ->
                        ChapterEntity(
                            bookUrl = bookUrl,
                            title = bean.title,
                            url = bean.url,
                            // 索引接着本地的末尾往后排：N + 0, N + 1...
                            indexNum = localCount + index
                        )
                    }

                    // 💡 性能暴增：如果只更新了 2 章，这里就只执行 2 次插入，原本已有的 1000 章纹丝不动！
                    chapterDao.insertChapters(entities)
                    println("Incremental Sync: 本地已有 $localCount 章，网络更新为 $fetchedCount 章，已增量追加 ${newChapters.size} 个新章节")
                }

                // 💡 算法 B：网络和本地章节数完全一致。说明没更新，直接返回！
                // 💡 0次磁盘写入，完美保护手机闪存寿命！
                fetchedCount == localCount -> {
                    println("Incremental Sync: 目录无变动，无需写入数据库")
                }

                // 💡 算法 C：极罕见的异常情况：网络章节数居然小于本地（说明源站被删改或重组了）
                // 此时执行保底容错，清空本地，重新全量对齐
                else -> {
                    chapterDao.deleteChaptersByBookUrl(bookUrl) // 删
                    val entities = chapters.mapIndexed { index, bean ->
                        ChapterEntity(
                            bookUrl = bookUrl,
                            title = bean.title,
                            url = bean.url,
                            indexNum = index
                        )
                    }
                    chapterDao.insertChapters(entities) // 插
                    println("Incremental Sync: 警告！网络章节数变少，已执行全量重组覆盖。")
                }
            }
        }
    }

}