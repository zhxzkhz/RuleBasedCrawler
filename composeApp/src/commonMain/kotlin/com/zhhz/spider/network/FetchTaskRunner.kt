package com.zhhz.spider.network

import com.alibaba.fastjson2.JSON
import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.SessionEntity
import com.zhhz.spider.rule.*
import com.zhhz.spider.util.JsExtensionClass
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetSocketAddress
import java.net.Proxy
import javax.script.SimpleBindings

private val logger = KotlinLogging.logger {}

class FetchTaskRunner(
    private val httpFetcher: HttpFetcher, // 注入 HttpFetcher 实例
    private val dao: RuleDao
) {


    suspend fun fetchWithSession(source : SourceRule, page: IPage, input: String, ctx: VariableContext): String {

        // 1. 从数据库读取持久化的会话
        val session = dao.getSession(source.id)

        // 2. 检查是否逻辑失效 (根据时间)
        val isExpired = session != null && session.expireAt > 0 && System.currentTimeMillis() > session.expireAt

        logger.info { "Fetching ${source.id}: $session" }

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

                if (savedSession != null) {
                    ctx["token"] = savedSession.tokenValue
                } else {
                    // 4. 数据库也没有，说明从未登录或已被清除
                    // 这里可以报错提示用户，或者自动尝试执行一次 performLogin
                    logger.info { "凭证缺失或过期，尝试重新登录..." }
                    val newToken = performLogin(source, ctx) // 这里的逻辑见前一条回复
                    if (newToken.first.isBlank()){
                        return "ERROR: REQUIRE_LOGIN"
                    }
                    // 存入数据库持久化
                    dao.saveSession(SessionEntity(source.id, newToken.first, expireAt = -1))
                    // return "ERROR: REQUIRE_LOGIN"
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

        // 绝对化处理 (确保以 http 开头)
        val rawUrl = when {
            path.startsWith("http") -> path
            source.url.isBlank() -> path // 兜底：无 base 时原样返回
            else -> combineUrl(source.url, path)
        }

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


        // 2. 执行动态 Header 脚本
        val script = local.headerScript ?: global.headerScript
        if (!script.isNullOrBlank()) {

            var jsonResult: Any
            try {
                // 执行 JS，约定脚本返回一个 JSON 字符串，例如: '{"Token": "abc", "Time": "123"}'
                jsonResult = SCRIPT_ENGINE.eval(script, bindings)
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
                logger.error { "Header 脚本返回格式错误: $e" }
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

        // 5. 最终构造请求对象
        val req = FetchRequest(
            url = finalUrl,
            method = method.uppercase(),
            charset = charset,
            isCache = source.useCache,
            headers = finalHeaders,
            body = finalBody,
            isJson = isJson,
            proxy = proxy,
            customDns = source.customDns
        )
        bindings["java_request"] = req


        logger.info { "发起请求 -> [${page::class.simpleName}] URL: $finalUrl  POST_BODY: $finalBody" }
        var rawResponse = httpFetcher.fetch(source.id, source.concurrentRate, req)
        bindings["value"] = rawResponse

        val responseScript = page.config.responseScript ?: source.globalConfig.responseScript
        if (!responseScript.isNullOrBlank()) {
            // 调用 JS 引擎执行解密
            // 约定：输入变量名为 'raw'，输出为脚本最后一行或 return 值
            try {
                rawResponse = JsExtensionClass.jsToJavaObject(SCRIPT_ENGINE.eval(responseScript, bindings)).toString()
            } catch (e: Exception) {
                logger.error { e }
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
        var expiresStr = RuleParser.parseString(response, page.expiresSelector, ctx).toLong()

        //小于当前时间就是失败值或者固定值，加上当前时间戳
        if (expiresStr < now) {
            expiresStr = expiresStr + now
        }

        if (token.isNotBlank()) {
            // 3. 存入数据库持久化 (这里的 dao 建议通过构造函数注入)
            dao.saveSession(SessionEntity(source.id, token,expiresStr))
        }

        return token to response
    }

    fun combineUrl(base: String, relative: String): String {
        val b = base.trimEnd('/')
        val r = relative.trimStart('/')
        return "$b/$r"
    }

}

