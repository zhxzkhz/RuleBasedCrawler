package com.zhhz.spider.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [BookEntity::class, ChapterEntity::class, RuleEntity::class, SessionEntity::class, BookGroupEntity::class, SettingsEntity::class],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun settingsDao(): SettingsDao
}

/**
 * v3 -> v4：创建 chapters 表
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        // ⚠️ 必须与 ChapterEntity 的最终 schema 完全一致（列名 / 主键 / 索引 / 外键）：
        // 旧实现建的是 (bookUrl,url) 复合主键 + name/index 列，与实体
        // （自增 id 主键 + title/indexNum + index_chapters_bookUrl 索引）不符，
        // Room 跑完迁移路径后做 schema 校验会直接抛
        // "Migration didn't properly handle: chapters"。
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `chapters` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookUrl` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `indexNum` INTEGER NOT NULL,
                `isRead` INTEGER NOT NULL,
                FOREIGN KEY(`bookUrl`) REFERENCES `books`(`detailUrl`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        // groupName 由 MIGRATION_9_10 追加，此处不要提前创建
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookUrl` ON `chapters` (`bookUrl`)")
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

/**
 * v11 -> v12：应用设置持久化到数据库
 * 新建 app_settings key-value 表，替代 JSON 文件存储
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `app_settings` (
                `key` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                PRIMARY KEY(`key`)
            )
        """.trimIndent())
    }
}
