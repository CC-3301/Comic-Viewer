package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的尺寸口径（票 #60 追加口径 AC8–AC10 + 批次 6 定版 **D7-A** 的 AC13）：
 * 长度下限 **64dp**、本体宽 **6dp**、抓取带 **12dp**、本体**离屏幕右缘 7dp**（= 20dp 右留白的中点）、
 * 两档右留白**都是 20dp**。批次 6 把「离屏缘 4dp / 网格档右留白 12dp」换成现在这四个数（真机验收未通过后
 * 维护者重定位置方案 D7-A）：滑条本体落在右留白正中、与封面之间留出空隙，不再「黏在封面上」。
 *
 * 为什么钉常量而不是量布局：本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），而滑条只在
 * 「1.2 秒淡出」的窗口内存在、单测里起不了帧 ⇒ 真量本体盒子的路走不通（`EntryProgressBarTest` 同此限制，
 * 写明了原因）。因此按仓库既有做法钉**尺寸常量值**，接线（常量 → `toPx()` → `Modifier.width/height`）由真机验收覆盖。
 *
 * 判别力：任一变回旧值（下限 24dp / 本体 4dp / 离屏缘 4dp / 右留白 12dp）即变红；抓取带若改宽到超过右留白
 * [GRID_CONTENT_PADDING]（AC13「抓取带不侵入右留白」）也变红——这是原先缺的那条护栏断言（留白与抓取带各自独立）。
 *
 * 口径边界（写明，避免读成全覆盖）：AC14 文字里的「空隙约 **13dp**」与 AC13 的四个数不自洽——13dp 是
 * 「屏缘 → 本体**内缘**」的距离（7 + 6），而**本体与内容之间**的空隙是 20 − 7 − 6 = **7dp**。本用例按
 * AC13（20dp 留白 + 本体落在留白正中，两者相加才是留白宽）钉 7dp，见 [本体与内容之间留出 7dp 空隙]。
 */
class QuickScrollBarSizeTest {

    /** AC8：1000 条目时比例算出的长度 ≈ 7dp，必须由下限兜到 64dp（旧下限 24dp 正是「太短」的根因） */
    @Test
    fun `长度下限是 64dp`() {
        assertEquals(64.dp, QUICK_SCROLL_BAR_MIN_LENGTH)
    }

    /** AC13：本体宽 6dp（旧 4dp 太细，看不见也碰不准），批次 6 保持 6dp */
    @Test
    fun `本体是 6dp`() {
        assertEquals(6.dp, QUICK_SCROLL_BAR_WIDTH)
    }

    /** AC13：抓取带保持 12dp、离屏幕右缘改成 7dp（= 20dp 右留白的中点） */
    @Test
    fun `抓取带 12dp 离屏缘 7dp`() {
        assertEquals(12.dp, QUICK_SCROLL_BAR_STRIP_WIDTH)
        assertEquals(7.dp, QUICK_SCROLL_BAR_INSET)
    }

    /**
     * AC13：本体落在右留白**正中**——「离屏缘 ×2 + 本体宽 = 右留白」。
     *
     * 这条取代了旧口径的「离屏缘 4dp + 本体 6dp ≤ 抓取带 12dp」：本体居中于 20dp 留白后落在离屏缘
     * 7–13dp 处，而抓取带只盖到 12dp，本体最里侧 1dp 落在带外（批次 6 的取舍：抓取带宽度与「不侵入右留白」
     * 都要保持不变，本体又要居中）。抓取带仍覆盖本体的 5dp/6dp，按带拖动够用。
     */
    @Test
    fun `本体落在 20dp 右留白正中`() {
        assertEquals(
            "离屏缘 $QUICK_SCROLL_BAR_INSET ×2 + 本体 $QUICK_SCROLL_BAR_WIDTH 应等于右留白 $GRID_CONTENT_PADDING",
            GRID_CONTENT_PADDING.value,
            QUICK_SCROLL_BAR_INSET.value * 2 + QUICK_SCROLL_BAR_WIDTH.value,
            0.001f,
        )
    }

    /** AC13 护栏：抓取带不得宽于右留白，否则会压到封面/名称（网格档的右留白就是那条 20dp） */
    @Test
    fun `抓取带不侵入网格档的右留白`() {
        assertTrue(
            "抓取带 $QUICK_SCROLL_BAR_STRIP_WIDTH 应 ≤ 网格档右留白 $GRID_CONTENT_PADDING",
            QUICK_SCROLL_BAR_STRIP_WIDTH <= GRID_CONTENT_PADDING,
        )
    }

    /** AC13：列表档右留白与网格档同为 20dp（竖屏/横屏、两档统一），否则滑条在列表档里落不进留白 */
    @Test
    fun `列表档右留白与网格档同为 20dp`() {
        assertEquals(20.dp, LIST_ROW_END_PADDING)
        assertEquals(GRID_CONTENT_PADDING, LIST_ROW_END_PADDING)
        assertTrue(
            "抓取带 $QUICK_SCROLL_BAR_STRIP_WIDTH 应 ≤ 列表档右留白 $LIST_ROW_END_PADDING",
            QUICK_SCROLL_BAR_STRIP_WIDTH <= LIST_ROW_END_PADDING,
        )
    }

    /**
     * AC14 的算术口径：本体与内容之间的空隙 = 右留白 − 离屏缘 − 本体宽 = 20 − 7 − 6 = **7dp**
     * （旧口径 12 − 4 − 6 = 2dp，即维护者抱怨的「黏在封面上」）。
     *
     * 判别力：留白退回 12dp 或离屏缘退回 4dp 都变红。AC14 正文写的「约 13dp」= 屏缘到本体**内缘**
     * （7 + 6），不是本体与封面之间的空隙；两者只能取一个，本用例取 AC13 的几何。
     */
    @Test
    fun `本体与内容之间留出 7dp 空隙`() {
        assertEquals(
            7.dp,
            GRID_CONTENT_PADDING - QUICK_SCROLL_BAR_INSET - QUICK_SCROLL_BAR_WIDTH,
        )
    }
}
