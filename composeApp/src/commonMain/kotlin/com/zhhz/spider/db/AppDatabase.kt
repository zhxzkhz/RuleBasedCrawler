package com.zhhz.spider.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [BookEntity::class, ChapterEntity::class, RuleEntity::class, SessionEntity::class, BookGroupEntity::class], // 必须在这里添加新类
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookGroupDao(): BookGroupDao
}

/**
 * v3 -> v4：创建 chapters 表
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
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

/**
 * v4 -> v5：历史遗留版本，无已知 schema 变更（占位）
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        // 无 schema 变更，仅桥接版本号
    }
}

/**
 * v5 -> v6：历史遗留版本，无已知 schema 变更（占位）
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        // 无 schema 变更，仅桥接版本号
    }
}

/**
 * v6 -> v7：历史遗留版本，无已知 schema 变更（占位）
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        // 无 schema 变更，仅桥接版本号
    }
}

/**
 * v7 -> v8：书架自定义分组
 * 1. books 表新增 groupFlags 位掩码列（默认 0 = 未分组）
 * 2. 新建 book_groups 分组定义表（mask 唯一索引）
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `books` ADD COLUMN `groupFlags` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `book_groups` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `mask` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_book_groups_mask` ON `book_groups` (`mask`)")
    }
}

/**
 * v8 -> v9：修复历史遗留数据。
 * 早期搜索页/详情页加入书架时 Book.inLibrary 默认 false 落库，导致按 inLibrary 过滤后书籍从书架消失；
 * 此前不存在合法的"阅读历史"软删除数据，统一归一为在架。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("UPDATE `books` SET `inLibrary` = 1 WHERE `inLibrary` = 0")
    }
}

/**
 * v9 -> v10：目录卷名支持
 * 1. chapters 表新增 groupName 字段，默认空字符串
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `chapters` ADD COLUMN `groupName` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v10 -> v11：分组自动更新支持
 * 1. book_groups 表新增 isAutoUpdate 字段，默认 false (0)
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `book_groups` ADD COLUMN `isAutoUpdate` INTEGER NOT NULL DEFAULT 0")
    }
}
