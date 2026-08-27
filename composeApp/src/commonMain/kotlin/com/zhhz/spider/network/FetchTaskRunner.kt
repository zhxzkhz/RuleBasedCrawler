package com.zhhz.spider.network

import com.alibaba.fastjson2.JSON
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.SessionEntity
import com.zhhz.spider.rule.*
import com.zhhz.spider.util.JsExtensionClass
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

class FetchTaskRunner(
    private val httpFetcher: HttpFetcher, // 注入 HttpFetcher 实例
    private val dao: RuleDao
) {


    /**
     * 💡 为 JS 脚本层创建动态 Header 签名器：
     * 与 buildRequest 相同的逻辑——先模板渲染全局静态头，再执行 headerScript 得到签名头。
     * 供 ExploreTabResolver 等场景挂载到 JsExtensionClass，
     * 使脚本内 java.ajax(url) 自动携带与管线一致的动态签名。
     */
    fun createJsHeaderSigner(source: SourceRule, ctx: VariableContext): (String) -> Map<String, String> {
        return { url ->
            val result = LinkedHashMap<String, String>()
            // 1. 全局静态头（值支持 {{var}} 模板）
            source.globalConfig.headers.forEach { (k, v) ->
                result[k] = RuleParser.resolveTemplate(v, url, ctx)
            }
            // 2. 执行全局 headerScript（返回 JSON 字符串形式的头集合）
            val script = source.globalConfig.headerScript
            if (!script.isNullOrBlank()) {
                try {
                    val bindings = SimpleBindings()
                    bindings["java"] = JsExtensionClass
                    bindings["java_url"] = url
                    bindings["java_source"] = source
                    bindings["java_ctx"] = ctx
                    bindings["java_log"] = logger
                    bindings["value"] = url

                    val jsonResult = JsEngineRunner.eval(source.id, script, bindings)
                    JSON.parseObject(jsonResult.toString(), Map::class.java).let { dynamic ->
                        dynamic.forEach { (k, v) ->
                            if (k != null && v != null) result[k.toString()] = v.toString()
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "JS ajax 签名头生成失败 source=${source.name.ifBlank { source.id }} id=${source.id} url=$url" }
                }
            }
            result
        }
    }

    /**
     * 💡 为 JS 脚本层创建响应处理器：与管线 responseScript 逻辑一致
     * （bindings.value=原始响应 → 执行脚本 → jsToJavaObject 后转字符串）。
     * 供脚本内 java.processResponse(raw[, url]) 调用；绑定与管线对齐，
     * 包含 java_request / java_source 等，responseScript 内可正常引用。
     */
    fun createJsResponseProcessor(
        source: SourceRule,
        ctx: VariableContext,
        headerProvider: ((String) -> Map<String, String>)? = null
    ): (String, String, Map<String, String>?) -> String {
        return { raw, requestUrl, sentHeaders ->
            val script = source.globalConfig.responseScript
            if (script.isNullOrBlank()) {
                raw
            } else {
                try {
                    val effectiveUrl = requestUrl.ifBlank { ctx["bookUrl"].orEmpty().ifBlank { source.url } }
                    val bindings = SimpleBindings()
                    bindings["java"] = JsExtensionClass
                    bindings["java_url"] = effectiveUrl
                    bindings["java_source"] = source
                    bindings["java_ctx"] = ctx
                    bindings["java_log"] = logger
                    bindings["value"] = raw
                    bindings["data"] = raw

                    // 💡 java_request.headers 优先使用发送时快照（与请求严格配对），
                    // 其次签名器重新生成，最后退回全局静态头
                    val actualHeaders = sentHeaders
                        ?.takeIf { it.isNotEmpty() }
                        ?: runCatching { headerProvider?.invoke(effectiveUrl) }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?: source.globalConfig.headers
                    bindings["java_request"] = com.zhhz.spider.network.FetchRequest(
                        url = effectiveUrl,
                        keyword = ctx["keyword"].orEmpty(),
                        headers = actualHeaders
                    )

                    JsExtensionClass.jsToJavaObject(JsEngineRunner.eval(source.id, script, bindings)).toString()
                } catch (e: Exception) {
                    logger.error(e) { "JS 响应脚本执行失败 source=${source.name.ifBlank { source.id }} id=${source.id}" }
                    raw
                }
            }
        }
    }

    suspend fun fetchWithSession(source : SourceRule, page: IPage, input: String, ctx: VariableContext): String {
        // 1. 从数据库读取持久化的会话
        val session = dao.getSession(source.id)

        // 2. 检查是否逻辑失效 (根据时间)
        val isExpired = session != null && session.expireAt > 0 && System.currentTimeMillis() > session.expireAt

        // 💡 只记录会话状态，不输出 token 明文（避免日志泄漏凭证）
        logger.info { "Session 检查 source=${source.id} 存在=${session != null} 已过期=$isExpired" }

        if (session == null || isExpired) {
            // 3. 如果没登录或已过期，触发自动化登录逻辑
            logger.info { "凭证缺失或过期，尝试重新登录..." }
            val response = performLogin(source, ctx) // 这里的逻辑见前一条回复
            if (response.first.isNotBlank()) {
                ctx["token"] = response.first
            }
            return response.second
        } else {
            return "缓存session：" + session.tokenValue
        }

    }

    /**
     * 核心抓取调度方法
     * @param source 整个规则源对象（提供 ID, 频率限制, 全局配置, 代理）
     * @param page 当前操作的页面对象 (SearchPage/DetailPage/ContentPage)
     * @param input 关键变量：搜索页为“关键字”，其他页为“目标 URL”
     * @param ctx 变量上下文
     */
    suspend fun fetch(source: SourceRule, page: IPage, input: String, ctx: VariableContext , cacheHtml: String? = null): String {

        if (page.urlSelector.steps.isEmpty() && cacheHtml != null && input.isBlank()) {
            logger.info { "检测到无 URL 规则且存在缓存 HTML，直接透传" }
            return cacheHtml
        }

        // 1. 只有声明了“需要登录”，才去碰 Session 逻辑
        if (source.requireLogin && page !is LoginPage) {
            // 2. 检查内存中是否有 Token
            if (!ctx.containsKey("token")) {
                // 3. 内存没有，去数据库捞
                val savedSession = dao.getSession(source.id)
                val isSavedSessionExpired = savedSession != null &&
                        savedSession.expireAt > 0 &&
                        System.currentTimeMillis() > savedSession.expireAt

                if (savedSession != null && !isSavedSessionExpired) {
                    ctx["token"] = savedSession.tokenValue
                } else {
                    // 4. 数据库也没有，说明从未登录或已被清除
                    // 这里可以报错提示用户，或者自动尝试执行一次 performLogin
                    logger.info { "凭证缺失或过期，尝试重新登录 source=${source.name.ifBlank { source.id }} id=${source.id}" }
                    val newToken = performLogin(source, ctx) // 这里的逻辑见前一条回复
                    if (newToken.first.isBlank()){
                        logger.error { "登录失败，未获取到 token source=${source.name.ifBlank { source.id }} id=${source.id}" }
                        return "ERROR: REQUIRE_LOGIN"
                    }
                    ctx["token"] = newToken.first
                }
            }
        }

        val global = source.globalConfig
        val local = page.config

        // 1. 合并基础配置 (takeIf 处理空字符串，确保继承全局)
        val method = local.method?.takeIf { it.isNotBlank() } ?: global.method?.takeIf { it.isNotBlank() } ?: "GET"
        val charset = local.charset?.takeIf { it.isNotBlank() } ?: global.charset?.takeIf { it.isNotBlank() } ?: "UTF-8"
        val isJson = local.isJson ?: global.isJson ?: false
        val headers = global.headers + local.headers

        // 计算目标 URL
        // 优先级 1: 如果 input 是http开头 就是目标URL
        // 优先级 2: 否则，直接认为 input 就是目标 URL（常见于从列表点进详情）
        // 获取基础路径 (优先执行 Selector，若无则使用 input)
        val path = if (page.urlSelector.steps.isNotEmpty()) {
            RuleParser.parseString(input, page.urlSelector, ctx)
        } else {
            input
        }

        // 绝对化处理：支持 /path、../path、?query、//cdn.example.com 这类常见相对地址
        val rawUrl = combineUrl(source.url, path)

        // 3. 执行模板渲染 (把 {{key}} 或 {{variable}} 替换掉)
        // 注意：如果 rawUrl == input，且 input 里没括号，resolveTemplate 会原样返回，安全！
        val finalUrl = RuleParser.resolveTemplate(rawUrl, input, ctx)
        val finalBody = (local.bodyPayload ?: global.bodyPayload)?.let {
            RuleParser.resolveTemplate(it, input, ctx)
        }
        val finalHeaders = headers.mapValues { RuleParser.resolveTemplate(it.value, input, ctx) }.toMutableMap()

        val bindings = SimpleBindings()
        bindings["java"] = JsExtensionClass
        bindings["java_url"] = finalUrl
        bindings["java_source"] = source
        bindings["java_page"] = page
        bindings["java_ctx"] = ctx
        bindings["java_log"] = logger
        bindings["value"] = input

        // 💡 预绑定请求信息（签名头尚未合并），headerScript 里可读取 url/method/headers 等；
        // 脚本执行完成后会用带签名的最终请求对象覆盖此绑定，供 responseScript 使用
        val preRequest = FetchRequest(
            url = finalUrl,
            keyword = input,
            method = method.uppercase(),
            charset = charset,
            isCache = source.useCache,
            headers = finalHeaders,
            body = finalBody,
            isJson = isJson,
            proxy = null,
            customDns = source.customDns
        )
        bindings["java_request"] = preRequest


        // 2. 执行动态 Header 脚本
        val script = local.headerScript ?: global.headerScript
        if (!script.isNullOrBlank()) {

            var jsonResult: Any?
            try {
                // 执行 JS，约定脚本返回一个 JSON 字符串，例如: '{"Token": "abc", "Time": "123"}'
                jsonResult = JsEngineRunner.eval(source.id, script, bindings)
                logger.debug { "JSON RESULT: $jsonResult" }
                // 解析并合并到请求头中
                val dynamicHeaders = JSON.parseObject(jsonResult.toString(), Map::class.java) as Map<*, *>
                // 2. 安全地合并到 finalHeaders
                dynamicHeaders.forEach { (k, v) ->
                    if (k != null && v != null) {
                        // 强制转为 String，确保符合 MutableMap<String, String> 的要求
                        finalHeaders[k.toString()] = v.toString()
                    }
                }
            } catch (e: Exception) {
                logger.error(e) {
                    "Header 脚本执行失败 source=${source.name.ifBlank { source.id }} id=${source.id} page=${page.pageName()} url=$finalUrl"
                }
            }
        }

        // 4. 转换代理格式
        val proxy = source.proxyUrl?.takeIf { it.contains(":") }?.let {
            try {
                val parts = it.split(":")
                Proxy(Proxy.Type.HTTP, InetSocketAddress(parts[0], parts[1].toInt()))
            } catch (e: Exception) {
                logger.error { "代理格式错误: $it" }
                null
            }
        }

        // 5. 最终构造请求对象（合并签名头后的版本，覆盖预绑定供后续脚本使用）
        val req = preRequest.copy(
            headers = finalHeaders,
            proxy = proxy
        )
        bindings["java_request"] = req


        logger.info { "发起请求 source=${source.name.ifBlank { source.id }} id=${source.id} page=${page.pageName()} url=$finalUrl method=$method" }
        var rawResponse = httpFetcher.fetch(source.id, source.concurrentRate, req)
        if (rawResponse.startsWith("ERROR:")) {
            logger.error {
                "请求失败 source=${source.name.ifBlank { source.id }} id=${source.id} page=${page.pageName()} url=$finalUrl error=$rawResponse"
            }
        }
        bindings["value"] = rawResponse

        val responseScript = page.config.responseScript ?: source.globalConfig.responseScript
        if (!responseScript.isNullOrBlank()) {
            // 调用 JS 引擎执行解密
            // 约定：输入变量名为 'raw'，输出为脚本最后一行或 return 值
            try {
                rawResponse = JsExtensionClass.jsToJavaObject(JsEngineRunner.eval(source.id, responseScript, bindings)).toString()
            } catch (e: Exception) {
                logger.error(e) {
                    "响应脚本执行失败 source=${source.name.ifBlank { source.id }} id=${source.id} page=${page.pageName()} url=$finalUrl"
                }
            }

        }
        return rawResponse
    }

    suspend fun performLogin(source: SourceRule, ctx: VariableContext): Pair<String, String> {
        val page = source.login
        if (page.urlSelector.steps.isEmpty() && page.config.bodyPayload.isNullOrBlank()) {
            return "" to ""// 未配置登录则跳过
        }

        // 1. 直接复用现有的 fetch 逻辑获取响应 (input 此时可以是空的，或者传入账号)
        val response = fetch(source, page, "", ctx)

        // 2. 提取 Token
        val token = RuleParser.parseString(response, page.tokenSelector, ctx)


        // 2. 确定失效时间
        val now = System.currentTimeMillis()
        var expiresStr = RuleParser.parseString(response, page.expiresSelector, ctx).toLongOrNull() ?: -1L

        //小于当前时间就是失败值或者固定值，加上当前时间戳
        if (expiresStr in 1 until now) {
            expiresStr = expiresStr + now
        }

        if (token.isNotBlank()) {
            // 3. 存入数据库持久化 (这里的 dao 建议通过构造函数注入)
            dao.saveSession(SessionEntity(source.id, token,expiresStr))
        }

        return token to response
    }

    fun combineUrl(base: String, relative: String): String {
        val path = relative.trim()
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        if (path.startsWith("//")) {
            val scheme = base.toHttpUrlOrNull()?.scheme ?: "https"
            return "$scheme:$path"
        }
        if (base.isBlank()) {
            return path
        }

        val resolvedUrl = base.toHttpUrlOrNull()?.resolve(path)
        if (resolvedUrl != null) {
            return resolvedUrl.toString()
        }

        val b = base.trimEnd('/')
        val r = path.trimStart('/')
        return "$b/$r"
    }

}

private fun IPage.pageName(): String = this::class.simpleName ?: this::class.toString()
