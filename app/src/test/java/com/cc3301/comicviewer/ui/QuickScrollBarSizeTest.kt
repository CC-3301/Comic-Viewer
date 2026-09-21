package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的尺寸口径（票 #60 追加口径 AC8–AC10 + 批次 6 定版 D7-A 的 AC13 + r6 的位置口径）：
 * 长度下限 **64dp**、本体宽 **6dp**、胶囊**中心**距**屏幕**右缘 **5dp**（本体右缘离屏缘 2dp、抓取带 8dp）、
 * 两档右留白**都是 20dp**。
 *
 * 为什么钉纯函数值而不是量布局：本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），而滑条只在
 * 「1.2 秒淡出」的窗口内存在、单测里起不了帧 ⇒ 真量本体盒子的路走不通（`EntryProgressBarTest` 同此限制，
 * 写明了原因）。因此按仓库既有做法钉**出货几何的同源函数值**（[quickScrollBarEdgeGap] /
 * [quickScrollBarStripWidth]，界面侧就是拿这两个函数算 `Modifier.offset` 的横向分量与 `Modifier.width`——
 * 不是钉某份默认常量），接线（dp → `toPx()` → `Modifier`）由真机验收覆盖。
 *
 * 判别力（每条都能失败）：本体宽改回 4dp / 下限退回 24dp / 水平右留白改回 12dp / 中心距屏缘改回 7dp 即变红；
 * [quickScrollBarEdgeGap] 写成写死的 `2.dp`（而不是由中心距与本体宽推出）时「换一个本体宽 ⇒ 离屏缘跟着变」
 * 变红；[quickScrollBarStripWidth] 丢了「+ 本体宽」时 [本体整体落在抓取带内] 与两条「不侵入右留白」变红。
 *
 * 位置口径（r6，维护者第二次真机反馈「依旧偏左（20.jpg）」+「用5dp」）：本体**贴屏幕侧固定**——
 * 胶囊中心离屏缘 [QUICK_SCROLL_BAR_CENTER_GAP]（5dp）。r4–r5 取「内容右缘 ↔ 屏幕右缘」的空档做居中，
 * 空档被系统右缘 inset 撑宽时本体跟着往外挪，观感上贴向封面 ⇒ 本轮不再让空档参与定位
 * （[quickScrollBarEdgeGap] 因此**没有**空档参数，这是有意的：`Scaffold` 右缘 inset 只留在本体外侧）。
 * 空档只用来判「抓取带是否侵入右留白」。
 */
class QuickScrollBarSizeTest {

    /** 内容侧的空档 = 两档右留白（无系统右缘 inset）；系统 inset 只会把它撑得更宽（见外侧用例） */
    private val contentGap = GRID_CONTENT_PADDING_HORIZONTAL

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
     * r6 位置口径：胶囊**中心**距**屏幕**右缘 5dp（维护者原话「用5dp」；r4–r5 的实测值是 9.7dp）。
     *
     * 关系式（不是把常量抄一遍）：中心距 = 本体右缘离屏缘 + 本体宽 / 2，由出货函数
     * [quickScrollBarEdgeGap] 给出——它若被改成写死值，两边就不再是同一个式子。
     */
    @Test
    fun `胶囊中心距屏幕右缘 5dp`() {
        assertEquals(5.dp, QUICK_SCROLL_BAR_CENTER_GAP)
        assertEquals(
            "中心距 = 离屏缘 + 本体宽 / 2",
            QUICK_SCROLL_BAR_CENTER_GAP,
            quickScrollBarEdgeGap(barWidth = QUICK_SCROLL_BAR_WIDTH) + QUICK_SCROLL_BAR_WIDTH / 2,
        )
    }

    /**
     * r6：本体**右缘**距**屏幕**右缘 2dp（= 5 − 6/2）；换一个本体宽，离屏缘跟着变——这条就是「离屏缘由
     * 中心距推出、不是写死的 2dp」的判据。
     */
    @Test
    fun `本体右缘离屏缘 2dp 且由中心距推出`() {
        assertEquals(2.dp, quickScrollBarEdgeGap(barWidth = QUICK_SCROLL_BAR_WIDTH))
        assertEquals("本体 4dp 时离屏缘应是 5 − 2 = 3dp", 3.dp, quickScrollBarEdgeGap(barWidth = 4.dp))
    }

