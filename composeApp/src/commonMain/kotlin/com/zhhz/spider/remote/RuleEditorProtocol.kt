package com.zhhz.spider.remote

import com.zhhz.spider.debug.DebugPageUpdate
import com.zhhz.spider.debug.FullChainDebugResult
import com.zhhz.spider.debug.LocalDebugResult
import com.zhhz.spider.debug.NetworkDebugResult
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import kotlinx.serialization.Serializable

const val RULE_EDITOR_SERVER_PORT = 8765

/**
 * 局域网配对令牌：Android 端服务启动时随机生成并轮换，显示在 Android 端规则 IDE 左下角，
 * 桌面端连接时必须手动输入（X-Editor-Token 头）。除 /health 外所有端点都校验。
 * 放在 commonMain：Android 端展示、客户端携带都依赖它。
 */
object RuleEditorPairing {
    const val TOKEN_HEADER = "X-Editor-Token"

    @Volatile
    var token: String = generate()
        private set

    fun rotate() {
        token = generate()
    }

    private fun generate(): String {
        val rnd = java.security.SecureRandom()
        val bytes = ByteArray(9)
        rnd.nextBytes(bytes)
        // 9 字节 → 72 bit 熵，URL-safe Base64 无填充，局域网内不可枚举
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

@Serializable
data class RemoteHealthResponse(val name: String = "RuleBasedCrawler Android", val version: Int = 1)

@Serializable
data class RemoteRule(
    val id: String,
    val name: String,
    val jsonContent: String,
    val isEnabled: Boolean,
    val updateTime: Long
)

@Serializable
data class RemoteRuleRequest(val rule: SourceRule)

@Serializable
data class RemoteDeleteRuleRequest(val id: String)

@Serializable
data class RemoteOperationResponse(val success: Boolean, val message: String = "")

@Serializable
data class RemoteDebugRequest(
    val tabIndex: Int = 1,
    val input: String = "",
    val html: String = "",
    val rule: SourceRule,
    val context: Map<String, String> = emptyMap()
)

@Serializable
data class RemoteImageRequest(
    val imageUrl: String,
    val html: String,
    val rule: SourceRule,
    val context: Map<String, String> = emptyMap()
)

@Serializable
data class RemoteLocalDebugResponse(
    val result: LocalDebugResult,
    val context: Map<String, String>
)

@Serializable
data class RemoteNetworkDebugResponse(
    val result: NetworkDebugResult,
    val context: Map<String, String>
)

@Serializable
data class RemoteFullChainDebugResponse(
    val result: FullChainDebugResult,
    val updates: List<DebugPageUpdate>
)

@Serializable
data class RemoteErrorResponse(val message: String)

@Serializable
data class RemoteLogsResponse(val lines: List<String>)
