package com.zhhz.spider.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 自定义书架分组定义表。
 *
 * 分组采用二进制位掩码方案：每创建一个新分组分配一个 mask（2, 4, 8, 16, 32 ...依次翻倍），
 * 书籍通过 books.groupFlags 中对应位的 0/1 表示是否属于该分组，一本书可同时属于多个分组。
 * bit0 (数值 1) 保留不分配，因此 Long 最多支持 62 个分组。
 */
@Serializable
@Entity(tableName = "book_groups", indices = [Index(value = ["mask"], unique = true)])
data class BookGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    // 该分组对应的位掩码值（2^n）
    @ColumnInfo(defaultValue = "0")
    val mask: Long = 0,

    // 是否允许自动更新（默认为 false）
    @ColumnInfo(defaultValue = "0")
    val isAutoUpdate: Boolean = false
)

/** 位掩码工具：分组从 2 开始二倍递增分配 */
object BookGroupFlags {
    /** 第一个可分配的掩码（bit0=1 保留） */
    const val FIRST_MASK: Long = 2L

    /** Long 正数区间内 bit1..bit62 共 62 个可用位 */
    const val MAX_GROUPS: Int = 62

    /** 书籍 flags 是否包含该分组 */
    fun contains(flags: Long, mask: Long): Boolean = flags and mask != 0L

    /** 从已占用的掩码集合中找出下一个可用掩码；已满返回 null */
    fun nextMask(usedMasks: Set<Long>): Long? {
        var mask = FIRST_MASK
        while (mask > 0) {
            if (mask !in usedMasks) return mask
            mask = mask shl 1
        }
        return null
    }
}
