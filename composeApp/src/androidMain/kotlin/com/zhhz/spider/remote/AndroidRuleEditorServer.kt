package com.zhhz.spider.remote

import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.debug.RuleDebugRunner
import com.zhhz.spider.manager.ContextSessionManager
import com.zhhz.spider.network.FetchTaskRunner
import com.zhhz.spider.network.MangaCallFactory
import com.zhhz.spider.repository.SessionRepository
import com.zhhz.spider.repository.impl.decodeImageHeaders
import com.zhhz.spider.rule.RuleParser
import com.zhhz.spider.rule.toEntity
import com.zhhz.spider.util.DebugLogBuffer
import com.zhhz.spider.util.ImageFormatDetector
import com.zhhz.spider.util.JsExtensionClass
import com.zhhz.spider.util.descrambleAndEncode
import com.zhhz.spider.util.toCoilBitmap
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.ByteArrayInputStream
import javax.script.SimpleBindings

class AndroidRuleEditorServer(
    private val ruleDao: RuleDao,
    private val taskRunner: FetchTaskRunner,
    private val contextSessionManager: ContextSessionManager,
    private val sessionRepository: SessionRepository,
    private val mangaCallFactory: MangaCallFactory
) : NanoHTTPD("0.0.0.0", RULE_EDITOR_SERVER_PORT) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun serve(session: IHTTPSession): Response = try {
        if (session.uri != "/logs") {
            DebugLogBuffer.append("规则服务请求: ${session.method} ${session.uri}")
        }
        when (session.method to session.uri) {
            Method.GET to "/health" -> jsonResponse(RemoteHealthResponse())
            Method.GET to "/logs" -> jsonResponse(RemoteLogsResponse(DebugLogBuffer.lines.value))
            Method.POST to "/logs/clear" -> {
                DebugLogBuffer.clear()
                jsonResponse(RemoteLogsResponse(emptyList()))
            }
            Method.GET to "/rules" -> runBlocking {
                jsonResponse(ruleDao.getAllRulesFlow().first().map { it.toRemote() })
            }
            Method.POST to "/rules/save" -> handlePost<RemoteRuleRequest, RemoteOperationResponse>(session) { request ->
                val entity = request.rule.toEntity()
                ruleDao.saveRule(entity)
                DebugLogBuffer.append("规则已保存: ${entity.name.ifBlank { entity.id }}")
                RemoteOperationResponse(true, "规则已保存")
            }
            Method.POST to "/rules/delete" -> handlePost<RemoteDeleteRuleRequest, RemoteRule>(session) { request ->
                val entity = ruleDao.getRuleById(request.id) ?: error("规则不存在: ${request.id}")
                ruleDao.deleteRule(entity)
                entity.toRemote()
            }
            Method.POST to "/debug/local" -> handlePost<RemoteDebugRequest, RemoteLocalDebugResponse>(session) { request ->
                val context = request.context.toMutableMap()
                RemoteLocalDebugResponse(
                    RuleDebugRunner.runLocal(request.tabIndex, request.html, request.rule, context),
                    context
                )
            }
            Method.POST to "/debug/network" -> handlePost<RemoteDebugRequest, RemoteNetworkDebugResponse>(session) { request ->
                val context = request.context.toMutableMap()
                RemoteNetworkDebugResponse(
                    RuleDebugRunner.runNetwork(request.tabIndex, request.input, request.rule, context, taskRunner),
                    context
                )
            }
            Method.POST to "/debug/full-chain" -> handlePost<RemoteDebugRequest, RemoteFullChainDebugResponse>(session) { request ->
                val updates = mutableListOf<com.zhhz.spider.debug.DebugPageUpdate>()
                val context = contextSessionManager.getContext(request.rule.id).apply {
                    putAll(request.context)
                }
                val result = RuleDebugRunner.runFullChain(
                    input = request.input,
                    rule = request.rule,
                    initialContext = context,
                    taskRunner = taskRunner,
                    contextSessionManager = contextSessionManager,
                    sessionRepository = sessionRepository,
                    onUpdate = updates::add
                )
                RemoteFullChainDebugResponse(result, updates)
            }
            Method.POST to "/debug/image" -> handleImage(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    } catch (e: Exception) {
        DebugLogBuffer.append("规则服务异常: ${e.message}")
        jsonResponse(RemoteErrorResponse(e.message ?: "服务器内部错误"), Response.Status.INTERNAL_ERROR)
    }

    private inline fun <reified T, reified R> handlePost(
        session: IHTTPSession,
        crossinline block: suspend (T) -> R
    ): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val request = json.decodeFromString<T>(files["postData"].orEmpty())
        return runBlocking { jsonResponse(block(request)) }
    }

    private fun handleImage(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val request = json.decodeFromString<RemoteImageRequest>(files["postData"].orEmpty())
        val ctx = request.context.toMutableMap()
        val chapterUrl = ctx["chapterUrl"].orEmpty().ifBlank {
            ctx["bookUrl"].orEmpty().ifBlank { request.rule.id }
        }
        val headers = if (request.rule.content.imageHeaders.steps.isEmpty()) {
            emptyMap()
        } else {
            decodeImageHeaders(
                RuleParser.parseString(request.html, request.rule.content.imageHeaders, ctx),
                request.rule.name.ifBlank { request.rule.id },
                chapterUrl
            )
        }
        val okhttpRequest = Request.Builder().url(request.imageUrl).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val originalBytes = mangaCallFactory.newCall(okhttpRequest, request.rule, ctx).execute().use { response ->
            if (!response.isSuccessful) error("图片请求失败: HTTP ${response.code}")
            response.body.bytes()
        }
        val format = ImageFormatDetector.detectFormat(originalBytes)
        val outputBytes = if (request.rule.content.decryptImage.isBlank()) {
            originalBytes
        } else {
            val bitmap = originalBytes.toCoilBitmap()
            val bindings = SimpleBindings().apply {
                put("java", JsExtensionClass)
                put("java_ctx", ctx)
                put("java_url", request.imageUrl)
                put("bitmap", bitmap)
            }
            bitmap.descrambleAndEncode(request.rule.content.decryptImage, format, bindings) ?: originalBytes
        }
        val mimeType = when (format) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        DebugLogBuffer.append("Android 图片预览完成: ${request.imageUrl}")
        return newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            ByteArrayInputStream(outputBytes),
            outputBytes.size.toLong()
        )
    }

    private inline fun <reified T> jsonResponse(
        value: T,
        status: Response.IStatus = Response.Status.OK
    ): Response = newFixedLengthResponse(status, "application/json; charset=utf-8", json.encodeToString(value))

    private fun RuleEntity.toRemote() = RemoteRule(id, name, jsonContent, isEnabled, updateTime)
}
