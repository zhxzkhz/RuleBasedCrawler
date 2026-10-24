package com.zhhz.spider.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分组位掩码分配回归测试。
 *
 * 设计：bit0(数值 1) 保留不分配，从 2 开始二倍递增，Long 正数区间共 62 个可用位。
 * 分配器若从 1 开始分配会与"未分组=0"语义冲突；越过符号位会产生负数掩码。
 */
class BookGroupFlagsTest {

    @Test
    fun nextMaskStartsAtTwoAndDoubles() {
        val used = mutableSetOf<Long>()
        assertEquals(2L, BookGroupFlags.nextMask(used))
        used += 2L
        assertEquals(4L, BookGroupFlags.nextMask(used))
        used += 4L
        assertEquals(8L, BookGroupFlags.nextMask(used))
    }

    @Test
    fun nextMaskSkipsUsedMasksAndFillsHoles() {
        // 2、8 已占用：应跳过 2 分配 4；2、4、8 占用后应分配 16
        assertEquals(4L, BookGroupFlags.nextMask(setOf(2L, 8L)))
        assertEquals(16L, BookGroupFlags.nextMask(setOf(2L, 4L, 8L)))
    }

    @Test
    fun nextMaskExhaustsAfterSixtyTwoGroups() {
        val used = mutableSetOf<Long>()
        var allocated = 0
        while (true) {
            val mask = BookGroupFlags.nextMask(used) ?: break
            used += mask
            allocated++
            // 防御：掩码必须是正数（越过符号位即分配器出错）
            assertTrue(mask > 0, "分配出非正数掩码: $mask")
        }

        assertEquals(BookGroupFlags.MAX_GROUPS, allocated)
        assertNull(BookGroupFlags.nextMask(used))
    }

    @Test
    fun containsChecksBitIntersection() {
        val flags = 2L or 8L // 属于分组 2 和 8
        assertTrue(BookGroupFlags.contains(flags, 2L))
        assertTrue(BookGroupFlags.contains(flags, 8L))
        assertFalse(BookGroupFlags.contains(flags, 4L))
        assertFalse(BookGroupFlags.contains(0L, 2L))
    }
}