    /**
     * r6：抓取带宽 **8dp**（= 离屏缘 2 + 本体 6，正好盖满本体）——批次 6 的 13dp 随位置口径一起改小
     * （本体占屏缘 2–8dp，带必须跟到同样的 8dp）。
     */
    @Test
    fun `抓取带 8dp`() {
        assertEquals(8.dp, quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH))
    }

    /**
     * AC13 护栏（r2 评审要求恢复）：**本体要整个落在抓取带内**——带是命中区，本体比带宽的话最内侧那段
     * 压在带外，按下去就落到列表内容。出货带宽由 [quickScrollBarStripWidth] 给（= 离屏缘 + 本体），
     * 这条断言把它钉成等式：函数丢了「+ 本体宽」即变红。
     */
    @Test
    fun `本体整体落在抓取带内`() {
        val edgeGap = quickScrollBarEdgeGap(barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertEquals(
            "抓取带应等于离屏缘 $edgeGap + 本体 $QUICK_SCROLL_BAR_WIDTH",
            edgeGap + QUICK_SCROLL_BAR_WIDTH,
            quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
        assertTrue(
            "离屏缘 $edgeGap + 本体 $QUICK_SCROLL_BAR_WIDTH 应 ≤ 抓取带",
            edgeGap + QUICK_SCROLL_BAR_WIDTH <=
                quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
    }

    /** AC13 护栏：抓取带不得宽于网格档右留白，否则会压到封面/名称（带宽改大或留白改小即变红） */
    @Test
    fun `抓取带不侵入网格档的右留白`() {
        val strip = quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertTrue(
            "抓取带 $strip 应 ≤ 网格档水平留白 $contentGap",
            strip <= contentGap,
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
        val strip = quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertTrue(
            "抓取带 $strip 应 ≤ 列表档右留白 $LIST_ROW_END_PADDING",
            strip <= LIST_ROW_END_PADDING,
        )
    }

    /**
     * 位置口径的真值：本体占屏缘 2–8dp，与内容（右留白 20dp）之间留出 20 − 8 = **12dp** 空隙
     * （批次 6 的 7dp 随位置口径一起更新；旧口径 12 − 4 − 6 = 2dp 就是维护者当时抱怨的「黏在封面上」）。
     *
     * 判别力：留白退回 12dp、或本体右缘离屏缘退回 7dp 都变红。
     */
    @Test
    fun `本体与内容之间留出 12dp 空隙`() {
        assertEquals(
            12.dp,
            contentGap - quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
    }

    /**
     * r6（真机第二次「依旧偏左」的修法）：**有系统右缘 inset 时，本体右缘距屏幕右缘恒为 2dp**。
     *
     * 空档 = 系统右缘 inset + 内容右留白（横屏三键导航把导航栏放右侧、挖孔时是 48 + 20 = 68dp）。
     * 定位只认屏幕侧（[quickScrollBarEdgeGap] 没有空档参数），因此两种空档下装配值相同：本体不跟着 inset 往外挪
     * ——r4–r5 拿空档算离屏缘（20dp ⇒ 7dp、68dp ⇒ 31dp），inset 越大越贴封面，这正是「偏左」的成因。
     * 空档这一侧也要成立：带仍不宽于空档 ⇒ 本体留在留白内、不压封面与名称。
     */
    @Test
    fun `有系统右缘 inset 时本体右缘距屏幕右缘恒为 2dp`() {
        val scenarios = listOf(
            "无系统右缘 inset" to contentGap,
            "48dp 三键导航右侧 inset" to contentGap + 48.dp,
        )
        scenarios.forEach { (name, gap) ->
            assertEquals(
                "$name（空档 $gap）下本体右缘离屏缘",
                2.dp,
                quickScrollBarEdgeGap(barWidth = QUICK_SCROLL_BAR_WIDTH),
            )
            assertEquals(
                "$name（空档 $gap）下抓取带宽不随空档变",
                8.dp,
                quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH),
            )
            assertTrue(
                "$name（空档 $gap）下抓取带应 ≤ 空档",
                quickScrollBarStripWidth(barWidth = QUICK_SCROLL_BAR_WIDTH) <= gap,
            )
        }
    }
}
