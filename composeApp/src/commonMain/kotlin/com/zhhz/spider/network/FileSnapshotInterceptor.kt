package com.zhhz.spider.network
import com.zhhz.spider.rule.TagAction
import com.zhhz.spider.util.toMd5
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File

class FileSnapshotInterceptor(private val cacheDir: File) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 从 Request 中提取指令
        val tagAction = request.tag(TagAction::class.java)

        // 如果没有指令，或者明确说不使用缓存，直接放行，完全不破坏原有逻辑
        if (tagAction == null || !tagAction.useCache) {
            return chain.proceed(request)
        }

        val cacheKey = request.generateCacheKey()
        val cacheFile = File(cacheDir, cacheKey)

        // 如果非强制刷新且文件存在，返回缓存
        if (cacheFile.exists()) {
            return Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("X-Snapshot", "true")
                .body(cacheFile.readBytes().toResponseBody(request.body?.contentType()))
                .build()
        }

        // 执行后续逻辑（包括 HttpFetcher 原有的频率限制和代理逻辑）
        val response = chain.proceed(request)

        if (response.isSuccessful) {
            val bytes = response.body.bytes()
            cacheFile.writeBytes(bytes)
            return response.newBuilder().body(bytes.toResponseBody(response.body.contentType())).build()
        }
        return response
    }
}

private fun Request.extractBody(): String {
    val body = this.body ?: return ""
    return try {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        // 读取内容但不消耗原始 body
        buffer.readUtf8()
    } catch (e: Exception) {
        ""
    }
}

fun Request.generateCacheKey(): String {
    val method = this.method
    val url = this.url.toString()
    val bodyContent = if (method == "POST") extractBody() else ""

    // 组合后哈希。包含 bodyContent 确保了 POST 请求的唯一性
    return "$method|$url|$bodyContent".toMd5()
}