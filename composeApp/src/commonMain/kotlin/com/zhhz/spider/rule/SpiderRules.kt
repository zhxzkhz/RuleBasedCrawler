package com.zhhz.spider.rule

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONReader
import com.alibaba.fastjson2.annotation.JSONField
import com.alibaba.fastjson2.annotation.JSONType
import com.zhhz.spider.db.RuleEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.time.Clock

// 变量上下文：跨页面记忆的“记事本”
typealias VariableContext = MutableMap<String, String>

/**
 * 步骤类型定义
 */
enum class StepType {
    CSS, XPATH, REGEX, JSON, TEMPLATE, SCRIPT, CONSTANT, REPLACE
}

/**
 * 基础解析原子单位：步骤
 */
@Serializable
data class ParseStep(
    val type: StepType = StepType.CSS,
    val rule: String = "",
    val extractType: ExtractType = ExtractType.TEXT, // 默认提取文本
    val attr: String? = null,                       // 仅在 ATTR 类型下有效
    val replacement: String = "",       // REPLACE 替换值
    var isList: Boolean = false         // 是否产生列表
)

enum class ExtractType {
    ELEMENT,       // 保持为 Jsoup Element 对象 (用于中间步骤流转)
    TEXT,          // 纯文本 (Jsoup.text())
    OWN_TEXT,      // 仅当前节点文本
    INNER_HTML,    // 内部 HTML
    OUTER_HTML,    // 完整标签 (用于 JS 处理图片反爬)
    ATTR           // 提取属性
}

/**
 * 复合选择器：支持步骤链和备选方案
 */
@Serializable
data class Selector(
    val steps: List<ParseStep> = emptyList(),
    val fallback: Selector? = null,
    val defaultValue: String = ""
)

/**
 * 网络请求配置模板（支持 {{var}} 占位符）
 */
@Serializable
data class FetchConfig(
    var method: String? = null,         // 为空则继承全局
    var charset: String? = null,
    var headers: Map<String, String> = emptyMap(),
    var headerScript: String? = null, // 新增：JS 脚本字符串
    var responseScript: String? = null, // 新增：数据解密/预处理脚本
    var bodyPayload: String? = null,
    var isJson: Boolean? = null
)

/**
 * 逻辑接口：仅用于底层 RequestResolver 统一调用
 */
interface IPage {
    val config: FetchConfig
    val urlSelector: Selector
}

/**
 * 搜索页：封装搜索逻辑
 */
@Serializable
data class SearchPage(
    override val config: FetchConfig = FetchConfig(),
    override val urlSelector: Selector = Selector(),
    val listSelector: Selector = Selector(),
    val nameSelector: Selector = Selector(),
    val authorSelector: Selector = Selector(),
    val coverSelector: Selector = Selector(),
    val detailUrlSelector: Selector = Selector()
) : IPage {
    // 强类型辅助方法，消除外部强转
    /**
     * 【新增】获取最终生成的 URL 字符串
     * @param input 搜索页为关键词，其他页为上一阶段抠出的链接/ID
     * @param ctx 变量上下文
     */
    fun getUrl(input: String, ctx: VariableContext): String {
        // 1. 先计算原始 URL (如果为空，则取 config.url，再为空则直接取 input)
        val rawUrl = RuleParser.parseString(input, urlSelector, ctx)
        // 2. 处理模板替换 (将 {{key}} 和 {{var}} 替换为真实值)
        return RuleParser.resolveTemplate(rawUrl, input, ctx)
    }
    fun getList(text: String, ctx: VariableContext ) = RuleParser.parseList(text, listSelector, ctx)
    fun getName(item: Any, ctx: VariableContext ) = RuleParser.parseString(item, nameSelector, ctx)
    fun getAuthor(item: Any, ctx: VariableContext ) = RuleParser.parseString(item, authorSelector, ctx)
    fun getCover(item: Any, ctx: VariableContext ) = RuleParser.parseString(item, coverSelector, ctx)
    fun getDetailUrl(item: Any, ctx: VariableContext ) = RuleParser.parseString(item, detailUrlSelector, ctx)
}

/**
 * 目录页：获取章节地址
 */
@Serializable
data class CatalogPage(
    override val config: FetchConfig = FetchConfig(),
    override val urlSelector: Selector = Selector(), // 如果目录和详情在同一页，这里留空
    val chapterListSelector: Selector = Selector(),
    val chapterNameSelector: Selector = Selector(),
    val chapterUrlSelector: Selector = Selector()
) : IPage {
    fun getCatalogUrl(text: String, ctx: VariableContext ) = RuleParser.parseString(text, urlSelector, ctx)
    fun getChapters(text: String, ctx: VariableContext ) = RuleParser.parseList(text, chapterListSelector, ctx)
    fun getChapterName(text: Any, ctx: VariableContext ) = RuleParser.parseString(text, chapterNameSelector, ctx)
    fun getChapterUrl(text: Any, ctx: VariableContext ) = RuleParser.parseString(text, chapterUrlSelector, ctx)
}

/**
 * 详情页：封装目录获取逻辑
 */
