package com.zhhz.spider.ui.widget.novel

import kotlin.test.Test
import kotlin.test.assertEquals

class NovelPageLayoutTest {

    @Test
    fun safeDrawingInsetsAreRemovedFromLayoutSize() {
        assertEquals(900, calculateNovelLayoutHeight(1000, 40f, 60f))
        assertEquals(0, calculateNovelLayoutHeight(80, 40f, 60f))
        assertEquals(920, calculateNovelLayoutWidth(1000, 30f, 50f))
        assertEquals(0, calculateNovelLayoutWidth(60, 30f, 50f))
    }

    @Test
    fun candidateTrailingSpaceDoesNotForceAnEarlyPageBreak() {
        assertEquals(
            80f,
            novelPageRequiredHeight(
                usedHeight = 60f,
                entryHeight = 30f,
                removableTrailingSpace = 10f
            )
        )
        assertEquals(
            90f,
            novelPageRequiredHeight(
                usedHeight = 60f,
                entryHeight = 30f,
                removableTrailingSpace = 0f
            )
        )
    }

    @Test
    fun remainingHeightIsDistributedAcrossInternalGaps() {
        val increment = verticalJustificationIncrement(
            itemHeights = listOf(20f, 20f, 20f),
            gapHeights = listOf(5f, 10f),
            maxContentHeight = 95f,
            justify = true
        )

        assertEquals(10f, increment)
    }

    @Test
    fun lastPageAndSingleItemPageKeepConfiguredSpacing() {
        assertEquals(
            0f,
            verticalJustificationIncrement(
                itemHeights = listOf(20f, 20f),
                gapHeights = listOf(5f),
                maxContentHeight = 60f,
                justify = false
            )
        )
        assertEquals(
            0f,
            verticalJustificationIncrement(
                itemHeights = listOf(20f),
                gapHeights = emptyList(),
                maxContentHeight = 60f,
                justify = true
            )
        )
    }

    @Test
    fun overflowingPageDoesNotCreateNegativeSpacing() {
        assertEquals(
            0f,
            verticalJustificationIncrement(
                itemHeights = listOf(40f, 40f),
                gapHeights = listOf(10f),
                maxContentHeight = 80f,
                justify = true
            )
        )
    }
}
