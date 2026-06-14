package com.zhhz.spider.rule

//import com.sun.script.javascript.RhinoScriptEngineFactory
import com.alibaba.fastjson2.JSONPath
import com.zhhz.spider.ENGINE
import com.zhhz.spider.util.JsExtensionClass
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentHashMap
import javax.script.ScriptEngine
import javax.script.ScriptException
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

val SCRIPT_ENGINE: ScriptEngine = ENGINE

object RuleParser {

    private val regexPatternCache = ConcurrentHashMap<String, Pattern>()
    private val replaceRegexCache = ConcurrentHashMap<String, Regex>()

    /** 预期返回字符串 */
    fun parseString(input: Any?, selector: Selector, ctx: VariableContext): String {
        val res = parseInternal(input, selector, ctx)
        return if (res is List<*>) res.firstOrNull()?.toString() ?: "" else res.toString()
    }

    fun traceString(input: Any?, selector: Selector, ctx: VariableContext, selectorName: String): ParseTraceResult<String> {
        val events = mutableListOf<ParseTraceEvent>()
        val res = parseInternal(input, selector, ctx, selectorName, events)
        val value = if (res is List<*>) res.firstOrNull()?.toString() ?: "" else res.toString()
        return ParseTraceResult(value, events)
    }

    /** 预期返回列表 */
    fun parseList(input: Any?, selector: Selector, ctx: VariableContext): List<Any> {
        val res = parseInternal(input, selector, ctx)
        return when (res) {
            is List<*> -> res.filterNotNull()
            else -> if (res.toString().isBlank()) emptyList() else listOf(res)
        }
    }

    fun traceList(input: Any?, selector: Selector, ctx: VariableContext, selectorName: String): ParseTraceResult<List<Any>> {
        val events = mutableListOf<ParseTraceEvent>()
        val res = parseInternal(input, selector, ctx, selectorName, events)
        val value = when (res) {
            is List<*> -> res.filterNotNull()
            else -> if (res.toString().isBlank()) emptyList() else listOf(res)
        }
        return ParseTraceResult(value, events)
    }

    fun parseInternal(
        input: Any?,
        selector: Selector,
        ctx: VariableContext,
        selectorName: String = "selector",
        traceEvents: MutableList<ParseTraceEvent>? = null
    ): Any {

        // 关键：在循环开始前锁定“根数据”
        val rootData: Any = input ?: ""

        var dataList: List<Any> = if (input == null) emptyList() else listOf(input)
        if (selector.steps.isEmpty()) {return selector.defaultValue}
        logger.debug { "开始解析流程，初始列表大小: ${dataList.size}" }

        for ((index, step) in selector.steps.withIndex()) {
            if (dataList.isEmpty()) {
                logger.warn { "步骤 [${index + 1}] (${step.type}) 执行前列表已干涸，中断解析" }
                traceEvents?.add(
                    ParseTraceEvent(
                        selectorName = selectorName,
                        stepIndex = index + 1,
                        stepCount = selector.steps.size,
                        type = step.type,
                        rule = step.rule,
                        inputCount = 0,
                        outputCount = 0,
                        status = ParseTraceStatus.SKIPPED,
                        message = "上一步已无输出"
                    )
                )
                break
            }
            val inputCount = dataList.size
            dataList = try {
                processBatch(dataList, rootData, step, ctx)
            } catch (e: Exception) {
                traceEvents?.add(
                    ParseTraceEvent(
                        selectorName = selectorName,
                        stepIndex = index + 1,
                        stepCount = selector.steps.size,
                        type = step.type,
                        rule = step.rule,
                        inputCount = inputCount,
                        outputCount = 0,
                        status = ParseTraceStatus.ERROR,
                        message = e.message ?: e::class.simpleName ?: "未知错误"
                    )
                )
                logger.error(e) {
                    "规则解析步骤失败 step=${index + 1}/${selector.steps.size} type=${step.type} rule=${step.rule.truncate(120)} input=${rootData.toString().truncate(160)}"
                }
                throw e
            }
            traceEvents?.add(
                ParseTraceEvent(
                    selectorName = selectorName,
                    stepIndex = index + 1,
                    stepCount = selector.steps.size,
                    type = step.type,
                    rule = step.rule,
                    inputCount = inputCount,
                    outputCount = dataList.size,
                    status = if (dataList.isEmpty()) ParseTraceStatus.EMPTY else ParseTraceStatus.OK
                )
            )
            logger.debug { "步骤 [${index + 1}] (${step.type}) 执行完毕，执行代码：${step.rule.truncate(40)}，产出数量: ${dataList.size}" }
        }

        val finalResult = if (dataList.isEmpty()) null else if (dataList.size == 1) dataList[0] else dataList

        if (isEmpty(finalResult) && selector.fallback != null) {
            logger.info { "主规则解析无结果，触发备选规则 (Fallback)" }
            return parseInternal(input, selector.fallback, ctx, "$selectorName.fallback", traceEvents)
        }

        val result = finalResult ?: selector.defaultValue
        logger.debug { "解析最终完成，结果为空? ${finalResult == null}" }
        return result
    }

    private fun processBatch(inputs: List<Any>, root: Any, step: ParseStep, ctx: VariableContext): List<Any> {
        val output = mutableListOf<Any>()
        for (item in inputs) {
            val res = try {
                processSingle(item, root, step, ctx)
            } catch (e: Exception) {
                logger.error(e) {
                    "规则解析单项失败 type=${step.type} rule=${step.rule.truncate(120)} item=${item.toString().truncate(160)}"
                }
                throw e
            }
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
                val matcher = regexPatternCache.computeIfAbsent(step.rule) { Pattern.compile(it) }.matcher(asString)
                val results = generateSequence { if (matcher.find()) matcher.group(if (matcher.groupCount() > 0) 1 else 0) else null }.toList()
                if (step.isList) results else results.firstOrNull()
            }

            // 4. 文本变换
            StepType.REPLACE -> {
                val target = resolveTemplate(step.rule, "", ctx)
                val regex = replaceRegexCache.computeIfAbsent(target) { Regex(it) }
                asString.replace(regex, step.replacement.decodeEscapes())
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
                    JsExtensionClass.jsToJavaObject(JsEngineRunner.eval(step.rule, bindings))
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
                // 同时兼容旧的 {{rootcss:...}} 和新的 {{root.css:...}} 语法
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
        // 增强正则：支持 root.css:/root.xpath:/root.json:，并兼容历史 rootcss:/root$. 写法。
        // 组1: 是否有 root 标记
        // 组2: 协议 (css:|xpath:|$.|key)
        // 组3: 表达式
        val placeholderRegex = Regex("""\{\{\s*(?:(root)\.?)?(css:|xpath:|json:|\$\.|key)(.*?)\s*\}\}""")

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

            val rule = when {
                protocol == "$." -> "$protocol$expression"
                protocol == "json:" && expression.startsWith("$") -> expression
                else -> expression
            }

            evaluateQuery(
                item = targetSource,
                type = type,
                rule = rule
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
        val placeholderRegex = Regex("""\{\{\s*([A-Za-z_][\w.-]*|key)\s*\}\}""")
        return placeholderRegex.replace(template) { match ->
            val key = match.groupValues[1]
            when {
                key == "key" -> input
                key.startsWith("ctx.") -> ctx[key.removePrefix("ctx.")] ?: match.value
                else -> ctx[key] ?: match.value
            }
        }
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
