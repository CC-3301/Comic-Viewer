package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 滑条**拖动期间**本体位置的行内比例：拖动支路把落点的行内偏移折算成「行内已滚过比例」
 * （与屏内比例同一个换算），让本体在两条目之间也连续跟手，而不是按条目一格一格跳。
 *
 * 为什么钉这个纯函数而不是量渲染：本仓库没有 compose-ui-test 基建（同 [QuickScrollBarThumbAppearanceTest]
 * 的限制），拖动支路的几何取数在 `ui/QuickScrollBarOverlay.kt` 里由这个函数给出，本体位置
 * = 几何进度 × 行程，直接由它决定。
 *
 * **判别力**：拖动支路若把行内比例归零（位置只由整数行索引给出），半行落点与整行落点给出的比例相同，
 * [半行落点的比例落在两条目之间] 与 [不同行内偏移给出不同比例] 一起变红。
 */
class QuickScrollBarDragFractionTest {

    /** 行距 100px 下的半行落点：比例 0.5（位置落在两条目中间，不是行首） */
    @Test
    fun `半行落点的比例落在两条目之间`() {
        assertEquals(0.5f, quickScrollBarDragScrollFraction(rowOffsetPx = 50, rowExtentPx = 100), 1e-6f)
    }

    /** 行首落点：比例 0（与屏内比例同口径） */
    @Test
    fun `行首落点的比例为零`() {
        assertEquals(0f, quickScrollBarDragScrollFraction(rowOffsetPx = 0, rowExtentPx = 100), 1e-6f)
    }

    /** 行距取不到（首帧未布局、≤0）：退回 0，不除零 */
    @Test
    fun `行距取不到时退回零`() {
        assertEquals(0f, quickScrollBarDragScrollFraction(rowOffsetPx = 50, rowExtentPx = 0), 1e-6f)
        assertEquals(0f, quickScrollBarDragScrollFraction(rowOffsetPx = 50, rowExtentPx = -100), 1e-6f)
    }

    /** 行内偏移不同 ⇒ 比例不同：旧口径（恒 0）下两个落点的本体位置重合，本条红 */
    @Test
    fun `不同行内偏移给出不同比例`() {
        assertNotEquals(
            quickScrollBarDragScrollFraction(rowOffsetPx = 25, rowExtentPx = 100),
            quickScrollBarDragScrollFraction(rowOffsetPx = 0, rowExtentPx = 100),
        )
    }
}
