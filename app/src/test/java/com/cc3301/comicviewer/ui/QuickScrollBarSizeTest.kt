package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的尺寸与**横向位置**口径（票 #60 追加口径 AC8–AC10 + 批次 6 定版 D7-A 的 AC13 +
 * 本轮定版「居中于空档」）：长度下限 **64dp**、本体宽 **6dp**、本体**居中于横向空档**
 * （离屏缘 = `(空档 − 本体宽) / 2`）、两档右留白**都是 20dp**。
 *
 * **本轮的用例为什么写成两层**：上一版只钉了「空档内两侧相等」这个**自洽关系** —— 它与本体在屏上的绝对
 * 位置无关，空档取哪个值都成立，所以真机说「偏左」时用例照样全绿（同一条几何在 r4/r5 → 批次 9 之间翻覆过
 * 数轮，最终口径见 `docs/SPEC.md` 的「快速定位滑条」段与 `CONTEXT.md` 同名条目）。本轮**同时**钉：
 *
 * 1. **关系**：空档 20dp（无系统右缘 inset）时「本体右缘距屏缘 == 本体左缘距内容右缘」（[本体居中于空档]）；
 * 2. **绝对量**：`gap` 取**两个不同值**（20dp 与 68dp = 无 inset / 48dp 三键导航右侧 inset），各自
 *    「本体右缘距屏缘」与「抓取带宽」都有具体期望值断言（7dp / 13dp 与 31dp / 37dp）。
 *
 * 判别力（注入式写法见下）：把 [quickScrollBarEdgeGap] 换成「贴屏幕侧固定」的
 * `10.dp − barWidth / 2`（r6/r8 的写法）⇒ 固定写法恒给 7dp，因此凡取到 68dp 空档的断言都变红
 * （[空档 68dp 时本体右缘离屏缘 31dp]、[两个空档下的抓取带宽]、[两个空档下本体都居中]），
 * 而只取 20dp 空档的断言因 `(20 − 6) / 2 == 10 − 6 / 2` 仍绿——只钉 20dp 那一档挡不住它。
 * 反过来只钉关系不钉绝对量时上面那组仍然全绿。两条一起才挡得住两个方向的漂移。
 * 本体宽改回 4dp / 下限退回 24dp / 水平右留白改回 12dp 也会变红；[quickScrollBarStripWidth] 丢了
 * 「+ 本体宽」时 [本体整体落在抓取带内] 的等式与两条居中关系断言不再成立（[抓取带不侵入网格档的右留白]
 * 在注入下仍绿：带变窄后仍 ≤ 留白，它挡的是「带宽改大 / 留白改小」）。
 *
 * 为什么钉纯函数值而不是量布局：本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），而滑条只在
 * 「1.2 秒淡出」的窗口内存在、单测里起不了帧 ⇒ 真量本体盒子的路径走不通（`EntryProgressBarTest` 同此限制，
 * 写明了原因）。因此按仓库既有做法钉**出货几何的同源函数值**（[quickScrollBarEdgeGap] /
 * [quickScrollBarStripWidth]，界面侧就是拿这两个函数算 `Modifier.offset` 的横向分量与 `Modifier.width`——
 * 不是钉某份默认常量）；接线（dp → `toPx()` → `Modifier`）与真机观感由真机验收覆盖。
 *
 * **竖屏看不出变化是预期**：无系统右缘 inset 时空档 20dp，新旧实现给出**同一位置**（本体右缘离屏缘 7dp）；
 * 只有空档被撑宽（横屏、三键导航把系统栏放右侧、挖孔）时两者才不同 —— 见 [空档 68dp 时本体右缘离屏缘 31dp]。
 */
class QuickScrollBarSizeTest {

    /** 内容侧的空档 = 两档右留白（无系统右缘 inset）；系统右缘 inset 只会把它撑得更宽（见外侧用例） */
    private val contentGap = GRID_CONTENT_PADDING_HORIZONTAL

    /** 48dp 三键导航把导航栏放右侧（或右侧挖孔）时的空档：inset + 内容右留白 */
    private val insetGap = contentGap + 48.dp

    private val barWidth = QUICK_SCROLL_BAR_WIDTH

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
     * **绝对量一（竖屏真值）**：无系统右缘 inset、空档 = 两档右留白 20dp ⇒ 本体右缘距屏缘
     * `(20 − 6) / 2 = ` **7dp**。
     */
    @Test
    fun `空档 20dp 时本体右缘离屏缘 7dp`() {
        assertEquals(7.dp, quickScrollBarEdgeGap(gap = contentGap, barWidth = barWidth))
    }

