package com.zhhz.spider.rule

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONReader
import com.alibaba.fastjson2.annotation.JSONField
import com.alibaba.fastjson2.annotation.JSONType
import com.zhhz.spider.constant.BookType
import com.zhhz.spider.db.RuleEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.time.Clock

// 变量上下文：跨页面记忆的“记事本”
typealias VariableContext = MutableMap<String, String>

/**
 * 步骤类型定义
 */
@Serializable
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
    val chapterUrlSelector: Selector = Selector(),
    val chapterGroupSelector: Selector = Selector() // 💡 新增：章节所属卷名/分组名
) : IPage {
    fun getCatalogUrl(text: String, ctx: VariableContext ) = RuleParser.parseString(text, urlSelector, ctx)
    fun getChapters(text: String, ctx: VariableContext ) = RuleParser.parseList(text, chapterListSelector, ctx)
    fun getChapterName(text: Any, ctx: VariableContext ) = RuleParser.parseString(text, chapterNameSelector, ctx)
    fun getChapterUrl(text: Any, ctx: VariableContext ) = RuleParser.parseString(text, chapterUrlSelector, ctx)
    fun getChapterGroup(text: Any, ctx: VariableContext ) = RuleParser.parseString(text, chapterGroupSelector, ctx)
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
    val preserveInlineImages: Boolean = false,
    val nextUrlSelector: Selector = Selector(),
    //图片头部
    val imageHeaders: Selector = Selector(),
    val decryptImage: String = "",
    val regexReplaceSelector: Selector = Selector()
) : IPage {
    fun getReaderUrl(text: String, ctx: VariableContext ) = RuleParser.parseString(text, urlSelector, ctx)

    fun getContent(text: String, ctx: VariableContext): List<Any> {
        val selector = if (preserveInlineImages) {
            contentSelector.withLastTextQueryAsInnerHtml()
        } else {
            contentSelector
        }
        return RuleParser.parseList(text, selector, ctx)
    }

}

/**
 * 正文图片需要随 HTML 一起进入阅读器。只转换选择器链中最后一个文本型 CSS/XPath 步骤，
 * 中间步骤仍可继续使用 ELEMENT 来传递节点。
 */
