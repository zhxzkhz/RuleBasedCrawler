package com.zhhz.spider.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val jsonContent: String,
    val updateTime: Long = System.currentTimeMillis()
)