    /**
     * **绝对量二（空档被撑宽，本轮改动的唯一可见档位）**：48dp 三键导航把系统栏放右侧 ⇒ 空档
     * 20 + 48 = 68dp ⇒ 离屏缘 `(68 − 6) / 2 = ` **31dp**（不是 7dp）。
     *
     * 这条就是「贴屏幕侧固定」写法的红点：r6/r8 的 `10dp − barWidth / 2` 恒给 7dp，本断言立刻失败。
     * 真机含义：横屏/三键导航下空档被撑宽 ⇒ 本体**离屏缘更大、更靠内容侧**（7dp → 31dp），空档内两侧留白仍相等。
     * **已知限制**：本体占屏缘 = [(inset + 14) / 2, (inset + 14) / 2 + 6]（空档 = inset + 20dp 右留白）⇒ 与系统
     * inset 带 [0, inset] **相交**要 inset > 14dp（= 20 − 6）、**整个落入**要 inset ≥ 26dp；48dp 导航栏下本体占
     * 31–37dp ⊂ 0–48dp（整个落在带内），是否被系统栏盖住 / 命中被接管**尚不确定**，由真机验收判（本地判不了，不造探针）。
     */
    @Test
    fun `空档 68dp 时本体右缘离屏缘 31dp`() {
        assertEquals(31.dp, quickScrollBarEdgeGap(gap = insetGap, barWidth = barWidth))
    }

    /**
     * **关系层**：空档 20dp 下「本体右缘距屏缘」== 「本体左缘距内容右缘」（两条边相等 = 居中）。
     *
     * 与两条绝对量断言配合：关系式单独成立不足以定位置（上一版翻车的原因），但少了它「居中」这个口径
     * 就没有直接判据。两个距离都由出货函数给：左边 [quickScrollBarEdgeGap]，右边 = 空档 − 抓取带。
     */
    @Test
    fun `本体居中于空档`() {
        assertEquals(
            "本体右缘距屏缘应等于本体左缘距内容右缘",
            quickScrollBarEdgeGap(gap = contentGap, barWidth = barWidth),
            contentGap - quickScrollBarStripWidth(gap = contentGap, barWidth = barWidth),
        )
    }

    /**
     * **抓取带绝对值**：空档 20dp ⇒ **13dp**（= 离屏缘 7 + 本体 6）；空档 68dp ⇒ **37dp**（= 31 + 6，
     * 即中等空档下带子随空档一起变宽）。带没跟上的话，本体最内侧那段会落在带外，按下去落到列表内容。
     */
    @Test
    fun `两个空档下的抓取带宽`() {
        assertEquals(13.dp, quickScrollBarStripWidth(gap = contentGap, barWidth = barWidth))
        assertEquals(37.dp, quickScrollBarStripWidth(gap = insetGap, barWidth = barWidth))
    }

    /**
     * 空档内两侧留白相等在两个档位都成立（同一条关系在 68dp 上再验一次，防「只在 20dp 凑对」）。
     */
    @Test
    fun `两个空档下本体都居中`() {
        listOf("无 inset" to contentGap, "48dp 右侧 inset" to insetGap).forEach { (name, gap) ->
            assertEquals(
                "$name（空档 $gap）下两边的留白应相等",
                quickScrollBarEdgeGap(gap = gap, barWidth = barWidth),
                gap - quickScrollBarStripWidth(gap = gap, barWidth = barWidth),
            )
        }
    }

    /**
     * 离屏缘是**由空档与本体宽推出**的，不是写死的 7dp：换个本体宽，取值跟着 `(空档 − 宽) / 2` 变
     * （20dp 空档下 4dp 本体 ⇒ 8dp）。
     */
    @Test
    fun `离屏缘由空档与本体宽推出 不是写死的 7dp`() {
        assertEquals(8.dp, quickScrollBarEdgeGap(gap = contentGap, barWidth = 4.dp))
        assertEquals(7.dp, quickScrollBarEdgeGap(gap = contentGap, barWidth = barWidth))
    }

    /**
     * AC13 护栏：**本体要整个落在抓取带内**——带是命中区，本体比带宽的话最内侧那段压在带外，
     * 按下去就落到列表内容。出货带宽由 [quickScrollBarStripWidth] 给（= 离屏缘 + 本体），
     * 这条断言把它钉成等式：函数丢了「+ 本体宽」即变红。
     */
    @Test
    fun `本体整体落在抓取带内`() {
        listOf("无 inset" to contentGap, "48dp 右侧 inset" to insetGap).forEach { (name, gap) ->
            val edgeGap = quickScrollBarEdgeGap(gap = gap, barWidth = barWidth)
            assertEquals(
                "$name（空档 $gap）：抓取带应等于离屏缘 $edgeGap + 本体 $barWidth",
                edgeGap + barWidth,
                quickScrollBarStripWidth(gap = gap, barWidth = barWidth),
            )
            assertTrue(
                "$name（空档 $gap）：离屏缘 $edgeGap + 本体 $barWidth 应 ≤ 抓取带",
                edgeGap + barWidth <= quickScrollBarStripWidth(gap = gap, barWidth = barWidth),
            )
        }
    }

