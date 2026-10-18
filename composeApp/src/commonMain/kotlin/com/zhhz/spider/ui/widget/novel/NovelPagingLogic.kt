package com.zhhz.spider.ui.widget.novel

/**
 * 章节打开时的起始页语义：
 * - 上一章（向前翻越界）：从末页进入，衔接上一章结尾
 * - 下一章 / 目录跳转 / 正常恢复：从首页或上次进度进入
 */
enum class ChapterStartPosition { First, Last }

/** 章节边界移动的起始位置：offset < 0（上一章）= 末页，否则首页 */
fun chapterStartPosition(offset: Int): ChapterStartPosition =
    if (offset < 0) ChapterStartPosition.Last else ChapterStartPosition.First

/** 根据 openAtEnd 标记与已存进度计算 pager 初始页 */
fun initialChapterPageIndex(openAtEnd: Boolean, savedProgress: Int, lastIndex: Int): Int =
    (if (openAtEnd) lastIndex else savedProgress).coerceIn(0, lastIndex.coerceAtLeast(0))

/** 翻页目标：越界时转为章节边界动作 */
sealed interface PageTurn {
    data class ToPage(val page: Int) : PageTurn
    data object ToPreviousChapter : PageTurn
    data object ToNextChapter : PageTurn
}

fun pageTurnTarget(currentPage: Int, forward: Boolean, pageCount: Int): PageTurn {
    val target = currentPage + if (forward) 1 else -1
    return when {
        pageCount <= 0 -> PageTurn.ToNextChapter
        target in 0 until pageCount -> PageTurn.ToPage(target)
        forward -> PageTurn.ToNextChapter
        else -> PageTurn.ToPreviousChapter
    }
}

/** 阅读页点击区域：左右 1/3 翻页，中上部呼出菜单，底部中间无动作 */
sealed interface TapZoneAction {
    data object Previous : TapZoneAction
    data object Next : TapZoneAction
    data object ToggleMenu : TapZoneAction
    data object None : TapZoneAction
}

fun tapZoneAction(offsetX: Float, offsetY: Float, screenWidth: Float, screenHeight: Float): TapZoneAction = when {
    offsetY <= screenHeight * 0.75f && offsetX >= screenWidth / 3 && offsetX <= screenWidth * 2 / 3 -> TapZoneAction.ToggleMenu
    offsetX < screenWidth / 3 -> TapZoneAction.Previous
    offsetX > screenWidth * 2 / 3 -> TapZoneAction.Next
    else -> TapZoneAction.None
}
