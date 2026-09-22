package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的尺寸口径（票 #60 追加口径 AC8–AC10 + 批次 6 定版 D7-A 的 AC13 + 批次 9 r2 的位置口径）：
 * 长度下限 **64dp**、本体宽 **6dp**、胶囊**中心**距**屏幕**右缘 **10dp**（两档右留白 20dp 下：本体右缘离屏缘
 * 7dp、抓取带 13dp）、两档右留白**都是 20dp**。
 *
 * 批次 9（维护者第三次真机反馈「滑条位置偏右」）：r6 的「中心距屏缘 5dp」→ **7dp**，抓取带随之 8dp → 10dp。
 * 批次 9 r2（维护者第四轮真机反馈「位置仍偏右」）：**7dp → 10dp**，抓取带 10dp → **13dp**；
 * 右留白**不动**（仍 20dp）⇒ 与内容的空隙由 10dp 变 **7dp**（几何：空隙 = 留白 − 本体内缘 = 留白 − 抓取带），
 * 网格档下「离封面 7dp / 离屏缘 7dp」因此相等（视觉正中）、列表档同一取值。
 *
 * 为什么钉纯函数值而不是量布局：本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），而滑条只在
 * 「1.2 秒淡出」的窗口内存在、单测里起不了帧 ⇒ 真量本体盒子的路径走不通（`EntryProgressBarTest` 同此限制，
 * 写明了原因）。因此按仓库既有做法钉**出货几何的同源函数值**（[quickScrollBarEdgeGap] /
 * [quickScrollBarStripWidth]，界面侧就是拿这两个函数算 `Modifier.offset` 的横向分量与 `Modifier.width`——
 * 不是钉某份默认常量），接线（dp → `toPx()` → `Modifier`）由真机验收覆盖。
 *
 * 判别力（每条都能失败）：本体宽改回 4dp / 下限退回 24dp / 水平右留白改回 12dp / 中心距屏缘改回 5dp 或 7dp 即变红；
 * [quickScrollBarEdgeGap] 改成**随空档变化**（r4–r5 的「居中于空档」：`(gap − barWidth) / 2`）时
 * [有系统右缘 inset 时本体右缘距屏幕右缘恒为 7dp] 与 [本体右缘离屏缘 7dp 且由中心距推出] 变红
 * （r7 评审：旧版这两条只断言与空档无关的常量、循环里逐场景重复求值 ⇒ 判别力为 0，现在场景按 gap 参数化、
 * 断言真的喂给出货函数）；[quickScrollBarStripWidth] 丢了「+ 本体宽」时 [本体整体落在抓取带内] 与两条
 * 「不侵入右留白」变红。
 *
 * 位置口径（批次 9 r2，维护者第四轮真机反馈「偏右」）：本体**贴屏幕侧固定**——
 * 胶囊中心离屏缘 [QUICK_SCROLL_BAR_CENTER_GAP]（10dp）。r4–r5 取「内容右缘 ↔ 屏幕右缘」的空档做居中，
 * 空档被系统右缘 inset 撑宽时本体跟着往外挪，观感上贴向封面 ⇒ 本轮不再让空档参与**定位**
 * （空档只当夹取上界：本体必须落在空档里，见 [空档比本体占位还窄时向内夹 不压内容]）。
 */
class QuickScrollBarSizeTest {

    /** 内容侧的空档 = 两档右留白（无系统右缘 inset）；系统 inset 只会把它撑得更宽（见外侧用例） */
    private val contentGap = GRID_CONTENT_PADDING_HORIZONTAL

