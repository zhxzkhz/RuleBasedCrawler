package com.zhhz.spider.remote

import com.zhhz.spider.debug.DebugPageUpdate
import com.zhhz.spider.debug.FullChainDebugResult
import com.zhhz.spider.debug.LocalDebugResult
import com.zhhz.spider.debug.NetworkDebugResult
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.VariableContext
import kotlinx.serialization.Serializable

const val RULE_EDITOR_SERVER_PORT = 8765

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