    /** AC13 护栏：抓取带不得宽于网格档右留白，否则会压到封面/名称（带宽改大或留白改小即变红） */
    @Test
    fun `抓取带不侵入网格档的右留白`() {
        val strip = quickScrollBarStripWidth(gap = contentGap, barWidth = barWidth)
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
        val strip = quickScrollBarStripWidth(gap = LIST_ROW_END_PADDING, barWidth = barWidth)
        assertTrue(
            "抓取带 $strip 应 ≤ 列表档右留白 $LIST_ROW_END_PADDING",
            strip <= LIST_ROW_END_PADDING,
        )
        // 两档同一个位置 ⇒ 切列表/网格时滑条不动（空档同值 ⇒ 离屏缘同值）
        assertEquals(
            quickScrollBarEdgeGap(gap = contentGap, barWidth = barWidth),
            quickScrollBarEdgeGap(gap = LIST_ROW_END_PADDING, barWidth = barWidth),
        )
    }

    /**
     * 位置口径的真值：空档 20dp 下本体占屏缘 7–13dp，与内容之间留出 20 − 13 = **7dp** 空隙
     * （批次 9 r2 由 10dp 变来：空隙 = 空档 − 抓取带；旧口径 12 − 4 − 6 = 2dp 就是维护者
     * 第一次抱怨的「黏在封面上」）。网格档下这条空隙与「离屏缘」相等（都是 7dp）⇒ 视觉正中。
     *
     * 判别力：留白退回 12dp、或本体宽改回 4dp 都变红。
     */
    @Test
    fun `本体与内容之间留出 7dp 空隙`() {
        assertEquals(
            7.dp,
            contentGap - quickScrollBarStripWidth(gap = contentGap, barWidth = barWidth),
        )
        // 网格档「离封面 7dp、离屏缘 7dp」：两个距离相等
        assertEquals(
            quickScrollBarEdgeGap(gap = contentGap, barWidth = barWidth),
            contentGap - quickScrollBarStripWidth(gap = contentGap, barWidth = barWidth),
        )
    }

    /**
     * 既有护栏（本轮保留）：空档比本体还窄时夹到 **0**（滑条贴屏缘），不产生负偏移；空档只比本体略宽时
     * 本体仍整个落在空档内（不压封面与名称）——两条都是「居中」在退化档位下的下限行为。
     *
     * 判别力：去掉 `coerceAtLeast(0.dp)` 时 `gap = 4dp` 那档变红（`(4 − 6) / 2` = −1dp；`gap = 6dp` 那档
     * 两边同为 0、不红）；`gap = 7dp` 那档钉**具体值 0.5dp**（= 居中式在退化档位下的取值，旧实现的
     * `gap − barWidth` 夹取给 1dp ⇒ 变红），另加一条不等式钉「本体仍留在空档内」。
     */
    @Test
    fun `空档比本体窄时夹到 0 不产生负偏移`() {
        assertEquals(0.dp, quickScrollBarEdgeGap(gap = 4.dp, barWidth = barWidth))
        assertEquals(0.dp, quickScrollBarEdgeGap(gap = barWidth, barWidth = barWidth))
        assertEquals(0.5.dp, quickScrollBarEdgeGap(gap = 7.dp, barWidth = barWidth))
        assertTrue(
            "空档 7dp：本体应仍留在空档内",
            quickScrollBarEdgeGap(gap = 7.dp, barWidth = barWidth) + barWidth <= 7.dp,
        )
    }

    /** 夹取之后的护栏：空档容得下本体时（gap ≥ 本体宽）本体加离屏缘不得超出空档（否则压进内容区） */
    @Test
    fun `任何空档下本体都不压内容`() {
        listOf(barWidth, 7.dp, contentGap, insetGap).forEach { gap ->
            val edgeGap = quickScrollBarEdgeGap(gap = gap, barWidth = barWidth)
            assertTrue("空档 $gap：离屏缘 $edgeGap 不得为负", edgeGap >= 0.dp)
            assertTrue(
                "空档 $gap：离屏缘 $edgeGap + 本体 $barWidth 不得超出空档",
                edgeGap + barWidth <= gap,
            )
        }
    }
}