    /** 48dp 三键导航把导航栏放右侧（或右侧挖孔）时的空档：inset + 内容右留白 */
    private val insetGap = contentGap + 48.dp

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
     * 批次 9 r2 位置口径：胶囊**中心**距**屏幕**右缘 10dp（7dp 在网格档下读作「偏右」——离封面 10dp 而离屏缘只 4dp）。
     *
     * 关系式（不是把常量抄一遍）：中心距 = 本体右缘离屏缘 + 本体宽 / 2，由出货函数
     * [quickScrollBarEdgeGap] 给出——它若被改成写死值，两边就不再是同一个式子。
     */
    @Test
    fun `胶囊中心距屏幕右缘 10dp`() {
        assertEquals(10.dp, QUICK_SCROLL_BAR_CENTER_GAP)
        assertEquals(
            "中心距 = 离屏缘 + 本体宽 / 2",
            QUICK_SCROLL_BAR_CENTER_GAP,
            quickScrollBarEdgeGap(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH) +
                QUICK_SCROLL_BAR_WIDTH / 2,
        )
    }

    /**
     * 批次 9 r2：本体**右缘**距**屏幕**右缘 7dp（= 10 − 6/2）；换一个本体宽，离屏缘跟着变——这条就是「离屏缘由
     * 中心距推出、不是写死的 7dp」的判据。
     */
    @Test
    fun `本体右缘离屏缘 7dp 且由中心距推出`() {
        assertEquals(7.dp, quickScrollBarEdgeGap(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH))
        assertEquals(
            "本体 4dp 时离屏缘应是 10 − 2 = 8dp",
            8.dp,
            quickScrollBarEdgeGap(gap = contentGap, barWidth = 4.dp),
        )
    }

    /**
     * 批次 9 r2：两档右留白 20dp 下抓取带宽 **13dp**（= 离屏缘 7 + 本体 6，正好盖满本体）——本体占屏缘 7–13dp，
     * 带必须跟到同样的 13dp（本体最内侧那段不能落在带外，否则按下去落到列表内容）。
     */
    @Test
    fun `抓取带 13dp`() {
        assertEquals(13.dp, quickScrollBarStripWidth(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH))
    }

