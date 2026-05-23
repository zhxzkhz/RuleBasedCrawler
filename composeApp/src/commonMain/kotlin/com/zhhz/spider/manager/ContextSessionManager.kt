package com.zhhz.spider.manager

import com.zhhz.spider.rule.VariableContext
import java.util.*

class ContextSessionManager(private val maxEntries: Int = 50) {

    // 1. 使用 LinkedHashMap 开启 accessOrder = true 模式实现 LRU 逻辑
    // 使用 Collections.synchronizedMap 保证线程安全
    private val sessions: MutableMap<String, VariableContext> = Collections.synchronizedMap(
        object : LinkedHashMap<String, VariableContext>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VariableContext>?): Boolean {
                // 当 Map 大小超过阈值时，自动移除最老（最近最少访问）的项目
                return size > maxEntries
            }
        }
    )

    /**
     * 存入或更新某本书的上下文快照
     */
    fun updateContext(bookUrl: String, ctx: VariableContext) {
        // 执行深度复制，防止原始 Map 变化影响缓存中的快照
        sessions[bookUrl] = ctx.toMutableMap()
        println("LRU Cache: 更新上下文 [${bookUrl.takeLast(10)}], 当前容量: ${sessions.size}/$maxEntries")
    }

    /**
     * 获取某本书的上下文。
     * 调用此方法会触发 LRU 排序，将该项目标记为“最近使用”。
     */
    fun getContext(bookUrl: String): VariableContext {
        val ctx = sessions[bookUrl]
        return ctx?.toMutableMap() // 返回副本，允许界面随意修改而不污染缓存
            ?: mutableMapOf()
    }

    /**
     * 手动清理
     */
    fun clear(bookUrl: String) {
        sessions.remove(bookUrl)
    }
}