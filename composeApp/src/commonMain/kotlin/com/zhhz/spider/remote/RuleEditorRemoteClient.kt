package com.zhhz.spider.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RuleEditorRemoteClient(address: String) {
    val baseUrl = address.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun health(): RemoteHealthResponse = get("/health")
    suspend fun rules(): List<RemoteRule> = get("/rules")
    suspend fun logs(): RemoteLogsResponse = get("/logs")
    suspend fun clearLogs(): RemoteLogsResponse = post("/logs/clear", Unit)
    suspend fun save(rule: RemoteRuleRequest): RemoteOperationResponse = post("/rules/save", rule)
    suspend fun delete(request: RemoteDeleteRuleRequest): RemoteRule = post("/rules/delete", request)
    suspend fun runLocal(request: RemoteDebugRequest): RemoteLocalDebugResponse = post("/debug/local", request)
    suspend fun runNetwork(request: RemoteDebugRequest): RemoteNetworkDebugResponse = post("/debug/network", request)
    suspend fun runFullChain(request: RemoteDebugRequest): RemoteFullChainDebugResponse = post("/debug/full-chain", request)
    suspend fun loadImage(request: RemoteImageRequest): ByteArray = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(baseUrl + "/debug/image")
            .post(json.encodeToString(request).toRequestBody(mediaType))
            .build()
        client.newCall(httpRequest).execute().use { response ->
            val bytes = response.body.bytes()
            if (!response.isSuccessful) error(bytes.decodeToString().ifBlank { "HTTP ${response.code}" })
            bytes
        }
    }

    private suspend inline fun <reified T> get(path: String): T = execute(
        Request.Builder().url(baseUrl + path).get().build()
    )

    private suspend inline fun <reified I, reified O> post(path: String, value: I): O = execute(
        Request.Builder()
            .url(baseUrl + path)
            .post(json.encodeToString(value).toRequestBody(mediaType))
            .build()
    )

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString<RemoteErrorResponse>(body).message }
                    .getOrDefault(body.ifBlank { "HTTP ${response.code}" })
                error(message)
            }
            json.decodeFromString<T>(body)
        }
    }
}
