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
}