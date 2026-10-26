package com.zhhz.spider.settings

import com.zhhz.spider.db.SettingsDao
import com.zhhz.spider.db.SettingsEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 应用设置持久化：存储在 Room 数据库的 app_settings 表中（key-value 结构）。
 * 清理缓存不会丢失设置。
 *
 * 并发模型：
 * - 所有读写都经由 [mutex] 串行，读不到半写状态；
 * - [update] 走单一写入者队列：基于"最新已保存设置"应用变换后整份写回，
 *   高频变更（如拖动滑杆）自动合并，且不会用旧快照覆盖其他模块刚写入的字段；
 * - 保存运行在仓库自己的应用级作用域上，不随调用方（如阅读器 ViewModel）销毁而取消。
 */
class SettingsRepository(
    private val settingsDao: SettingsDao,
    private val legacyCacheDir: File? = null
) {
    companion object {
        val DOWNLOAD_BOOKS_RANGE = 1..5
        val DOWNLOAD_IMAGES_RANGE = 2..12
        private const val KEY_PREFIX = "setting."
    }

    @Serializable
    data class AppSettings(
        val downloadBooks: Int = 2,
        val downloadImages: Int = 6,
        val readerFontSize: Int = 18,
        val readerBackground: Long = 0xFFFAF7F0,
        val readerTextColor: Long = 0xFF333333,
        val readerImmersive: Boolean = false,
        val readerMangaMode: String = "webtoon",
        val shelfSort: String = "recent",
        val shelfViewMode: String = "grid",
        val searchHistory: List<String> = emptyList(),
        val ruleSubscriptions: List<String> = emptyList(),
        val lastUpdateCheckAt: Long = 0,
        val updateUngroupedBooks: Boolean = true,
        val readerLineSpacing: Int = 10,
        val readerParagraphSpacing: Int = 5,
        val readerMargin: Float = 40f,
        val readerFontFamily: String = "serif",
        val readerBrightness: Float = 1.0f,
        val readerUseAndroidReader: Boolean = false,
        val readerPageAnimation: Boolean = true,
        val appStartDefaultGroupMask: Long = 0L
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedSettings: AppSettings? = null

    // 仓库级作用域：保存不随调用方（如阅读器 ViewModel）销毁而取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pendingUpdates = Channel<(AppSettings) -> AppSettings>(Channel.UNLIMITED)

    init {
        // 单一写入者：锁内基于最新设置依次应用更新并整份写回。
        // ⚠️ 每个变换独立容错：某个 transform 抛异常时只跳过该次变更——
        // 若异常逃逸导致写入者协程死亡，Channel 将无人消费，
        // 此后所有 update() 静默失效（设置再也存不下来）
        scope.launch {
            for (transform in pendingUpdates) {
                mutex.withLock {
                    var settings = applyTransformSafely(loadInternal(), transform)
                        ?: return@withLock // 首个变换即失败：本次不落盘，后续排队项留待下一轮
                    // 合并已排队的后续变更，滑杆高频拖动只落盘一次
                    while (true) {
                        val next = pendingUpdates.tryReceive().getOrNull() ?: break
                        // 单个排队变换失败只跳过它，继续合并其余
                        settings = applyTransformSafely(settings, next) ?: continue
                    }
                    saveInternal(settings)
                }
            }
        }
    }

    private fun applyTransformSafely(
        current: AppSettings,
        transform: (AppSettings) -> AppSettings
    ): AppSettings? = runCatching { transform(current) }
        .onFailure { e -> logger.error(e) { "设置更新变换执行失败，已跳过该次变更" } }
        .getOrNull()

    /**
     * 异步原子更新：基于最新已保存设置应用 [transform] 后整份写回。
     * 只改目标字段，不会重置其他设置；重复调用按入队顺序生效，后写覆盖先写。
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        pendingUpdates.trySend(transform)
    }

    /**
     * 从数据库加载设置（首次启动时自动迁移旧 JSON 文件）
     */
    suspend fun load(): AppSettings = mutex.withLock { loadInternal() }

    /**
     * 保存整份设置到数据库。仅在需要覆盖全部字段时使用；
     * 修改个别字段请优先使用 [update]，避免旧快照覆盖其他模块的并发修改。
     */
    suspend fun save(settings: AppSettings) = mutex.withLock { saveInternal(settings) }

    fun clearCache() {
        cachedSettings = null
    }

    private suspend fun loadInternal(): AppSettings {
        cachedSettings?.let { return it }

        migrateFromJsonIfNeeded()

        val rows = settingsDao.getAll()
        if (rows.isEmpty()) {
            val defaults = AppSettings()
            cachedSettings = defaults
            return defaults
        }

        val map = rows.associate { it.key to it.value }
        val settings = AppSettings(
            downloadBooks = map.getInt("downloadBooks", 2),
            downloadImages = map.getInt("downloadImages", 6),
            readerFontSize = map.getInt("readerFontSize", 18),
            readerBackground = map.getLong("readerBackground", 0xFFFAF7F0),
            readerTextColor = map.getLong("readerTextColor", 0xFF333333),
            readerImmersive = map.getBool("readerImmersive", false),
            readerMangaMode = map.getString("readerMangaMode", "webtoon"),
            shelfSort = map.getString("shelfSort", "recent"),
            shelfViewMode = map.getString("shelfViewMode", "grid"),
            searchHistory = map.getList("searchHistory"),
            ruleSubscriptions = map.getList("ruleSubscriptions"),
            lastUpdateCheckAt = map.getLong("lastUpdateCheckAt", 0),
            updateUngroupedBooks = map.getBool("updateUngroupedBooks", true),
            readerLineSpacing = map.getInt("readerLineSpacing", 10),
            readerParagraphSpacing = map.getInt("readerParagraphSpacing", 5),
            readerMargin = map.getFloat("readerMargin", 40f),
            readerFontFamily = map.getString("readerFontFamily", "serif"),
            readerBrightness = map.getFloat("readerBrightness", 1.0f),
            readerUseAndroidReader = map.getBool("readerUseAndroidReader", false),
            readerPageAnimation = map.getBool("readerPageAnimation", true),
            appStartDefaultGroupMask = map.getLong("appStartDefaultGroupMask", 0)
        )
        cachedSettings = settings
        return settings
    }

    /** @return 是否成功落库（失败已内部记录日志，调用方据此决定是否保留迁移源文件） */
    private suspend fun saveInternal(settings: AppSettings): Boolean {
        return runCatching {
            val entries = mapOf(
                "downloadBooks" to settings.downloadBooks.toString(),
                "downloadImages" to settings.downloadImages.toString(),
                "readerFontSize" to settings.readerFontSize.toString(),
                "readerBackground" to settings.readerBackground.toString(),
                "readerTextColor" to settings.readerTextColor.toString(),
                "readerImmersive" to settings.readerImmersive.toString(),
                "readerMangaMode" to settings.readerMangaMode,
                "shelfSort" to settings.shelfSort,
                "shelfViewMode" to settings.shelfViewMode,
                "searchHistory" to json.encodeToString(settings.searchHistory),
                "ruleSubscriptions" to json.encodeToString(settings.ruleSubscriptions),
                "lastUpdateCheckAt" to settings.lastUpdateCheckAt.toString(),
                "updateUngroupedBooks" to settings.updateUngroupedBooks.toString(),
                "readerLineSpacing" to settings.readerLineSpacing.toString(),
                "readerParagraphSpacing" to settings.readerParagraphSpacing.toString(),
                "readerMargin" to settings.readerMargin.toString(),
                "readerFontFamily" to settings.readerFontFamily,
                "readerBrightness" to settings.readerBrightness.toString(),
                "readerUseAndroidReader" to settings.readerUseAndroidReader.toString(),
                "readerPageAnimation" to settings.readerPageAnimation.toString(),
                "appStartDefaultGroupMask" to settings.appStartDefaultGroupMask.toString()
            )
            entries.forEach { (key, value) ->
                settingsDao.upsert(SettingsEntity(key = KEY_PREFIX + key, value = value))
            }
            cachedSettings = settings
            true
        }.onFailure { e ->
            logger.error(e) { "保存设置失败" }
        }.getOrDefault(false)
    }

    private suspend fun migrateFromJsonIfNeeded() {
        val dir = legacyCacheDir ?: return
        val file = File(dir, "app_settings.json")
        if (!file.exists()) return

        runCatching {
            val oldSettings = json.decodeFromString<AppSettings>(file.readText())
            // 只有确认落库成功才删源文件；失败时保留 JSON，下次启动重试
            // （旧实现 saveInternal 吞掉异常后无条件删文件，失败即永久丢设置）
            if (saveInternal(oldSettings)) {
                file.delete()
                logger.info { "已从 JSON 文件迁移设置到数据库" }
            } else {
                logger.error { "JSON 设置迁移落库失败，保留源文件待下次重试" }
            }
        }.onFailure { e ->
            logger.warn(e) { "JSON 设置文件迁移失败，使用默认值" }
        }
    }

    private fun Map<String, String>.getInt(key: String, default: Int): Int =
        get(KEY_PREFIX + key)?.toIntOrNull() ?: default

    private fun Map<String, String>.getLong(key: String, default: Long): Long =
        get(KEY_PREFIX + key)?.toLongOrNull() ?: default

    private fun Map<String, String>.getFloat(key: String, default: Float): Float =
        get(KEY_PREFIX + key)?.toFloatOrNull() ?: default

    private fun Map<String, String>.getBool(key: String, default: Boolean): Boolean =
        get(KEY_PREFIX + key)?.toBooleanStrictOrNull() ?: default

    private fun Map<String, String>.getString(key: String, default: String): String =
        get(KEY_PREFIX + key) ?: default

    private fun Map<String, String>.getList(key: String): List<String> =
        get(KEY_PREFIX + key)?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        } ?: emptyList()
}
