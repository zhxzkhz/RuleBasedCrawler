package com.zhhz.spider.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class,ChapterEntity::class,RuleEntity::class, SessionEntity::class], // 必须在这里添加新类
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
}