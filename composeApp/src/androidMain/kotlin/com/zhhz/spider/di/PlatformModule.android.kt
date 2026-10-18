package com.zhhz.spider.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import com.zhhz.spider.remote.AndroidRuleEditorServer
import com.zhhz.spider.util.BookPackager
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

actual val platformModule = module {
    // 注入数据库：利用 androidContext() 获取上下文
    single<AppDatabase> {
        val context = androidContext()
        val dbFile = context.getDatabasePath("spider.db")
        Room.databaseBuilder<AppDatabase>(context, dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            // 仅在「数据库降级」时允许重建；升级路径缺迁移时直接报错，
            // 避免无差别 destructive 静默清空书架 / 书源 / 阅读设置
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    single(named("cacheDir")) {
        File(androidContext().noBackupFilesDir, "spider_snapshots").apply {
            if (!exists()) mkdirs()
        }
    }

    // 旧版设置 JSON 位于 cacheDir/spider_snapshots 下，迁移时仍需从旧位置读取
    single(named("legacyCacheDir")) {
        File(androidContext().cacheDir, "spider_snapshots")
    }

    single(named("imageCacheDir")) {
        migrateLegacyCache(androidContext(), "spider_image").toOkioPath()
    }

    single(named("bookCacheDir")) {
        migrateLegacyCache(androidContext(), "spider_book").toOkioPath()
    }

    singleOf(::BookPackager)
    singleOf(::AndroidRuleEditorServer)

}

/**
 * 持久缓存写入 noBackupFilesDir：不会被系统按“缓存”清理，也不会进入自动备份。
 * 旧版本位于 cacheDir，首次访问时尝试整体改名以保留已下载内容；
 * 改名失败（跨卷、占用等）则保留旧目录并新建空目录。
 */
private fun migrateLegacyCache(context: android.content.Context, name: String): File {
    val target = File(context.noBackupFilesDir, name)
    if (!target.exists()) {
        val legacy = File(context.cacheDir, name)
        if (legacy.exists()) {
            target.parentFile?.mkdirs()
            legacy.renameTo(target)
        }
        if (!target.exists()) target.mkdirs()
    }
    return target
}
