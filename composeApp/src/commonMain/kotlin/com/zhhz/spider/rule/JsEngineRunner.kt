package com.zhhz.spider.rule

import java.util.concurrent.locks.ReentrantLock
import javax.script.Bindings
import kotlin.concurrent.withLock

object JsEngineRunner {
    private val lock = ReentrantLock()

    fun eval(script: String, bindings: Bindings): Any? {
        return lock.withLock {
            SCRIPT_ENGINE.eval(script, bindings)
        }
    }
}
