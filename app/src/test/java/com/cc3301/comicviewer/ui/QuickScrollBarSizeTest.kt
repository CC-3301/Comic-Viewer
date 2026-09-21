package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的尺寸口径（票 #60 追加口径 AC8–AC10 + 批次 6 定版 **D7-A** 的 AC13，r2 评审后抓取带宽改 **13dp**）：
 * 长度下限 **64dp**、本体宽 **6dp**、无系统右缘 inset 时抓取带 **13dp**（= 离屏缘 7 + 本体 6，正好盖满本体）、
 * 本体**离屏幕右缘 7dp**（= 20dp 右留白的中点）、两档右留白**都是 20dp**。批次 6 把「离屏缘 4dp / 抓取带 12dp /
 * 网格档右留白 12dp」换成现在这几个数（真机验收未通过后维护者重定位置方案 D7-A）：滑条本体落在右留白正中、
 * 与封面之间留出空隙，不再「黏在封面上」。
 *
 * 为什么钉纯函数值而不是量布局：本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），而滑条只在
 * 「1.2 秒淡出」的窗口内存在、单测里起不了帧 ⇒ 真量本体盒子的路走不通（`EntryProgressBarTest` 同此限制，
 * 写明了原因）。因此按仓库既有做法钉**出货几何的同源函数值**（[quickScrollBarInset] / [quickScrollBarStripWidth]，
 * 界面侧就是拿这两个函数算离屏缘与 `Modifier.width`——不是钉某份默认常量），接线（dp → `toPx()` → `Modifier`）
 * 由真机验收覆盖。
 *
 * 判别力（每条都能失败）：本体宽改回 4dp / 下限退回 24dp / 水平右留白改回 12dp 即变红；
 * [quickScrollBarInset] 退回「固定 7dp」或写成别的式子时 [系统右缘 inset 撑宽空档后本体仍居中] 与默认空档下的
 * 7dp 断言变红；[quickScrollBarStripWidth] 丢了「+ 本体宽」或返回整个空档时 [本体整体落在抓取带内] 与
 * 两条「不侵入右留白」变红。r5 删掉了两条恒真断言（它们只把常量的定义式抄了一遍），换成对这两个函数的断言。
 *
 * 另有一条钉**纵向留白仍是 12dp**：批次 6 补记 #2 只改水平方向，横竖共用一个常量会把 #106 已验收的纵向几何拖走。
 * r4 起「居中」的口径：空档 = 「内容右缘 ↔ **屏幕**右缘」= `Scaffold` 右缘 inset + 内容右留白
 * （`BrowserScreen.kt` 现场算给 `QuickScrollBar`），真机「偏左」的因果正是按离屏缘固定定位时系统 inset 只撑宽了
 * 可见空档、滑条没跟着移到中间。
 *
 * 口径边界（写明，避免读成全覆盖）：AC14 文字里的「空隙约 **13dp**」与 AC13 的四个数不自洽——13dp 是
 * 「屏缘 → 本体**内缘**」的距离（7 + 6），而**本体与内容之间**的空隙是 20 − 7 − 6 = **7dp**。本用例按
 * AC13（20dp 留白 + 本体落在留白正中，两者相加才是留白宽）钉 7dp，见 [本体与内容之间留出 7dp 空隙]。
 */
class QuickScrollBarSizeTest {

    /** 无系统右缘 inset 时的空档 = 内容右留白（两档同值，见 [列表档右留白与网格档同为 20dp]） */
    private val defaultGap = GRID_CONTENT_PADDING_HORIZONTAL

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

