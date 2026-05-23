package com.zhhz.spider.network

import okhttp3.Interceptor
import okhttp3.Response

class ForceCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // 强行覆盖服务器的缓存指令
        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Cache-Control")
            // 强行设置缓存有效期为 1 小时 (根据需求调整)
            .header("Cache-Control", "public, max-age=3600")
            .build()
    }
}