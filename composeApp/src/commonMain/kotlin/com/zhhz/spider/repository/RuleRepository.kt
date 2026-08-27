package com.zhhz.spider.repository

import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.rule.SourceRule
import kotlinx.coroutines.flow.Flow

interface RuleRepository {
    // 强制命名：供 UI 层（规则管理页）观察所有规则
    fun loadData(): Flow<List<RuleEntity>>

    // 核心新增：获取内存中所有已启用的规则（同步方法，无耗时）
    // 供 引擎层（Search/Detail）调用：直接返回解析好、已启用的业务模型
    fun getEnabledRules(): List<SourceRule>

    // 强制命名：保存/更新规则
    suspend fun saveData(rule: RuleEntity)

    // 强制命名：删除规则
    suspend fun deleteData(rule: RuleEntity)

    /**
     * 从订阅 URL 拉取规则合集并合并入库（按 id 去重，已存在的保留启用状态只更新内容）。
     * 支持三种格式：单个规则对象 / 规则数组 / {"rules": [...]}
     */
    suspend fun syncRuleSubscription(url: String): RuleSubscriptionResult
}

/** 订阅同步结果 */
data class RuleSubscriptionResult(
    val added: Int,
    val updated: Int,
    val failedRules: Int,
    val error: String? = null
) {
    val success: Boolean get() = error == null

    fun toMessage(): String = if (!success) {
        "同步失败：$error"
    } else {
        buildString {
            append("新增 $added 条")
            if (updated > 0) append("，更新 $updated 条")
            if (failedRules > 0) append("，失败 $failedRules 条")
        }
    }
}