package com.zhhz.spider.repository.impl

import com.zhhz.spider.db.RuleDao
import com.zhhz.spider.db.RuleEntity
import com.zhhz.spider.network.HttpFetcher
import com.zhhz.spider.repository.RuleRepository
import com.zhhz.spider.repository.RuleSubscriptionResult
import com.zhhz.spider.rule.RuleParser // 注入你的解析器
import com.zhhz.spider.rule.SourceRule
import com.zhhz.spider.rule.toDomain
import com.zhhz.spider.util.toMd5
// import com.zhhz.spider.rule.SourceRule
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val logger = KotlinLogging.logger {}

class RuleRepositoryImpl(
    private val ruleDao: RuleDao,
    private val httpFetcher: HttpFetcher
) : RuleRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialLoadDone = false
    private val initialLoadLock = Any()

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
                            logger.error(e) { "规则解析失败 id=${entity.id} name=${entity.name}" }
                            null
                        }
                    }

                // 3. 存入已解析缓存
                _parsedRulesCache.value = parsedList
                // 首帧数据已到达，getEnabledRules() 之后不再需要同步兜底
                initialLoadDone = true
            }
        }
    }

    /**
     * 首次同步兜底装载。
     * 解析缓存由 init 里的 Flow 异步填充，createdAtStart() 只保证对象被创建、
     * 不保证首帧数据已到达；冷启动后立刻搜索会拿到空列表并抛「没有启用任何书源」。
     * 只在第一次访问时执行一次，之后走内存缓存。
     *
     * ⚠️ runBlocking 不指定调度器：协程直接跑在调用者线程的事件循环上，
     * 挂起点由 Room 内部自行调度。此前写法 runBlocking(Dispatchers.IO) 会
     * "占用调用者线程 + 再向 IO 池要一个线程"，调用者本身常在 IO/受限池上
     * （如 MangaCallFactory 的 limitedParallelism(2)），嵌套时存在线程池互饿风险；
     * DetailScreen 组合期调用 supportsComments() 时还会让主线程双重占线程。
     */
    private fun ensureInitialLoad() {
        if (initialLoadDone) return
        synchronized(initialLoadLock) {
            if (initialLoadDone) return
            runCatching { runBlocking { ruleDao.getAllRules() } }
                .onSuccess { entities ->
                    _rawRulesCache.value = entities
                    _parsedRulesCache.value = entities.filter { it.isEnabled }.mapNotNull { entity ->
                        runCatching { entity.toDomain() }
                            .onFailure { e -> logger.error(e) { "规则解析失败 id=${entity.id}" } }
                            .getOrNull()
                    }
                }
                .onFailure { e -> logger.error(e) { "规则缓存首次装载失败" } }
            initialLoadDone = true
        }
    }

    override fun loadData(): Flow<List<RuleEntity>> {
        return _rawRulesCache.asStateFlow()
    }

    override fun getEnabledRules(): List<SourceRule> {
        // 搜索时调用这里：0延迟、0解析消耗，直接拿到可用模型！
        // 首次调用做一次同步兜底装载，避免启动窗口内返回空列表
        ensureInitialLoad()
        return _parsedRulesCache.value
    }

    override suspend fun saveData(rule: RuleEntity) {
        withContext(Dispatchers.IO) { ruleDao.saveRule(rule) }
    }

    override suspend fun deleteData(rule: RuleEntity) {
        withContext(Dispatchers.IO) { ruleDao.deleteRule(rule) }
    }

    private val subscriptionJson = Json { ignoreUnknownKeys = true }

    // 订阅条目：id 允许缺失（由 name/内容哈希生成），name 缺失回退 id
    private data class RemoteRuleEntry(val id: String, val name: String, val json: String)

    private fun parseRuleSubscriptionEntries(body: String): List<RemoteRuleEntry> {
        val root = subscriptionJson.parseToJsonElement(body)
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["rules"]?.jsonArray ?: JsonArray(listOf(root))
            else -> error("无法识别的订阅格式")
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["ruleName"]?.jsonPrimitive?.contentOrNull
            val resolvedName = name ?: rawId ?: return@mapNotNull null
            val resolvedId = rawId ?: "sub_${resolvedName.toMd5()}"
            RemoteRuleEntry(resolvedId, resolvedName, obj.toString())
        }
    }

    override suspend fun syncRuleSubscription(url: String): RuleSubscriptionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = okhttp3.Request.Builder().url(url.trim()).build()
                httpFetcher.baseClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val bodyText = response.body?.string()?.trim().orEmpty()
                    check(bodyText.isNotEmpty()) { "订阅内容为空" }
                    parseRuleSubscriptionEntries(bodyText)
                }
            }.fold(
                onSuccess = { entries ->
                    var added = 0
                    var updated = 0
                    var failedRules = 0
                    entries.forEach { entry ->
                        try {
                            val existing = ruleDao.getRuleById(entry.id)
                            ruleDao.saveRule(
                                RuleEntity(
                                    id = entry.id,
                                    name = entry.name,
                                    jsonContent = entry.json,
                                    // 已存在的规则保留用户自己的启用/禁用状态
                                    isEnabled = existing?.isEnabled ?: true,
                                    updateTime = System.currentTimeMillis()
                                )
                            )
                            if (existing == null) added++ else updated++
                        } catch (_: Exception) {
                            failedRules++
                        }
                    }
                    RuleSubscriptionResult(added, updated, failedRules, null)
                },
                onFailure = { t ->
                    RuleSubscriptionResult(0, 0, 0, t.message ?: "同步失败")
                }
            )
        }
}
