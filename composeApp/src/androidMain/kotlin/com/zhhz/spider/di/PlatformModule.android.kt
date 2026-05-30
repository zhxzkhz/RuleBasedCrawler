package com.zhhz.spider.di

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhhz.spider.db.AppDatabase
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

}