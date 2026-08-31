package com.zhhz.spider.util

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = KotlinLogging.logger {}

/** 全局错误数据 */
data class AppError(
    val id: Long = System.nanoTime(),
    val message: String,
    val detail: String? = null
)

/**
 * 全局错误捕获器。
 *
 * install() 后：
 * - 未捕获的线程异常（含协程内未处理的异常）不再直接崩溃，
 *   而是被收集到 currentError，由 UI 层渲染成自定义错误提示框
 * - 业务代码也可主动调用 report() 上报错误
 *
 * 注意：Compose 组合期错误仍可能中断当前界面渲染，
 * 但进程不会退出，用户可关闭错误框返回可用界面。
 */
object GlobalErrorHandler {

    private val _currentError = MutableStateFlow<AppError?>(null)
    val currentError = _currentError.asStateFlow()

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logger.error(throwable) { "全局未捕获异常 thread=${thread.name}" }
            report(throwable)
            // 不再调用 previousHandler / System.exit，进程保持存活
        }
    }

    fun report(throwable: Throwable, message: String? = null) {
        _currentError.value = AppError(
            message = message
                ?: throwable.message?.takeIf { it.isNotBlank() }
                ?: throwable::class.simpleName
                ?: "未知错误",
            detail = throwable.stackTraceToString().take(4000)
        )
    }

    fun report(message: String) {
        _currentError.value = AppError(message = message)
    }

    fun dismiss() {
        _currentError.value = null
    }
}
