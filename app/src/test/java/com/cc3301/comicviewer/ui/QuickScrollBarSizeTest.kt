package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的尺寸口径（票 #60 追加口径 AC8–AC10，2026-09-21 真机反馈「滑条太短、不容易碰到」）：
 * 长度下限 **64dp**、本体宽 **6dp**、抓取带 **12dp**、离屏幕右缘 **4dp**。
 *
 * 为什么钉常量而不是量布局：本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），而滑条只在
 * 「1.2 秒淡出」的窗口内存在、单测里起不了帧 ⇒ 真量本体盒子的路走不通（`EntryProgressBarTest` 同此限制，
 * 写明了原因）。因此按仓库既有做法钉**尺寸常量值**，接线（常量 → `toPx()` → `Modifier.width/height`）由真机验收覆盖。
 *
 * 判别力：任一变回旧值（下限 24dp / 本体 4dp）即变红；抓取带若改宽到超过网格档右留白
 * `GRID_CONTENT_PADDING`（AC10「不侵入右留白」）也变红——这是原先缺的那条护栏断言（两个常量各自独立）。
 */
class QuickScrollBarSizeTest {

    /** AC8：1000 条目时比例算出的长度 ≈ 7dp，必须由下限兜到 64dp（旧下限 24dp 正是「太短」的根因） */
    @Test
    fun `长度下限是 64dp`() {
        assertEquals(64.dp, QUICK_SCROLL_BAR_MIN_LENGTH)
    }

    /** AC10：本体宽 6dp（旧 4dp 太细，看不见也碰不准） */
    @Test
    fun `本体是 6dp`() {
        assertEquals(6.dp, QUICK_SCROLL_BAR_WIDTH)
    }

    /** AC10：抓取带保持 12dp、离屏幕右缘保持 4dp（方案 C「加宽抓取带」已否，不侵入右留白） */
    @Test
    fun `抓取带 12dp 离屏缘 4dp`() {
        assertEquals(12.dp, QUICK_SCROLL_BAR_STRIP_WIDTH)
        assertEquals(4.dp, QUICK_SCROLL_BAR_INSET)
    }

    /** 本体要落在抓取带内（离缘 4dp + 本体 6dp = 10dp ≤ 抓取带 12dp）：否则看得见、抓不着 */
    @Test
    fun `本体落在抓取带内`() {
        assertTrue(
            "离屏缘 $QUICK_SCROLL_BAR_INSET + 本体 $QUICK_SCROLL_BAR_WIDTH 应 ≤ 抓取带 $QUICK_SCROLL_BAR_STRIP_WIDTH",
            QUICK_SCROLL_BAR_INSET + QUICK_SCROLL_BAR_WIDTH <= QUICK_SCROLL_BAR_STRIP_WIDTH,
        )
    }

    /** AC10 护栏：抓取带不得宽于网格档的右留白，否则会压到封面（列表档留白 16dp 更宽，网格档是紧的那一档） */
    @Test
    fun `抓取带不侵入网格档的右留白`() {
        assertTrue(
            "抓取带 $QUICK_SCROLL_BAR_STRIP_WIDTH 应 ≤ 网格档右留白 $GRID_CONTENT_PADDING",
            QUICK_SCROLL_BAR_STRIP_WIDTH <= GRID_CONTENT_PADDING,
        )
    }
}
