package com.zhhz.spider.rule

//import com.sun.script.javascript.RhinoScriptEngineFactory
import com.alibaba.fastjson2.JSONPath
import com.zhhz.spider.ENGINE
import com.zhhz.spider.util.JsExtensionClass
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import java.util.regex.Pattern
import javax.script.ScriptEngine
import javax.script.ScriptException
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

val SCRIPT_ENGINE: ScriptEngine = ENGINE

object RuleParser {

    /** 预期返回字符串 */
    fun parseString(input: Any?, selector: Selector, ctx: VariableContext): String {
        val res = parseInternal(input, selector, ctx)
        return if (res is List<*>) res.firstOrNull()?.toString() ?: "" else res.toString()
    }

    /** 预期返回列表 */
    fun parseList(input: Any?, selector: Selector, ctx: VariableContext): List<Any> {
        val res = parseInternal(input, selector, ctx)
        return when (res) {
            is List<*> -> res.filterNotNull()
            else -> if (res.toString().isBlank()) emptyList() else listOf(res)
        }
    }

    fun parseInternal(input: Any?, selector: Selector, ctx: VariableContext): Any {

        // 关键：在循环开始前锁定“根数据”
        val rootData: Any = input ?: ""

        var dataList: List<Any> = if (input == null) emptyList() else listOf(input)
        if (selector.steps.isEmpty()) {return selector.defaultValue}
        logger.debug { "开始解析流程，初始列表大小: ${dataList.size}" }

        for ((index, step) in selector.steps.withIndex()) {
            if (dataList.isEmpty()) {
                logger.warn { "步骤 [${index + 1}] (${step.type}) 执行前列表已干涸，中断解析" }
                break
            }
            dataList = processBatch(dataList,  rootData,step, ctx)
            logger.debug { "步骤 [${index + 1}] (${step.type}) 执行完毕，执行代码：${step.rule.truncate(40)}，产出数量: ${dataList.size}" }
        }

        val finalResult = if (dataList.isEmpty()) null else if (dataList.size == 1) dataList[0] else dataList

        if (isEmpty(finalResult) && selector.fallback != null) {
            logger.info { "主规则解析无结果，触发备选规则 (Fallback)" }
            return parseInternal(input, selector.fallback, ctx)
        }

        val result = finalResult ?: selector.defaultValue
        logger.debug { "解析最终完成，结果为空? ${finalResult == null}" }
        return result
    }

    private fun processBatch(inputs: List<Any>, root: Any, step: ParseStep, ctx: VariableContext): List<Any> {
        val output = mutableListOf<Any>()
        for (item in inputs) {
            val res = processSingle(item, root, step, ctx)
            if (res is List<*>) res.filterNotNull().forEach { output.add(it) }
            else if (res != null && res != "") output.add(res)
        }
        return output
    }

    fun processSingle(item: Any, root: Any, step: ParseStep, ctx: VariableContext): Any? {
        if (step.rule.isBlank()){
            return item
        }
        // 1. 定义延迟加载的转换器 (仅在需要时执行，且只执行一次)
        val asElement by lazy { item as? Element ?: Jsoup.parse(item.toString()) }
        val asString by lazy { if (item is Element) item.outerHtml() else item.toString() }

        return when (step.type) {
            // 2. 结构化查询：利用原子引擎处理列表或单项
            StepType.CSS, StepType.XPATH -> {
                val els = if (step.type == StepType.CSS) asElement.select(step.rule)
                else asElement.selectXpath(step.rule)
                els.mapNotNull { extractValue(it, step) }
            }

            // 3. 文本正则：利用 Sequence 优化列表处理
            StepType.REGEX -> {
                val matcher = Pattern.compile(step.rule).matcher(asString)
                val results = generateSequence { if (matcher.find()) matcher.group(if (matcher.groupCount() > 0) 1 else 0) else null }.toList()
                if (step.isList) results else results.firstOrNull()
            }

            // 4. 文本变换
            StepType.REPLACE -> {
                val target = resolveTemplate(step.rule, "", ctx)
                asString.replace(Regex(target), step.replacement.decodeEscapes())
            }

            // 5. 数据解析
            StepType.JSON -> try {
                JSONPath.extract(asString, step.rule)
            } catch (e: Exception) {
                logger.error { "JSON 解析失败: ${e.message}" }
                null
            }

            // 6. 基础类型
            StepType.CONSTANT -> step.rule.decodeEscapes()

            // 7. js执行
            StepType.SCRIPT -> {
                val bindings = SimpleBindings()
                bindings["java"] = JsExtensionClass
                bindings["java_log"] = logger
                bindings["java_ctx"] = ctx
                bindings["value"] = item
                bindings["data"] = item
                bindings["root"] = root  // 【新增】根数据，JS 里可以用 root.xxx 访问原始 HTML
                try {
                    JsExtensionClass.jsToJavaObject(SCRIPT_ENGINE.eval(step.rule, bindings))
                }  catch (e: ScriptException){
                    val errorDetail = """
                    JS执行失败！
                    执行代码: ${step.rule}
                    错误原因: ${e.message}
                    错误行号: ${e.lineNumber}
                    错误源码: ${e.columnNumber}
                    错误堆栈: ${e.stackTrace.joinToString("\n")}
                    """.lines().joinToString("\n") { it.trimStart() }
                    logger.error { errorDetail }
                    throw e
                }

            }

            // 8. 模板替换
            StepType.TEMPLATE -> {
                // 3. 再次通过 resolveTemplate 处理上下文中的 {{variable}}
                // 自动解析并填充：{{css:.class}}, {{xpath://a}}, {{$.json.path}}
                // 【核心优化】：支持 {{rootcss:...}} 或 {{root$.xxx}} 语法
                val dynamicTemplate = parseInternalQueries(step.rule, item, root)
                resolveTemplate(dynamicTemplate, item.toString(), ctx)
            }
        }
    }

