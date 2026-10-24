package com.zhhz.spider.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import coil3.PlatformContext
import com.zhhz.spider.db.AppDatabase
import com.zhhz.spider.db.MIGRATION_3_4
import com.zhhz.spider.db.MIGRATION_4_5
import com.zhhz.spider.db.MIGRATION_5_6
import com.zhhz.spider.db.MIGRATION_6_7
import com.zhhz.spider.db.MIGRATION_7_8
import com.zhhz.spider.db.MIGRATION_8_9
import com.zhhz.spider.db.MIGRATION_9_10
import com.zhhz.spider.db.MIGRATION_10_11
import com.zhhz.spider.db.MIGRATION_11_12
import com.zhhz.spider.util.BookPackager
import io.github.oshai.kotlinlogging.KotlinLogging
import okio.Path.Companion.toOkioPath
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

private val diLogger = KotlinLogging.logger {}

actual val platformModule = module {
    // 注入数据库
    single<AppDatabase> {
        val dbFile = resolveDesktopDatabaseFile()
        diLogger.info { "dbFile >> $dbFile" }
        Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            // 仅在「数据库降级」时允许重建；升级路径缺迁移时直接报错，
            // 避免无差别 destructive 静默清空书架 / 书源 / 阅读设置
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    single(named("cacheDir")) {
        File(desktopDataDirectory(), "snapshots").apply {
            if (!exists()) mkdirs()
        }
    }

    // 旧版设置 JSON 位于临时目录的 spider_snapshots 下，迁移时仍需从旧位置读取
    single(named("legacyCacheDir")) {
        File(System.getProperty("java.io.tmpdir"), "spider_snapshots")
    }

    single(named("imageCacheDir")) {
        migrateLegacyCache("images", "spider_image").toOkioPath()
    }

    single(named("bookCacheDir")) {
        migrateLegacyCache("books", "spider_book").toOkioPath()
    }

    singleOf(::BookPackager)

    single<PlatformContext> { PlatformContext.INSTANCE }

    // 注入桌面端专有的 Swing 编辑器
    //single<EditorProvider> { DesktopEditorProvider() }
}

private fun desktopDataDirectory(): File =
    File(System.getProperty("user.home"), ".spider").apply {
        if (!exists()) mkdirs()
    }

/**
 * 桌面端缓存位置：固定在用户主目录下，避免系统清理临时目录导致离线内容丢失。
 * 若新位置不存在且旧临时目录存在，优先整体改名（同盘时原子且快速）；
 * 改名失败（跨盘、占用等）则保留旧缓存并新建空目录。
 */
private fun migrateLegacyCache(name: String, legacyName: String): File {
    val target = File(desktopDataDirectory(), name)
    if (!target.exists()) {
        val legacy = File(System.getProperty("java.io.tmpdir"), legacyName)
        if (legacy.exists()) {
            target.parentFile?.mkdirs()
            legacy.renameTo(target)
        }
        if (!target.exists()) target.mkdirs()
    }
    return target
}

/**
 * 桌面端数据库位置：固定在用户主目录下，避免放在系统临时目录被清理
 * 而导致书架、书源、阅读设置等全部数据丢失。
 * 若新位置尚无数据库而旧临时目录中存在，则连同 wal/shm 一起整体迁移。
 */
private fun resolveDesktopDatabaseFile(): File {
    val dataDir = desktopDataDirectory()
    val newFile = File(dataDir, "spider.db")
    val legacyFile = File(System.getProperty("java.io.tmpdir"), "spider.db")

    if (!newFile.exists() && legacyFile.exists()) {
        runCatching {
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val src = File(legacyFile.parentFile, legacyFile.name + suffix)
                if (src.exists()) {
                    src.copyTo(File(newFile.parentFile, newFile.name + suffix), overwrite = true)
                }
            }
            diLogger.info { "已迁移临时目录数据库到 ${newFile.absolutePath}" }
        }.onFailure { e ->
            diLogger.warn(e) { "数据库迁移失败，将在新位置重新创建" }
        }
    }
    return newFile
}
