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

    companion object {
        /** 快照键格式版本标记：键里加入规则指纹后，旧格式文件全部作废，一次性清理 */
        private const val FORMAT_MARKER = ".snapshot-v2"
    }

    private var formatUpgraded = false

    private fun upgradeSnapshotFormatOnce() {
        if (formatUpgraded) return
        synchronized(this) {
            if (formatUpgraded) return
            val marker = File(cacheDir, FORMAT_MARKER)
            if (!marker.exists()) {
                runCatching {
                    cacheDir.listFiles()?.forEach { it.delete() }
                    marker.createNewFile()
                }
            }
            formatUpgraded = true
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        upgradeSnapshotFormatOnce()
        val request = chain.request()

        // 从 Request 中提取指令
        val tagAction = request.tag(TagAction::class.java)

        // 如果没有指令，或者明确说不使用缓存，直接放行，完全不破坏原有逻辑
        if (tagAction == null || !tagAction.useCache) {
            return chain.proceed(request)
        }

        val cacheKey = request.generateCacheKey(tagAction.cacheSalt)
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
            // 原子写入：先写临时文件再 rename，防止崩溃时产生损坏缓存
            val tmpFile = File(cacheFile.parent, cacheFile.name + ".tmp")
            tmpFile.writeBytes(bytes)
            tmpFile.renameTo(cacheFile)
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

fun Request.generateCacheKey(cacheSalt: String = ""): String {
    val method = this.method
    val url = this.url.toString()
    val bodyContent = if (method == "POST") extractBody() else ""

    // 组合后哈希。包含 bodyContent 确保了 POST 请求的唯一性；
    // cacheSalt（规则指纹）保证规则修改后同一 URL 的旧快照自动失效
    return "$method|$url|$bodyContent|$cacheSalt".toMd5()
}