package com.zhhz.spider.manager

import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.VariableContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

private val logger = KotlinLogging.logger {}

class ContextSessionManager(private val maxEntries: Int = 50) {

    private val lock = Mutex()
    private val sessions = mutableMapOf<String, VariableContext>()
    private val keyOrder = mutableListOf<String>()

    /**
     * 获取或创建会话（返回的是内存实例引用，保证后续网络操作的 Cookie 实时同步）
     */
    suspend fun getContext(key: String,ruleId: String? = null): VariableContext = lock.withLock {
        val existingCtx = sessions[key]
        if (existingCtx != null) {
            keyOrder.remove(key)
            keyOrder.add(key)
            existingCtx
        } else {
            val sourceCtx = ruleId?.let { sessions[it] }
            // 使用线程安全 Map 包装，允许多协程并发读写（如 Cookie 同步）
            val newCtx: VariableContext = Collections.synchronizedMap(
                sourceCtx?.toMutableMap() ?: mutableMapOf()
            )
            sessions[key] = newCtx
            keyOrder.add(key)
            pruneCacheIfNeeded()
            newCtx
        }
    }

    /**
     * 💡 核心新增：派生/分支会话 (Fork/Clone)
     * 将“源会话（如搜索阶段的会话）”完整复制一份，生成一个独立的、专属于“目标书本”的新会话。
     * 这样既继承了搜索阶段的 Cookie，又保证了不同书籍之间会话的物理隔离！
     */
    suspend fun forkContext(fromKey: String, bookUrl: String) = lock.withLock {
        if (sessions.containsKey(bookUrl)) return@withLock
        val sourceCtx = sessions[fromKey]
        if (sourceCtx != null) {
            sessions[bookUrl] = Collections.synchronizedMap(sourceCtx.toMutableMap())
            keyOrder.remove(bookUrl)
            keyOrder.add(bookUrl)
            pruneCacheIfNeeded()
            logger.debug { "LRU Cache: 成功为书本 [${bookUrl.takeLast(10)}] 分支派生会话，继承自 [$fromKey]" }
        }
    }

    @Suppress("unused")
    suspend fun clear(key: String) = lock.withLock {
        sessions.remove(key)
        keyOrder.remove(key)
    }

    // 内部修剪方法
    private fun pruneCacheIfNeeded() {
        if (sessions.size > maxEntries) {
            val oldestKey = keyOrder.removeAt(0)
            sessions.remove(oldestKey)
            logger.debug { "LRU Cache: 容量满，自动淘汰最老数据 [$oldestKey]" }
        }
    }
}

suspend fun ContextSessionManager.getActiveContext(sessionRepository: SessionRepository,ruleId: String): VariableContext {
    val currentBook = sessionRepository.loadData()
    val currentBookUrl = currentBook?.availableSources
        ?.find { it.ruleId == ruleId }
        ?.url
        ?: currentBook?.url
        ?: ruleId
    return this.getContext(currentBookUrl)
}
