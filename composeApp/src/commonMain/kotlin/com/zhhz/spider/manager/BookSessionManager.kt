package com.zhhz.spider.manager

import com.zhhz.spider.db.ChapterDao
import com.zhhz.spider.db.toDomain
import com.zhhz.spider.network.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 会话管理器：跨页面传递大批量数据（如目录）
 */
class BookSessionManager {
    // 内存中的临时目录缓存
    private var transientCatalog: List<Chapter> = emptyList()

    fun setTransientCatalog(list: List<Chapter>) {
        transientCatalog = list
    }

    fun getCatalog(bookUrl: String, dao: ChapterDao): Flow<List<Chapter>> = flow {
        // 1. 优先从内存里拿（刚从详情页点进来）
        if (transientCatalog.isNotEmpty()) {
            emit(transientCatalog)
            return@flow
        }

        // 2. 内存没有，再从数据库里拿（已经收藏的书，下次重新打开）
        // 这里假设你有一张 chapter_cache 表，或者从数据库恢复
        val saved = dao.getChapters(bookUrl).map {
            it.toDomain()
        }
        emit(saved)
    }
}