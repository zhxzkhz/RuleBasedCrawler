package com.zhhz.spider.ui.widget.novel

import kotlin.test.Test
import kotlin.test.assertEquals

class NovelPagingLogicTest {

    // ── 章节边界起始位置 ──

    @Test
    fun movingToPreviousChapterOpensAtLastPage() {
        assertEquals(ChapterStartPosition.Last, chapterStartPosition(-1))
    }

    @Test
    fun movingToNextChapterOrStayingOpensAtFirstPage() {
        assertEquals(ChapterStartPosition.First, chapterStartPosition(1))
        assertEquals(ChapterStartPosition.First, chapterStartPosition(0))
    }

    @Test
    fun initialPageIndexUsesLastPageWhenOpenAtEnd() {
        assertEquals(9, initialChapterPageIndex(openAtEnd = true, savedProgress = 0, lastIndex = 9))
        assertEquals(0, initialChapterPageIndex(openAtEnd = false, savedProgress = 0, lastIndex = 9))
        assertEquals(4, initialChapterPageIndex(openAtEnd = false, savedProgress = 4, lastIndex = 9))
    }

    @Test
    fun initialPageIndexClampsOutOfRangeProgress() {
        assertEquals(9, initialChapterPageIndex(openAtEnd = false, savedProgress = 99, lastIndex = 9))
        assertEquals(0, initialChapterPageIndex(openAtEnd = false, savedProgress = -1, lastIndex = 9))
        assertEquals(0, initialChapterPageIndex(openAtEnd = true, savedProgress = 5, lastIndex = 0))
    }

    // ── 翻页目标与章节边界 ──

    @Test
    fun pageTurnInsideBoundsTargetsAdjacentPage() {
        assertEquals(PageTurn.ToPage(5), pageTurnTarget(currentPage = 4, forward = true, pageCount = 10))
        assertEquals(PageTurn.ToPage(3), pageTurnTarget(currentPage = 4, forward = false, pageCount = 10))
        assertEquals(PageTurn.ToPage(1), pageTurnTarget(currentPage = 0, forward = true, pageCount = 10))
        assertEquals(PageTurn.ToPage(0), pageTurnTarget(currentPage = 1, forward = false, pageCount = 10))
        assertEquals(PageTurn.ToPage(9), pageTurnTarget(currentPage = 8, forward = true, pageCount = 10))
    }

    @Test
    fun pageTurnBeyondBoundsCrossesChapterBoundary() {
        assertEquals(PageTurn.ToNextChapter, pageTurnTarget(currentPage = 9, forward = true, pageCount = 10))
        assertEquals(PageTurn.ToPreviousChapter, pageTurnTarget(currentPage = 0, forward = false, pageCount = 10))
        assertEquals(PageTurn.ToPreviousChapter, pageTurnTarget(currentPage = -1, forward = false, pageCount = 10))
    }

    // ── 点击区域 ──

    @Test
    fun tapZonesPageOnLeftAndRightThirds() {
        assertEquals(TapZoneAction.Previous, tapZoneAction(offsetX = 50f, offsetY = 100f, screenWidth = 900f, screenHeight = 1600f))
        assertEquals(TapZoneAction.Next, tapZoneAction(offsetX = 850f, offsetY = 100f, screenWidth = 900f, screenHeight = 1600f))
        // 左右区域不受高度限制：底部左/右三分之一同样翻页
        assertEquals(TapZoneAction.Previous, tapZoneAction(offsetX = 50f, offsetY = 1500f, screenWidth = 900f, screenHeight = 1600f))
        assertEquals(TapZoneAction.Next, tapZoneAction(offsetX = 850f, offsetY = 1500f, screenWidth = 900f, screenHeight = 1600f))
    }

    @Test
    fun tapMiddleUpperAreaTogglesMenu() {
        assertEquals(TapZoneAction.ToggleMenu, tapZoneAction(offsetX = 450f, offsetY = 100f, screenWidth = 900f, screenHeight = 1600f))
    }

    @Test
    fun tapBottomMiddleDoesNothing() {
        // 0.75 * 1600 = 1200，底部中间区域无动作
        assertEquals(TapZoneAction.None, tapZoneAction(offsetX = 450f, offsetY = 1400f, screenWidth = 900f, screenHeight = 1600f))
    }
}
