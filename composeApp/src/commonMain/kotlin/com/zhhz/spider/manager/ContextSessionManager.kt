package com.zhhz.spider.manager

import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.VariableContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ContextSessionManager(private val maxEntries: Int = 50) {

    private val lock = Mutex()
    private val sessions = mutableMapOf<String, VariableContext>()
    private val keyOrder = mutableListOf<String>()

    /**
     * 获取或创建会话（返回的是内存实例引用，保证后续网络操作的 Cookie 实时同步）
     */
    suspend fun getContext(key: String,ruleId: String? = null): VariableContext = lock.withLock {
        var existingCtx = sessions[key]
        if (existingCtx == null && ruleId != null) {
            existingCtx = sessions[ruleId]
        }
        if (existingCtx != null) {
            keyOrder.remove(key)
            keyOrder.add(key)
            existingCtx
        } else {
            val newCtx: VariableContext = mutableMapOf()
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
        // 💡 幂等保护：如果目标书本的会话已经在缓存里了（说明之前在详情页已经派生过）
        // 直接拦截，拒绝重复克隆，完美保护现有的阅读会话状态！
        if (sessions.containsKey(bookUrl)) return
        val sourceCtx = sessions[fromKey]
        if (sourceCtx != null) {
            // 💡 执行深拷贝，生成全新的 Map 实例
            sessions[bookUrl] = sourceCtx.toMutableMap()
            // 更新 LRU 队列
            keyOrder.remove(bookUrl)
            keyOrder.add(bookUrl)
            pruneCacheIfNeeded()
            println("LRU Cache: 成功为书本 [${bookUrl.takeLast(10)}] 分支派生会话，继承自 [${fromKey}]")
        }
    }

    suspend fun clear(key: String) = lock.withLock {
        sessions.remove(key)
        keyOrder.remove(key)
    }

    // 内部修剪方法
    private fun pruneCacheIfNeeded() {
        if (sessions.size > maxEntries) {
            val oldestKey = keyOrder.removeAt(0)
            sessions.remove(oldestKey)
            println("LRU Cache: 容量满，自动淘汰最老数据 [$oldestKey]")
        }
    }
}

suspend fun ContextSessionManager.getActiveContext(sessionRepository: SessionRepository,ruleId: String): VariableContext {
    val currentBookUrl = sessionRepository.loadData()?.availableSources?.find { it.ruleId == ruleId } ?.url ?: ruleId
    return this.getContext(currentBookUrl)
}