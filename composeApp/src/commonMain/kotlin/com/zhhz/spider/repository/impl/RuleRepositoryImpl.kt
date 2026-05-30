package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.rule.RuleParser // 注入你的解析器
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.toDomain
// import com.zhhz.spider.rule.SourceRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

class RuleRepositoryImpl(
    private val ruleDao: RuleDao
) : RuleRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 缓存 1：原始数据（供 UI 管理页展示）
    private val _rawRulesCache = MutableStateFlow<List<RuleEntity>>(emptyList())
    // 缓存 2：解析后的业务模型（供爬虫引擎极速调用）
    private val _parsedRulesCache = MutableStateFlow<List<SourceRule>>(emptyList())

    init {
        repositoryScope.launch {
            ruleDao.getAllRulesFlow().collectLatest { entities ->
                // 1. 更新供 UI 用的原始数据
                _rawRulesCache.value = entities

                // 2. 提前在后台线程把启动的规则解析好
                val parsedList = entities
                    .filter { it.isEnabled } // 只解析启用的规则，节省资源
                    .mapNotNull { entity ->
                        try {
                            entity.toDomain()
                        } catch (e: Exception) {
                            // 解析失败的规则直接过滤掉，保证爬虫引擎拿到的绝对是健康的数据
                            e.printStackTrace()
                            null
                        }
                    }

                // 3. 存入已解析缓存
                _parsedRulesCache.value = parsedList
            }
        }
    }

    override fun loadData(): Flow<List<RuleEntity>> {
        return _rawRulesCache.asStateFlow()
    }

    override fun getEnabledRules(): List<SourceRule> {
        // 搜索时调用这里：0延迟、0解析消耗，直接拿到可用模型！
        return _parsedRulesCache.value
    }

    override suspend fun saveData(rule: RuleEntity) {
        withContext(Dispatchers.IO) { ruleDao.saveRule(rule) }
    }

    override suspend fun deleteData(rule: RuleEntity) {
        withContext(Dispatchers.IO) { ruleDao.deleteRule(rule) }
    }
}