@Serializable
data class DetailPage(
    override val config: FetchConfig = FetchConfig(),
    override val urlSelector: Selector = Selector(),
    val bookNameSelector: Selector = Selector(),
    val bookAuthorSelector: Selector = Selector(),
    val bookCoverSelector: Selector = Selector(),
    val bookIntroSelector: Selector = Selector(),
    val bookLabelSelector: Selector = Selector(),
    val catalogUrlSelector: Selector = Selector()
) : IPage {
    fun getBookName(text: String, ctx: VariableContext ) = RuleParser.parseString(text, bookNameSelector, ctx)
    fun getBookAuthor(text: String, ctx: VariableContext ) = RuleParser.parseString(text, bookAuthorSelector, ctx)
    fun getBookCover(text: String, ctx: VariableContext ) = RuleParser.parseString(text, bookCoverSelector, ctx)
    fun getBookDesc(text: String, ctx: VariableContext ) = RuleParser.parseString(text, bookIntroSelector, ctx)
    fun getBookLabel(text: String, ctx: VariableContext ) = RuleParser.parseList(text, bookLabelSelector, ctx)
    fun getCatalogUrl(text: String, ctx: VariableContext ) = RuleParser.parseString(text, catalogUrlSelector, ctx)
}

/**
 * 正文页：封装内容提取逻辑
 */
@Serializable
data class ContentPage(
    override val config: FetchConfig = FetchConfig(),
    override val urlSelector: Selector = Selector(),
    val contentSelector: Selector = Selector(),
    val nextUrlSelector: Selector = Selector(),
    //图片头部
    val imageHeaders: Selector = Selector(),
    val decryptImage: String = "",
    val regexReplaceSelector: Selector = Selector()
) : IPage {
    fun getReaderUrl(text: String, ctx: VariableContext ) = RuleParser.parseString(text, urlSelector, ctx)

    fun getContent(text: String, ctx: VariableContext): List<Any> {
        return RuleParser.parseList(text, contentSelector, ctx)
    }

}

/**
 * 登录页：专门负责获取凭证
 */
@Serializable
data class LoginPage(
    override val config: FetchConfig = FetchConfig(method = "POST"), // 默认 POST
    override val urlSelector: Selector = Selector(),                // 登录 API 地址
    val tokenSelector: Selector = Selector(),                         // 从响应中提取 Token
    val expiresSelector: Selector = Selector()                         // 提取失效时间的规则 (用于从 JSON 提取 expires_in)
) : IPage {
    fun getToken(text: String,ctx: VariableContext = mutableMapOf()): String {
        return RuleParser.parseString(text, tokenSelector, ctx)
    }
}

/**
 * 顶层规则对象 (JSON 导入/导出的直接目标)
 */
@JSONType(orders = ["id", "name", "url", "concurrentRate", "proxyUrl", "customDns", "useCache", "globalConfig", "requireLogin", "login", "search", "detail", "catalog", "content"])
@Serializable
data class SourceRule(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    var type: Int = 0,
    var concurrentRate: Long = 1000,
    var proxyUrl: String? = null,
    var customDns: String? = null, // 1. DNS服务器: "8.8.8.8,8.8.4.4" 以逗号分割
    var useCache: Boolean = true,
    var globalConfig: FetchConfig = FetchConfig(headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SpiderIDE/1.0")),
    var requireLogin: Boolean = false,
    val login: LoginPage = LoginPage(),
    // 具体类型声明，确保点点点 (source.search.xxx) 时的开发体验
    val search: SearchPage = SearchPage(),
    val detail: DetailPage = DetailPage(),
    val catalog: CatalogPage = CatalogPage(),
    val content: ContentPage = ContentPage()
)

data class TagAction(val useCache: Boolean)

// 💡 1. 纯净的 Kotlin 扩展函数，支持任意子集的闭环切换
fun StepType.nextIn(allowedTypes: List<StepType>): StepType {
    if (allowedTypes.isEmpty()) {
        return StepType.entries[(this.ordinal + 1) % StepType.entries.size]
    }

    // 2. 找到当前类型在子集中的索引
    val currentIndex = allowedTypes.indexOf(this)

    // 💡 3. 如果当前类型不在子集里（比如目前是 CSS，但点击后要进入子集）
    // indexOf 会返回 -1，此时 (-1 + 1) % size = 0，会自动安全地跳转到子集的第一项！
    val nextIndex = (currentIndex + 1) % allowedTypes.size

    return allowedTypes[nextIndex]
}

fun SourceRule.toEntity(isEnabled: Boolean = true): RuleEntity {
    // 如果没有 ID，生成一个（这里建议在规则创建时分配）
    val finalId = this.id.ifBlank { Clock.System.now().toEpochMilliseconds().toString() }
    return RuleEntity(
        id = finalId,
        name = this.name,
        //jsonContent = JSON.toJSONString(this.copy()),
        jsonContent = Json.encodeToString<SourceRule>(this),
        isEnabled = isEnabled,
    )
}

fun RuleEntity.toDomain(): SourceRule {
    return Json.decodeFromString<SourceRule>(jsonContent)
    //return JSON.parseObject(this.jsonContent, SourceRule::class.java, JSONReader.Feature.FieldBased)
}
