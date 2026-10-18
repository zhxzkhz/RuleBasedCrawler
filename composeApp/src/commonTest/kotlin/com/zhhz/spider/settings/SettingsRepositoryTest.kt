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
}
