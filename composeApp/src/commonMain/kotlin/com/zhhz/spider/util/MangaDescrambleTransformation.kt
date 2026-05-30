package com.zhhz.spider.util

import coil3.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.manager.getActiveContext
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.rule.SCRIPT_ENGINE
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import javax.script.ScriptException
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

class MangaDescrambleTransformation(
    key: String,
    private val ruleId: String
) : Transformation(), KoinComponent {

    // 💡 核心：利用 Koin 在后台线程自动注入我们的规则仓库
    private val ruleRepository: RuleRepository by inject()
    private val sessionRepository: SessionRepository by inject()
    private val contextSessionManager: ContextSessionManager by inject()

    override val cacheKey: String = "descramble_${key}_${ruleId}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        //getEnabledRules() 查找操作在 0 毫秒内即可同步完成，没有任何性能损耗！
        // 如果找不到，说明规则被禁用了，直接原样返回
        val rule = ruleRepository.getEnabledRules().find { it.id == ruleId } ?: return input
        val bindings = SimpleBindings()
        val ctx = contextSessionManager.getActiveContext(sessionRepository,ruleId)
        bindings.put("java", JsExtensionClass)
        bindings.put("java_ctx", ctx)
        bindings.put("java_log", logger)
        bindings.put("java_url", cacheKey.split("_")[1])
        bindings.put("bitmap", input)
        bindings.put("cacheKey", cacheKey)
        var output: Bitmap = input

        if (rule.content.decryptImage.isNotBlank()) {
            try {
                output =
                    JsExtensionClass.jsToJavaObject(SCRIPT_ENGINE.eval(rule.content.decryptImage, bindings)) as Bitmap
            } catch (e: ScriptException) {
                val errorDetail = """
                JS执行失败！
                错误原因: ${e.message}
                错误行号: ${e.lineNumber}
                错误源码: ${e.columnNumber}
                错误堆栈: ${e.stackTrace.joinToString("\n")}
                """.trimIndent()
                logger.error { errorDetail }
            } catch (e: Exception) {
                // 捕获 Java 层的 NPE 或其他异常
                logger.error { e }
            }
        }

        return output
    }

}