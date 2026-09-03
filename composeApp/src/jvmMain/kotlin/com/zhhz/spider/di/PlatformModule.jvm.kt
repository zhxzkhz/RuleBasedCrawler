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
import okio.Path.Companion.toOkioPath
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

actual val platformModule = module {
    // 注入数据库
    single<AppDatabase> {
        val dbFile = File(System.getProperty("java.io.tmpdir"), "spider.db")
        println("dbFile >> $dbFile")
        Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .fallbackToDestructiveMigration(true)
            .build()
    }
    
    single(named("cacheDir")) {
        File(System.getProperty("java.io.tmpdir"), "spider_snapshots").apply {
            if (!exists()) mkdirs()
        }
    }

    single(named("imageCacheDir")) {
        File(System.getProperty("java.io.tmpdir"), "spider_image").apply {
            if (!exists()) mkdirs()
        }.toOkioPath()
    }

    single(named("bookCacheDir")) {
        File(System.getProperty("java.io.tmpdir"), "spider_book").apply {
            if (!exists()) mkdirs()
        }.toOkioPath()
    }

    singleOf(::BookPackager)

    single<PlatformContext> { PlatformContext.INSTANCE }

    // 注入桌面端专有的 Swing 编辑器
    //single<EditorProvider> { DesktopEditorProvider() }
}