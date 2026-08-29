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
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    single(named("cacheDir")) {
        File(androidContext().cacheDir, "spider_snapshots").apply {
            if (!exists()) mkdirs()
        }
    }

    single(named("imageCacheDir")) {
        File(androidContext().cacheDir, "spider_image").apply {
            if (!exists()) mkdirs()
        }.toOkioPath()
    }

    single(named("bookCacheDir")) {
        File(androidContext().cacheDir, "spider_book").apply {
            if (!exists()) mkdirs()
        }.toOkioPath()
    }

    singleOf(::BookPackager)
    singleOf(::AndroidRuleEditorServer)

}
