package com.zhhz.spider.settings

import com.zhhz.spider.db.SettingsDao
import com.zhhz.spider.db.SettingsEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 应用设置持久化：存储在 Room 数据库的 app_settings 表中（key-value 结构）。
 * 清理缓存不会丢失设置。
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

    /**
     * 从数据库加载设置（首次启动时自动迁移旧 JSON 文件）
     */
    suspend fun load(): AppSettings {
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
            readerMangaMode = map["readerMangaMode"] ?: "webtoon",
            shelfSort = map["shelfSort"] ?: "recent",
            shelfViewMode = map["shelfViewMode"] ?: "grid",
            searchHistory = map.getList("searchHistory"),
            ruleSubscriptions = map.getList("ruleSubscriptions"),
            lastUpdateCheckAt = map.getLong("lastUpdateCheckAt", 0),
            updateUngroupedBooks = map.getBool("updateUngroupedBooks", true),
            readerLineSpacing = map.getInt("readerLineSpacing", 10),
            readerParagraphSpacing = map.getInt("readerParagraphSpacing", 5),
            readerMargin = map.getFloat("readerMargin", 40f),
            readerFontFamily = map["readerFontFamily"] ?: "serif",
            readerBrightness = map.getFloat("readerBrightness", 1.0f),
            readerUseAndroidReader = map.getBool("readerUseAndroidReader", false),
            readerPageAnimation = map.getBool("readerPageAnimation", true),
            appStartDefaultGroupMask = map.getLong("appStartDefaultGroupMask", 0)
        )
        cachedSettings = settings
        return settings
    }

    /**
     * 保存设置到数据库
     */
    suspend fun save(settings: AppSettings) {
        runCatching {
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
        }.onFailure { e ->
            logger.error(e) { "保存设置失败" }
        }
    }

    fun clearCache() {
        cachedSettings = null
    }

    private suspend fun migrateFromJsonIfNeeded() {
        val dir = legacyCacheDir ?: return
        val file = File(dir, "app_settings.json")
        if (!file.exists()) return

        runCatching {
            val oldSettings = json.decodeFromString<AppSettings>(file.readText())
            save(oldSettings)
            file.delete()
            logger.info { "已从 JSON 文件迁移设置到数据库" }
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

    private fun Map<String, String>.getList(key: String): List<String> =
        get(KEY_PREFIX + key)?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrNull()
        } ?: emptyList()
}
