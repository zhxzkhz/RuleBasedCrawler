package com.zhhz.spider.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rule_sessions")
data class SessionEntity(
    @PrimaryKey val ruleId: String,  // 对应规则的 ID
    val tokenValue: String,          // 提取到的 Header 原始值
    val expireAt: Long = -1,         // 失效时间戳（-1 代表永不失效直到报错）
    val lastUpdated: Long = System.currentTimeMillis()
)