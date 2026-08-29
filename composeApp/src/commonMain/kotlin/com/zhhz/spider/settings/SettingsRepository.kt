package com.zhhz.spider.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.concurrent.thread

/**
 * 轻量应用设置：以 JSON 文件形式存储在缓存目录，避免数据库迁移成本。
 * 读取为阻塞式（文件极小，仅启动时读一次）；保存为异步。
 */
class SettingsRepository(
    cacheDir: File
) {
    companion object {
        /** 同时下载书本数的可配置范围 */
        val DOWNLOAD_BOOKS_RANGE = 1..5

        /** 同时下载图片数的可配置范围 */
        val DOWNLOAD_IMAGES_RANGE = 2..12
    }

    @Serializable
    data class AppSettings(
        val downloadBooks: Int = 2,
        val downloadImages: Int = 6,
        // ---------------- 阅读器 ----------------
        val readerFontSize: Int = 18,
        val readerBackground: Long = 0xFFFAF7F0,
        val readerTextColor: Long = 0xFF333333,
        val readerImmersive: Boolean = false,
        // 漫画阅读模式：webtoon(条滑) / paged(翻页) / rtl(日漫右开)
        val readerMangaMode: String = "webtoon",
        // 书架排序：recent(最近阅读) / created(加入时间) / title(书名)
        val shelfSort: String = "recent",
        // 书架显示模式：grid(网格) / list(列表)
        val shelfViewMode: String = "grid",
        // 搜索历史（最多 10 条，新的在前）
        val searchHistory: List<String> = emptyList(),
        // 规则订阅源 URL 列表
        val ruleSubscriptions: List<String> = emptyList(),
        // ---------------- 追更检测 ----------------
        // 上次自动检查更新的时间戳（节流：间隔不足则不自动刷新）
        val lastUpdateCheckAt: Long = 0,
        // 是否允许自动更新“未分组”的书籍（默认 true）
        val updateUngroupedBooks: Boolean = true,
        // ---------------- 阅读器扩展 ----------------
        val readerLineSpacing: Int = 10,
        val readerMargin: Float = 40f,
        val readerFontFamily: String = "serif",
        val readerBrightness: Float = 1.0f
    )

    private val file = File(cacheDir, "app_settings.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** 阻塞读取（文件极小；DownloadManager 构造时用一次） */
    fun loadBlocking(): AppSettings = runCatching {
        if (file.exists()) json.decodeFromString<AppSettings>(file.readText()) else AppSettings()
    }.getOrElse { AppSettings() }

    suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(settings))
        }
    }
}
