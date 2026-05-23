package com.zhhz.spider.di

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import coil3.PlatformContext
import com.zhhz.spider.db.AppDatabase
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
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration(true)
            .build()
    }
    
    single(named("cacheDir")) {
        File(System.getProperty("java.io.tmpdir"), "spider_snapshots").apply {
            if (!exists()) mkdirs()
        }
    }

    single<PlatformContext> { PlatformContext.INSTANCE }

    // 注入桌面端专有的 Swing 编辑器
    //single<EditorProvider> { DesktopEditorProvider() }
}


val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        // 执行创建 chapters 表的 SQL 语句
        // 注意：字段名和类型必须与 ChapterEntity 严格对应
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `chapters` (
                `bookUrl` TEXT NOT NULL, 
                `url` TEXT NOT NULL, 
                `name` TEXT NOT NULL, 
                `index` INTEGER NOT NULL, 
                `isRead` INTEGER NOT NULL DEFAULT 0, 
                PRIMARY KEY(`bookUrl`, `url`), 
                FOREIGN KEY(`bookUrl`) REFERENCES `books`(`detailUrl`) ON UPDATE NO ACTION ON DELETE CASCADE 
            )
        """.trimIndent())
    }
}