    /**
     * 原子查询引擎：将原本散落在 processSingle 里的提取逻辑收口
     */
    private fun evaluateQuery(
        item: Any,
        type: StepType,
        rule: String,
        extractType: ExtractType = ExtractType.TEXT,
        attr: String? = null
    ): String {
        val asElement by lazy { item as? Element ?: Jsoup.parse(item.toString()) }
        val asString by lazy { if (item is Element) item.outerHtml() else item.toString() }

        return when (type) {
            StepType.CSS, StepType.XPATH -> {
                val el = if (type == StepType.CSS) asElement.select(rule).firstOrNull()
                else asElement.selectXpath(rule).firstOrNull()

                el?.let { extractValue(it, ParseStep(extractType = extractType, attr = attr)) }?.toString() ?: ""
            }
            StepType.JSON -> {
                // JSON 目前通常不支持 @ 提取属性，直接返回字符串
                try { JSONPath.extract(asString, rule)?.toString() ?: "" } catch (_: Exception) { "" }
            }
            else -> asString
        }
    }

    private fun parseInternalQueries(template: String, item: Any, root: Any): String {
        // 增强正则：支持 (root)?(协议)
        // 组1: 是否有 root 标记
        // 组2: 协议 (css:|xpath:|$.|key)
        // 组3: 表达式
        val placeholderRegex = Regex("""\{\{\s*(root)?(css:|xpath:|\$\.|key)(.*?)\s*\}\}""")

        return placeholderRegex.replace(template) { match ->
            val useRoot = match.groupValues[1] == "root"
            val protocol = match.groupValues[2]
            val expression = match.groupValues[3].trim()

            // 确定查询源：如果是 root 则用全量数据，否则用局部 item
            val targetSource = if (useRoot) root else item

            val type = when (protocol) {
                "css:" -> StepType.CSS
                "xpath:" -> StepType.XPATH
                "key" -> StepType.CONSTANT
                else -> StepType.JSON
            }

            evaluateQuery(
                item = targetSource,
                type = type,
                rule = if (protocol == "$.") "$protocol$expression" else expression
            )
        }
    }

    private fun extractValue(el: Element, step: ParseStep): Any {
        return when (step.extractType) {
            ExtractType.ELEMENT -> el // 【关键】直接返回 Element 对象，不转字符串
            ExtractType.TEXT -> el.text()
            ExtractType.OWN_TEXT -> el.ownText()
            ExtractType.INNER_HTML -> el.html()
            ExtractType.OUTER_HTML -> el.outerHtml()
            ExtractType.ATTR -> {
                val attrName = step.attr ?: ""
                val finalName = if (attrName.startsWith("@")) attrName.substring(1) else attrName
                el.attr(finalName)
            }
        }
    }

    private fun isEmpty(data: Any?): Boolean = data == null || data == "" || (data is List<*> && data.isEmpty())

    fun resolveTemplate(template: String, input: String, ctx: VariableContext): String {
        var res = template.replace("{{key}}", input)
        ctx.forEach { (k, v) -> res = res.replace("{{$k}}", v) }
        return res
    }

    fun String.decodeEscapes(): String {
        if (!contains("\\")) return this
        return this.replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    fun String.truncate(maxLength: Int, ellipsis: String = "..."): String {
        return if (this.length <= maxLength) {
            this
        } else {
            this.substring(0, maxLength) + ellipsis
        }
    }

}