private fun Selector.withLastTextQueryAsInnerHtml(): Selector {
    val lastQueryIndex = steps.indexOfLast { it.type == StepType.CSS || it.type == StepType.XPATH }
    val adjustedSteps = if (lastQueryIndex >= 0 && steps[lastQueryIndex].extractType == ExtractType.TEXT) {
        steps.toMutableList().apply {
            this[lastQueryIndex] = this[lastQueryIndex].copy(extractType = ExtractType.INNER_HTML)
        }
    } else {
        steps
    }
    return copy(
        steps = adjustedSteps,
        fallback = fallback?.withLastTextQueryAsInnerHtml()
    )
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

/** 固定分类/榜单：直接写死在规则里的 名称+地址（可带分组），无需从入口页解析 */
@Serializable
data class ExploreFixedTab(
    val title: String = "",
    val url: String = "",
    val group: String = ""
)

/**
 * 分类下的排序选项：param 拼接到当前分类地址上（自动处理 ?/& 连接符）。
 * 示例："?sort=hot"、"&o=0"；空串表示默认排序。{{page}} 翻页模板不受影响。
 */
@Serializable
data class ExploreSortOption(
    val title: String = "",
    val param: String = ""
)

/** 把排序参数拼接到分类地址（自动处理 ?/& 连接符） */
fun applyExploreSort(url: String, sortParam: String): String {
    val p = sortParam.trim()
    if (p.isEmpty()) return url
    return if (url.contains("?")) {
        url + (if (p.startsWith("&")) p else "&" + p.removePrefix("?"))
    } else {
        url + (if (p.startsWith("?")) p else "?" + p.removePrefix("&"))
    }
}

/**
 * 评论页：书籍评论/短评列表。
 * urlSelector 生成评论地址（支持 {{key}}=详情URL、{{page}} 翻页），列表项解析出用户/内容等。
 */
@Serializable
data class CommentPage(
    override val config: FetchConfig = FetchConfig(),
    override val urlSelector: Selector = Selector(),   // 评论接口/页面地址
    val listSelector: Selector = Selector(),           // 评论列表项
    val userSelector: Selector = Selector(),           // 用户名
    val contentSelector: Selector = Selector(),        // 评论内容
    val timeSelector: Selector = Selector(),           // 时间（可选）
    val scoreSelector: Selector = Selector(),          // 评分（可选）
    val avatarSelector: Selector = Selector(),         // 头像（可选）
    val replyListSelector: Selector = Selector()       // 楼中楼回复列表（可选，结构与评论项一致）
) : IPage {
    fun getCommentUrl(input: String, ctx: VariableContext): String {
        val rawUrl = RuleParser.parseString(input, urlSelector, ctx)
        return RuleParser.resolveTemplate(rawUrl, input, ctx)
    }
    fun getComments(text: String, ctx: VariableContext) = RuleParser.parseList(text, listSelector, ctx)
    fun getUser(item: Any, ctx: VariableContext) = RuleParser.parseString(item, userSelector, ctx)
    fun getContent(item: Any, ctx: VariableContext) = RuleParser.parseString(item, contentSelector, ctx)
    fun getTime(item: Any, ctx: VariableContext): String {
        if (timeSelector.steps.isEmpty()) return ""
        return RuleParser.parseString(item, timeSelector, ctx).trim()
    }
    fun getScore(item: Any, ctx: VariableContext): String {
        if (scoreSelector.steps.isEmpty()) return ""
        return RuleParser.parseString(item, scoreSelector, ctx).trim()
    }
    fun getAvatar(item: Any, ctx: VariableContext): String {
        if (avatarSelector.steps.isEmpty()) return ""
        return RuleParser.parseString(item, avatarSelector, ctx).trim()
    }
    /** 楼中楼回复列表（可选）：配置后每条评论下的回复会被平铺解析 */
    fun getReplyList(item: Any, ctx: VariableContext): List<Any> {
        if (replyListSelector.steps.isEmpty()) return emptyList()
        return RuleParser.parseList(item, replyListSelector, ctx)
    }
}

/**
 * 发现页：规则分类/榜单。
 * 固定分类（fixedTabs）优先；未配置固定分类时，才从入口页解析动态标签。
 * 书籍列表选择器未配置时回退复用搜索页结构。
 */
@Serializable
data class ExplorePage(
    override val config: FetchConfig = FetchConfig(),
    override val urlSelector: Selector = Selector(),   // 发现入口地址，留空则使用书源主页 URL
    val fixedTabs: List<ExploreFixedTab> = emptyList(), // 固定分类/榜单（写死 名称+地址）
    var tabsScript: String? = null,                    // JS 脚本动态生成分类（返回 [{title,url}] 数组或 JSON 字符串）
    val sorts: List<ExploreSortOption> = emptyList(),  // 分类下的排序选项（结果页出 Chip 切换）
    val tabsSelector: Selector = Selector(),           // 分类/榜单标签列表
    val tabNameSelector: Selector = Selector(),        // 分类名称
    val tabUrlSelector: Selector = Selector(),         // 分类对应的书籍列表页地址
    val tabGroupSelector: Selector = Selector(),       // 分类分组名（如 男生/女生），留空则不分組
    val listSelector: Selector = Selector(),           // 分类下的书籍列表
    val nameSelector: Selector = Selector(),
    val authorSelector: Selector = Selector(),
    val coverSelector: Selector = Selector(),
    val detailUrlSelector: Selector = Selector()
) : IPage {
    fun getExploreUrl(input: String, ctx: VariableContext): String {
        val rawUrl = RuleParser.parseString(input, urlSelector, ctx)
        return RuleParser.resolveTemplate(rawUrl, input, ctx)
    }
    fun getTabs(text: String, ctx: VariableContext) = RuleParser.parseList(text, tabsSelector, ctx)
    fun getTabName(item: Any, ctx: VariableContext) = RuleParser.parseString(item, tabNameSelector, ctx)
    fun getTabGroup(item: Any, ctx: VariableContext): String {
        if (tabGroupSelector.steps.isEmpty()) return ""
        return RuleParser.parseString(item, tabGroupSelector, ctx).trim()
    }
    fun getTabUrl(item: Any, ctx: VariableContext): String {
        val rawUrl = RuleParser.parseString(item, tabUrlSelector, ctx)
        return RuleParser.resolveTemplate(rawUrl, rawUrl, ctx)
    }
    fun getList(text: String, ctx: VariableContext) = RuleParser.parseList(text, listSelector, ctx)
    fun getName(item: Any, ctx: VariableContext) = RuleParser.parseString(item, nameSelector, ctx)
    fun getAuthor(item: Any, ctx: VariableContext) = RuleParser.parseString(item, authorSelector, ctx)
    fun getCover(item: Any, ctx: VariableContext) = RuleParser.parseString(item, coverSelector, ctx)
    fun getDetailUrl(item: Any, ctx: VariableContext) = RuleParser.parseString(item, detailUrlSelector, ctx)
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
    var type: Int = BookType.text,
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
    val content: ContentPage = ContentPage(),
    val explore: ExplorePage = ExplorePage(),
    val comment: CommentPage = CommentPage(),
    // 规则最后保存时间（来自 RuleEntity.updateTime，不参与 JSON 序列化）：
    // 作为网页快照缓存的盐，规则一改快照自动失效
    @Transient val updatedAt: Long = 0L
)

/** 规则是否配置了可用的评论规则 */
fun SourceRule.supportsComments(): Boolean =
    comment.listSelector.steps.isNotEmpty() && comment.contentSelector.steps.isNotEmpty()

data class TagAction(val useCache: Boolean, val cacheSalt: String = "")

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

/** 详情地址规则可将用户输入的 ID 转换成实际详情页地址。 */
fun SourceRule.supportsIdSearch(): Boolean = detail.urlSelector.steps.isNotEmpty()

// 💡 大多数网站榜单列表与搜索列表 DOM 结构一致：
// 榜单的书籍列表选择器未单独配置时，直接回退复用搜索页的选择器。
fun SourceRule.exploreListSelector(): Selector =
    explore.listSelector.takeIf { it.steps.isNotEmpty() } ?: search.listSelector

fun SourceRule.exploreNameSelector(): Selector =
    explore.nameSelector.takeIf { it.steps.isNotEmpty() } ?: search.nameSelector

fun SourceRule.exploreAuthorSelector(): Selector =
    explore.authorSelector.takeIf { it.steps.isNotEmpty() } ?: search.authorSelector

fun SourceRule.exploreCoverSelector(): Selector =
    explore.coverSelector.takeIf { it.steps.isNotEmpty() } ?: search.coverSelector

fun SourceRule.exploreDetailUrlSelector(): Selector =
    explore.detailUrlSelector.takeIf { it.steps.isNotEmpty() } ?: search.detailUrlSelector

/** 规则是否配置了可用的发现/榜单规则（固定分类/JS 生成/动态标签三选一；列表结构可回退复用搜索页） */
fun SourceRule.supportsExplore(): Boolean =
    (explore.fixedTabs.any { it.title.isNotBlank() && it.url.isNotBlank() } ||
            !explore.tabsScript.isNullOrBlank() ||
            explore.tabsSelector.steps.isNotEmpty()) &&
            exploreListSelector().steps.isNotEmpty() &&
            exploreDetailUrlSelector().steps.isNotEmpty()

fun RuleEntity.toDomain(): SourceRule {
    // ⚠️ 必须以实体主键为准：SourceRule.id 的默认值是 UUID.randomUUID()，
    // 而 kotlinx 反序列化遇到缺失字段会每次重新求值默认值，
    // 因此 jsonContent 中没有 id 的规则（订阅导入最常见）每次解析都会得到新的随机 id，
    // 导致书架保存的 ruleId 对不上、书打不开、会话与缓存全部失效。
    // updatedAt 同理取实体值：作为快照缓存盐，规则保存后旧快照自动失效。
    return Json.decodeFromString<SourceRule>(jsonContent).copy(id = this.id, updatedAt = this.updateTime)
    //return JSON.parseObject(this.jsonContent, SourceRule::class.java, JSONReader.Feature.FieldBased)
}
