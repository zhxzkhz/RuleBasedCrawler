package com.zhhz.spider.manager

import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.toDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RuleManager(private val ruleDao: RuleDao) {
    // 1. 用一个普通的变量作为“缓存池”，StateFlow 只用来通知界面
    private var activeRule: SourceRule? = null

    // 2. 界面订阅用 Flow
    private val _currentRuleFlow = MutableStateFlow<SourceRule?>(null)
    val currentRule = _currentRuleFlow.asStateFlow()

    suspend fun getRule(ruleId: String): SourceRule {
        // 1. 直接对比变量，绝对稳健，没有异步延迟
        val memory = activeRule
        if (memory?.id == ruleId) {
            return memory
        }

        // 2. 数据库加载
        val dbRule = ruleDao.getRuleById(ruleId)?.toDomain() ?: throw Exception("Rule not found")

        // 3. 更新缓存和通知界面
        activeRule = dbRule
        _currentRuleFlow.value = dbRule
        return dbRule
    }

    fun updateActiveRule(rule: SourceRule) {
        activeRule = rule
        _currentRuleFlow.value = rule
    }
}