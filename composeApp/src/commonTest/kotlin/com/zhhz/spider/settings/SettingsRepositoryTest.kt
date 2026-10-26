package com.zhhz.spider.settings

import com.zhhz.spider.db.SettingsDao
import com.zhhz.spider.db.SettingsEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {

    private class FakeSettingsDao : SettingsDao {
        val rows = mutableMapOf<String, String>()
        override suspend fun getValue(key: String): String? = rows[key]
        override suspend fun upsert(setting: SettingsEntity) {
            rows[setting.key] = setting.value
        }
        override suspend fun getAll(): List<SettingsEntity> =
            rows.map { SettingsEntity(key = it.key, value = it.value) }
    }

    /** 清掉内存缓存强制走数据库读取路径 */
    private suspend fun SettingsRepository.reloadFromDb(): SettingsRepository.AppSettings {
        clearCache()
        return load()
    }

    @Test
    fun stringSettingsRoundTripThroughDatabase() = runBlocking {
        val repo = SettingsRepository(FakeSettingsDao())
        repo.save(
            SettingsRepository.AppSettings(
                readerFontFamily = "sans-serif",
                readerMangaMode = "paged",
                shelfSort = "title",
                shelfViewMode = "list"
            )
        )
        val loaded = repo.reloadFromDb()
        assertEquals("sans-serif", loaded.readerFontFamily)
        assertEquals("paged", loaded.readerMangaMode)
        assertEquals("title", loaded.shelfSort)
        assertEquals("list", loaded.shelfViewMode)
    }

    @Test
    fun updateOnlyChangesTargetFields() = runBlocking {
        val repo = SettingsRepository(FakeSettingsDao())
        repo.save(
            SettingsRepository.AppSettings(
                readerFontSize = 22,
                readerFontFamily = "monospace",
                readerMangaMode = "rtl"
            )
        )

        // 模拟保存下载设置：绝不能重置阅读器设置
        repo.update { it.copy(downloadBooks = 3, downloadImages = 8) }

        withTimeout(5_000) {
            while (repo.load().downloadBooks != 3) delay(20)
        }
        val loaded = repo.reloadFromDb()
        assertEquals(22, loaded.readerFontSize)
        assertEquals("monospace", loaded.readerFontFamily)
        assertEquals("rtl", loaded.readerMangaMode)
        assertEquals(3, loaded.downloadBooks)
        assertEquals(8, loaded.downloadImages)
    }

    @Test
    fun rapidUpdatesKeepOrderAndLastValueWins() = runBlocking {
        val repo = SettingsRepository(FakeSettingsDao())
        repeat(10) { i -> repo.update { it.copy(readerMargin = 10f + i) } }

        withTimeout(5_000) {
            while (repo.load().readerMargin != 19f) delay(20)
        }
        assertEquals(19f, repo.reloadFromDb().readerMargin)
    }

    /**
     * 历史缺陷回归：单个 transform 抛异常曾会让写入者协程死亡，
     * 此后所有 update() 静默失效（设置再也存不下来）。
     * 现在坏变换只被跳过，后续变更照常生效。
     */
    @Test
    fun failingTransformDoesNotKillWriterLoop() = runBlocking {
        val repo = SettingsRepository(FakeSettingsDao())
        repo.save(SettingsRepository.AppSettings(readerFontSize = 20))

        repo.update { throw IllegalStateException("坏变换") }
        repo.update { it.copy(readerFontSize = 30) }

        withTimeout(5_000) {
            while (repo.load().readerFontSize != 30) delay(20)
        }
        assertEquals(30, repo.reloadFromDb().readerFontSize)
    }

    /**
     * 历史缺陷回归：JSON 迁移落库失败（saveInternal 内部吞异常）后
     * 仍无条件删除源文件——迁移失败即永久丢设置。
     * 现在只有落库成功才删文件，失败保留待下次重试。
     */
    @Test
    fun migrationFailureKeepsSourceJsonForRetry() = runBlocking {
        val legacyDir = java.nio.file.Files.createTempDirectory("settings-legacy").toFile()
        try {
            java.io.File(legacyDir, "app_settings.json").writeText(
                """{"readerFontSize": 24}"""
            )
            val failingDao = object : SettingsDao {
                override suspend fun getValue(key: String): String? = null
                override suspend fun upsert(setting: SettingsEntity) {
                    throw IllegalStateException("数据库不可用")
                }
                override suspend fun getAll(): List<SettingsEntity> = emptyList()
            }

            val repo = SettingsRepository(failingDao, legacyDir)
            val loaded = repo.load()

            assertEquals(18, loaded.readerFontSize) // 迁移失败 → 默认值
            // 关键断言：源文件必须保留，下次启动可重试
            assertEquals(true, java.io.File(legacyDir, "app_settings.json").exists())
        } finally {
            legacyDir.deleteRecursively()
        }
    }
}