    /**
     * AC13 护栏（r2 评审要求恢复）：**本体要整个落在抓取带内**——带是命中区，本体比带宽的话最内侧那段
     * 压在带外，按下去就落到列表内容。出货带宽由 [quickScrollBarStripWidth] 给（= 离屏缘 + 本体），
     * 这条断言把它钉成等式：函数丢了「+ 本体宽」即变红。
     */
    @Test
    fun `本体整体落在抓取带内`() {
        val edgeGap = quickScrollBarEdgeGap(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertEquals(
            "抓取带应等于离屏缘 $edgeGap + 本体 $QUICK_SCROLL_BAR_WIDTH",
            edgeGap + QUICK_SCROLL_BAR_WIDTH,
            quickScrollBarStripWidth(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
        assertTrue(
            "离屏缘 $edgeGap + 本体 $QUICK_SCROLL_BAR_WIDTH 应 ≤ 抓取带",
            edgeGap + QUICK_SCROLL_BAR_WIDTH <=
                quickScrollBarStripWidth(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
    }

    /** AC13 护栏：抓取带不得宽于网格档右留白，否则会压到封面/名称（带宽改大或留白改小即变红） */
    @Test
    fun `抓取带不侵入网格档的右留白`() {
        val strip = quickScrollBarStripWidth(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH)
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
        val strip = quickScrollBarStripWidth(gap = LIST_ROW_END_PADDING, barWidth = QUICK_SCROLL_BAR_WIDTH)
        assertTrue(
            "抓取带 $strip 应 ≤ 列表档右留白 $LIST_ROW_END_PADDING",
            strip <= LIST_ROW_END_PADDING,
        )
    }

    /**
     * 位置口径的真值：两档右留白 20dp 下本体占屏缘 7–13dp，与内容之间留出 20 − 13 = **7dp** 空隙
     * （批次 9 r2 由 10dp 变来：空隙 = 留白 − 抓取带 = 留白 − 本体内缘；旧口径 12 − 4 − 6 = 2dp 就是维护者
     * 第一次抱怨的「黏在封面上」）。网格档下这条空隙与「离屏缘」相等（7dp）⇒ 视觉正中。
     *
     * 判别力：留白退回 12dp、或本体右缘离屏缘改回 4dp（批 8）/ 7dp（批次 9）都变红。
     */
    @Test
    fun `本体与内容之间留出 7dp 空隙`() {
        assertEquals(
            7.dp,
            contentGap - quickScrollBarStripWidth(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
        // 网格档「离封面 7dp、离屏缘 7dp」：两个距离相等
        assertEquals(
            quickScrollBarEdgeGap(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
            contentGap - quickScrollBarStripWidth(gap = contentGap, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
    }

    /**
     * 批次 9 r2（真机「偏右」的修法）：**有系统右缘 inset 时，本体右缘距屏幕右缘恒为 7dp**。
     *
     * 空档 = 系统右缘 inset + 内容右留白（横屏三键导航把导航栏放右侧、挖孔时是 48 + 20 = 68dp）。
     * 定位只认屏幕侧，空档进不了定位（只在留白比本体占位还窄时向内夹，见下一条用例），因此两个档位下装配值
     * 相同——本体不跟着 inset 往外挪：r4–r5 拿空档算离屏缘（20dp ⇒ 7dp、68dp ⇒ 31dp），inset 越大越贴封面，
     * 这正是「偏左」的成因。
     *
     * **判别力（r7 评审要求，之前为 0）**：每个档位都把该档位的 gap **喂进出货函数**再断言取值；把
     * [quickScrollBarEdgeGap] 改成「随空档变化」（r4–r5 的居中式 `(gap − barWidth) / 2`，⇒ 7dp / 31dp）后本条变红。
     */
    @Test
    fun `有系统右缘 inset 时本体右缘距屏幕右缘恒为 7dp`() {
        val scenarios = listOf(
            "无系统右缘 inset" to contentGap,
            "48dp 三键导航右侧 inset" to insetGap,
        )
        scenarios.forEach { (name, gap) ->
            assertEquals(
                "$name（空档 $gap）下本体右缘离屏缘",
                7.dp,
                quickScrollBarEdgeGap(gap = gap, barWidth = QUICK_SCROLL_BAR_WIDTH),
            )
        }
        // 同一份运行里两个档位的取值必须相等（「不随 inset 漂移」的正面表述）
        assertEquals(
            scenarios.map { (_, gap) ->
                quickScrollBarEdgeGap(gap = gap, barWidth = QUICK_SCROLL_BAR_WIDTH)
            }.toSet().size,
            1,
        )
        // 空档这一侧也要成立：带仍不宽于空档 ⇒ 本体留在留白内、不压封面与名称
        scenarios.forEach { (name, gap) ->
            val strip = quickScrollBarStripWidth(gap = gap, barWidth = QUICK_SCROLL_BAR_WIDTH)
            assertTrue("$name（空档 $gap）下抓取带 $strip 应 ≤ 空档", strip <= gap)
        }
    }

    /**
     * r7（清单里让 [quickScrollBarEdgeGap] 读得到 gap 的理由）：空档只当**夹取上界**——留白窄于本体占位时
     * 向内夹，保证本体不压封面与名称（这也是它和「定位」的区别：定位的基准是屏幕右缘，不随空档漂）。
     *
     * 判别力：函数退回「恒定 7dp 不读 gap」时 `gap = 7dp` 一档变红（7dp + 6dp 会压进内容区）。
     */
    @Test
    fun `空档比本体占位还窄时向内夹 不压内容`() {
        // 7dp 留白：右缘离屏缘夹到 7 − 6 = 1dp（本体整体落在留白内）
        assertEquals(
            1.dp,
            quickScrollBarEdgeGap(gap = 7.dp, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
        // 4dp 留白比本体还窄：夹到 0（贴屏缘），不出现负偏移
        assertEquals(
            0.dp,
            quickScrollBarEdgeGap(gap = 4.dp, barWidth = QUICK_SCROLL_BAR_WIDTH),
        )
    }
}
