package com.zhhz.spider.rule

import com.zhhz.spider.ENGINE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.script.Bindings
import javax.script.ScriptEngine
import kotlin.concurrent.withLock

/**
 * JS 执行调度器。
 *
 * 💡 按来源拆分引擎与锁：
 * - 传入 lockKey（通常是 sourceId / ruleId）：该来源的所有脚本串行执行，
 *   但不同来源之间完全并行——慢的分类脚本不再拖垮其他书源的解析。
 * - 不传 key：走全局默认引擎（兼容旧调用与无来源上下文的场景）。
 *
 * ⚠️ 嵌套求值（如分类脚本内 java.processResponse 再执行 responseScript）
 * 使用同一个 key 时锁可重入，安全。
 */
object JsEngineRunner {

    private val defaultLock = ReentrantLock()
    private val defaultEngine: ScriptEngine = ENGINE

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val engines = ConcurrentHashMap<String, ScriptEngine>()

    private fun createEngine(): ScriptEngine = ENGINE

    fun eval(script: String, bindings: Bindings): Any? = eval(null, script, bindings)

    fun eval(lockKey: String?, script: String, bindings: Bindings): Any? {
        if (lockKey == null) {
            return defaultLock.withLock { defaultEngine.eval(script, bindings) }
        }
        val lock = locks.computeIfAbsent(lockKey) { ReentrantLock() }
        val engine = engines.computeIfAbsent(lockKey) { createEngine() }
        return lock.withLock { engine.eval(script, bindings) }
    }
}