    /**
     * AC13：默认空档（20dp 右留白）下离屏缘 **7dp**、抓取带 **13dp**——两条都由出货函数算出来
     * （[quickScrollBarInset] / [quickScrollBarStripWidth]），因此漂的是一个函数而不是某份默认常量。
     */
    @Test
    fun `默认空档下离屏缘 7dp、抓取带 13dp`() {
        assertEquals(7.dp, quickScrollBarInset(gap = defaultGap, barWidth = QUICK_SCROLL_BAR_WIDTH))
        assertEquals(
            13.dp,
            quickScrollBarStripWidth(gap = defaultGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
    }

    /**
     * AC13：本体落在右留白**正中**——本体两侧留白相等（空档 − 本体 = 两侧之和）。
     */
    @Test
    fun `本体落在 20dp 右留白正中`() {
        val inset = quickScrollBarInset(gap = defaultGap, barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertEquals(
            "两侧各 $inset，相加应等于空档 $defaultGap − 本体 $QUICK_SCROLL_BAR_WIDTH",
            defaultGap,
            inset * 2 + QUICK_SCROLL_BAR_WIDTH,
        )
    }

    /**
     * AC13 护栏（r2 评审要求恢复）：**本体要整个落在抓取带内**——带是命中区，本体比带宽的话最内侧那段
     * 压在带外，按下去就落到列表内容。出货带宽由 [quickScrollBarStripWidth] 给（= 离屏缘 + 本体），
     * 这条断言把它钉成等式：函数丢了「+ 本体宽」即变红。
     */
    @Test
    fun `本体整体落在抓取带内`() {
        val inset = quickScrollBarInset(gap = defaultGap, barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertEquals(
            "抓取带应等于离屏缘 $inset + 本体 $QUICK_SCROLL_BAR_WIDTH",
            inset + QUICK_SCROLL_BAR_WIDTH,
            quickScrollBarStripWidth(gap = defaultGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
        assertTrue(
            "离屏缘 $inset + 本体 $QUICK_SCROLL_BAR_WIDTH 应 ≤ 抓取带",
            inset + QUICK_SCROLL_BAR_WIDTH <=
                quickScrollBarStripWidth(gap = defaultGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
    }

    /** AC13 护栏：抓取带不得宽于网格档右留白，否则会压到封面/名称（函数若返回整个空档即变红） */
    @Test
    fun `抓取带不侵入网格档的右留白`() {
        val strip = quickScrollBarStripWidth(
            gap = GRID_CONTENT_PADDING_HORIZONTAL,
            barWidth = QUICK_SCROLL_BAR_WIDTH,
        )
        assertTrue(
            "抓取带 $strip 应 ≤ 网格档水平留白 $GRID_CONTENT_PADDING_HORIZONTAL",
            strip <= GRID_CONTENT_PADDING_HORIZONTAL,
        )
    }

    /**
     * 批次 6 补记 #2：**留白只改水平方向**——网格档纵向留白保持 **12dp** 原值（旧常量四边同值时会跟着变 20dp，
     * 而 `gridCellMaxHeight` 扣的就是纵向留白 ⇒ 格子高度上限少 16dp、牵动 #106 已验收的格内几何）。
     *
     * 判别力：两个分量再被合并成一个常量（或纵向被改成 20dp/其它值）即变红。
     */
    @Test
    fun `网格档留白横向 20dp 纵向保持 12dp`() {
        assertEquals(20.dp, GRID_CONTENT_PADDING_HORIZONTAL)
        assertEquals(12.dp, GRID_CONTENT_PADDING_VERTICAL)
    }

    /** AC13：列表档右留白与网格档水平留白同为 20dp（竖屏/横屏、两档统一），否则滑条在列表档里落不进留白 */
    @Test
    fun `列表档右留白与网格档同为 20dp`() {
        assertEquals(20.dp, LIST_ROW_END_PADDING)
        assertEquals(GRID_CONTENT_PADDING_HORIZONTAL, LIST_ROW_END_PADDING)
        val strip = quickScrollBarStripWidth(
            gap = LIST_ROW_END_PADDING,
            barWidth = QUICK_SCROLL_BAR_WIDTH,
        )
        assertTrue(
            "抓取带 $strip 应 ≤ 列表档右留白 $LIST_ROW_END_PADDING",
            strip <= LIST_ROW_END_PADDING,
        )
    }

    /**
     * AC14 的算术口径：本体与内容之间的空隙 = 空档 − 离屏缘 − 本体宽 = 20 − 7 − 6 = **7dp**
     * （旧口径 12 − 4 − 6 = 2dp，即维护者抱怨的「黏在封面上」）。
     *
     * 判别力：留白退回 12dp 或离屏缘退回固定 4dp 都变红。AC14 正文写的「约 13dp」= 屏缘到本体**内缘**
     * （7 + 6），不是本体与封面之间的空隙；两者只能取一个，本用例取 AC13 的几何。
     */
    @Test
    fun `本体与内容之间留出 7dp 空隙`() {
        assertEquals(
            7.dp,
            defaultGap -
                quickScrollBarInset(gap = defaultGap, barWidth = QUICK_SCROLL_BAR_WIDTH) -
                QUICK_SCROLL_BAR_WIDTH,
        )
    }

    /**
     * r4（真机「不够居中、偏左」的因果）：本体在**实际空档**里居中 = 两侧留白相等，
     * 而且这个空档包含 `Scaffold` 的右缘 inset（横屏三键导航把导航栏放右侧、挖孔）——
     * 空档被撑宽时滑条跟着往外移，不会停在离屏缘 7dp 上贴封面。
     *
     * 判别力：`quickScrollBarInset` 退回「离屏缘固定 7dp」（或写成 `gap − barWidth`）即变红。
     */
    @Test
    fun `系统右缘 inset 撑宽空档后本体仍居中`() {
        // 48dp 三键导航右侧 inset + 20dp 内容右留白 = 68dp 可见空档
        val gap = 68.dp
        val inset = quickScrollBarInset(gap = gap, barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertEquals(31.dp, inset)
        // 两侧留白相等（居中）
        assertEquals(gap, inset * 2 + QUICK_SCROLL_BAR_WIDTH)
        // 空档比本体还窄：夹到 0，不出现负偏移
        assertEquals(0.dp, quickScrollBarInset(gap = 4.dp, barWidth = QUICK_SCROLL_BAR_WIDTH))
    }

    /**
     * r5（登记 §17.3 的可见行为）：抓取带在撑宽的空档下**跟着变宽**（出货真值，不是默认常量）——
     * `(68 + 6) / 2 = 37dp`，仍整体落在空档内（不压封面），但「带内点击不传给条目」的那段因此变长。
     * 这条同时钉住「`QUICK_SCROLL_BAR_STRIP_WIDTH` 之类的默认常量不能代表出货带宽」。
     */
    @Test
    fun `抓取带随空档变宽但仍落在空档内`() {
        val gap = 68.dp
        val strip = quickScrollBarStripWidth(gap = gap, barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertEquals(37.dp, strip)
        assertTrue("抓取带 $strip 应 ≤ 空档 $gap", strip <= gap)
    }
}
