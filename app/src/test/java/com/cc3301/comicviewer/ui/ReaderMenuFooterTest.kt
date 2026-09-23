package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 底部行（票 #105 AC7/AC8 + 补记 8 ①③）的**真实点击位置、真实命中高度与行高**：
 * 真组合生产代码 [ReaderMenuFooter]，真往 [ComposeView] 发触摸事件，看哪个回调被触发。
 *
 * 版式（补记 8 ① 的 V1）：**三列等宽**——上一本 / 页数 / 下一本，每列内容在自己列里居中，
 * 三个中心分别在行宽的 1/6、1/2、5/6；上一本与下一本的**整列可点**。
 *
 * 判别力：
 * ① 行**可见**高 = 36dp（补记 8 ② 的 A 档），但上一本那列的**命中带高 ≥ 48dp**，且**只向下挂**
 *    （第 10 轮第 2 条）：行顶上方 1dp / 5dp 点不中（向上溢出 0 ≤ 行距）、行顶下方 1dp 与 44dp 点得中、
 *    下方 52dp 点不中——两种行距（4dp 常规 / 0dp 矮视口）各量一遍；
 * ② 列边界在 1/3 与 2/3：1/6 触发上一本、1/2 两个回调都不触发（中间列是页数，不是按钮）、
 *    5/6 触发下一本；1/3 ± 1dp 与 2/3 ± 1dp 逐对断言（等宽三列才可能同时成立），
 *    **按每次点按的增量**断言（累计值会被前面的点按推高，写死累计值是在验旧状态）。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：页数文字在**中列居中**只由结构（`weight(1f)` + `TextAlign.Center`）
 * 与真机目视把守——Robolectric 的字体度量是 stub，量不出文字盒的真实居中；页数字色（纯白）与
 * 上/下一本的橙色由 `ReaderMenuLayoutTest.页数是纯白 上下一本是橙` 锁常量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderMenuFooterTest {

    /**
     * 测量时给 [ComposeView] 的宽度规格：300dp（Robolectric 默认屏宽 320dp 之内）。
     * 它只是**请求值**：Activity 可见后主线程 traversal 会按**窗口**尺寸重新量一次（实测行宽 = 320dp，
     * 即默认屏宽，见 `三列等宽 上一本中心在 1 6 页数在 1 2 下一本在 5 6` 里的断言），
     * 所以行宽一律按 `boundsInWindow` 的实测值算（[Probe.xAt]）。320dp ÷ 3 ≈ 106.7dp > 本体下限 96dp，
     * 三列等宽不被本体下限破坏。
     */
    private val rowWidth = 300.dp

    /** 行上方留出的空白（默认为 [spaceAbove]，单个用例可传值模拟面板里的行距），用于验命中带与行的边界 */
    private val spaceAbove = 20.dp

    /** 行**下方**留出的空白：命中带改成只向下挂后，探点要落在行底之下，这块空白保证探点仍在 View 内 */
    private val spaceBelow = 24.dp

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /** 一次组合的观测结果：行的实测尺寸/位置、以及两个回调各被触发了几次 */
    private class Probe {
        var rowLeftPx = 0f
        var rowTopPx = 0f
        var rowWidthPx = -1
        var rowHeightPx = -1
        var prevClicks = 0
        var nextClicks = 0
    }

    /**
     * 组合生产代码 [ReaderMenuFooter]（上方垫 [spaceAbove]、下方垫 [spaceBelow] 便于量命中带的两端），
     * 返回观测器与可发事件的 View。宽度精确给 [rowWidth]、高度精确给「上下空白 + 行高」。
     */
    private fun compose(
        pageCount: Int = 340,
        displayPage: Int = 12,
        rowHeight: Dp = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP.dp,
        spaceAbove: Dp = this.spaceAbove,
    ): Pair<Probe, View> {
        val probe = Probe()
        val view = composeViewInActivity {
            MaterialTheme {
                Column(modifier = Modifier.height(spaceAbove + rowHeight + spaceBelow)) {
                    Spacer(modifier = Modifier.height(spaceAbove))
                    ReaderMenuFooter(
                        displayPage = displayPage,
                        pageCount = pageCount,
                        panelInnerWidth = 320.dp,
                        onPrevBook = { probe.prevClicks++ },
                        onNextBook = { probe.nextClicks++ },
                        rowHeight = rowHeight,
                        modifier = Modifier.onGloballyPositioned {
                            val frame = it.boundsInWindow()
                            probe.rowLeftPx = frame.left
                            probe.rowTopPx = frame.top
                            probe.rowWidthPx = frame.width.roundToInt()
                            probe.rowHeightPx = frame.height.roundToInt()
                        },
                    )
                    Spacer(modifier = Modifier.height(spaceBelow))
                }
            }
        }
        view.layoutOnce(
            (rowWidth.value * density).roundToInt(),
            ((spaceAbove + rowHeight + spaceBelow).value * density).roundToInt(),
        )
        assertTrue("底部行没被放置（测量没生效），本次断言无意义", probe.rowWidthPx > 0)
        return probe to view
    }


    /** 在 [view] 的 (x, y) 发一次按下 + 抬起（中间让主线程跑一轮，手势协程才看得见 UP） */
    private fun tap(view: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        view.dispatchTouchEvent(down)
        shadowOf(Looper.getMainLooper()).idle()
        val up = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(up)
        shadowOf(Looper.getMainLooper()).idle()
        down.recycle()
        up.recycle()
    }

    /** 行宽的比例位置（含 [offsetDp] 的微调，用于逐对验列边界） */
    private fun Probe.xAt(fraction: Float, offsetDp: Float = 0f): Float =
        rowLeftPx + rowWidthPx * fraction + offsetDp * density

    @Test
    fun `底部行可见高 36dp 命中带只向下挂 向上溢出为 0`() {
        // 补记 8 ②③ + 第 10 轮第 2 条：可见行高 48 → 36dp，命中带补到 48dp，但**只向下挂**。
        // 为何不能居中溢出：上行是 48dp 的滑条行（`SeekSlider` 的 `pointerInput` 铺满整行），
        // 行距只有 4dp，居中时命中带向上溢出 6dp ⇒ 2dp 压进滑条行（矮视口行距 0 ⇒ 6dp 全压进去），
        // 那 2dp 里点滑动条会被「上一本/下一本」抢走（跳书是直接执行、无确认）。
        // 量法（命中带 = [行顶, 行顶 + 48]）：行顶上方 1dp / 5dp 点不中（向上溢出 0 ≤ 行距）；
        // 行顶下方 1dp 与 44dp 点得中（带高 ≥ 48dp）；行顶下方 52dp 点不中（带高 < 56dp）。
        // 两种行距各量一遍：4dp = 常规行距、0dp = 矮视口行距（0 时向上溢出必须恰好为 0）。
        assertEquals("可见行高是 A 档的 36dp", 36f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        for (gapDp in listOf(4f, 0f)) {
            val (probe, view) = compose(spaceAbove = gapDp.dp)
            assertEquals("行可见高必须按参数走（36dp）", 36f, probe.rowHeightPx / density, 0.6f)
            val x = probe.xAt(1f / 6f)
            fun yBelow(dp: Float): Float = probe.rowTopPx + dp * density
            tap(view, x = x, y = yBelow(-5f))
            assertEquals("行距 ${gapDp}dp：行顶上方 5dp 不得命中（向上溢出必须 ≤ 行距）", 0, probe.prevClicks)
            tap(view, x = x, y = yBelow(-1f))
            assertEquals("行距 ${gapDp}dp：行顶上方 1dp 也不得命中（命中带顶边 = 行顶）", 0, probe.prevClicks)
            tap(view, x = x, y = yBelow(1f))
            assertEquals("行距 ${gapDp}dp：行顶下方 1dp 在命中带内", 1, probe.prevClicks)
            tap(view, x = x, y = yBelow(44f))
            assertEquals("行距 ${gapDp}dp：行顶下方 44dp 仍命中（带高 ≥ 48dp）", 2, probe.prevClicks)
            tap(view, x = x, y = yBelow(52f))
            assertEquals("行距 ${gapDp}dp：行顶下方 52dp 已出命中带（带高 < 56dp）", 2, probe.prevClicks)
        }
    }

    @Test
    fun `三列等宽 上一本中心在 1 6 页数在 1 2 下一本在 5 6`() {
        // 补记 8 ①：三等分三列，内容在各自列里居中。
        // 判别力：列边界必须落在 1/3 与 2/3 —— 边界左右各 1dp 逐对断言（等宽三列才可能同时成立）；
        // 页数在中列居中 ⇒ 行中点两个回调都不触发（旧版「页数贴右端」下 1/2 处属下一本槽位、会触发）。
        val (probe, view) = compose()
        assertEquals(
            "前提：行宽 = Robolectric 窗口宽（默认屏宽 320dp，传进去的 300dp 规格被窗口盖掉），比例位置全部按实测行宽算",
            320f,
            probe.rowWidthPx / density,
            0.6f,
        )
        val midY = probe.rowTopPx + probe.rowHeightPx / 2f

        tap(view, x = probe.xAt(1f / 6f), y = midY)
        assertEquals("1/6 处（上一本列中心）触发上一本", 1, probe.prevClicks)
        assertEquals("1/6 处不得触发下一本", 0, probe.nextClicks)

        tap(view, x = probe.xAt(1f / 2f), y = midY)
        assertEquals("1/2 处（页数中列）不得触发上一本", 1, probe.prevClicks)
        assertEquals("1/2 处（页数中列）不得触发下一本", 0, probe.nextClicks)

        tap(view, x = probe.xAt(5f / 6f), y = midY)
        assertEquals("5/6 处（下一本列中心）触发下一本", 1, probe.nextClicks)
        assertEquals("5/6 处不得触发上一本", 1, probe.prevClicks)

        // 列边界两侧各 1dp 逐对断言：用**本次点按的增量**（前面的点按已把两个计数各推高过一次，
        // 写死累计值只会验到「上一拍有没有生效」，抓不到边界本身）
        val prevBefore = probe.prevClicks
        tap(view, x = probe.xAt(1f / 3f, offsetDp = -1f), y = midY)
        assertEquals("1/3 左侧 1dp 仍属上一本列", prevBefore + 1, probe.prevClicks)
        tap(view, x = probe.xAt(1f / 3f, offsetDp = 1f), y = midY)
        assertEquals("1/3 右侧 1dp 已进中列（页数），不得再触发上一本", prevBefore + 1, probe.prevClicks)
        val nextBefore = probe.nextClicks
        tap(view, x = probe.xAt(2f / 3f, offsetDp = -1f), y = midY)
        assertEquals("2/3 左侧 1dp 仍属中列，不得触发下一本", nextBefore, probe.nextClicks)
        tap(view, x = probe.xAt(2f / 3f, offsetDp = 1f), y = midY)
        assertEquals("2/3 右侧 1dp 已进下一本列", nextBefore + 1, probe.nextClicks)
        assertEquals("边界四拍都不得串到另一列", prevBefore + 1, probe.prevClicks)
    }

    @Test
    fun `整列可点 行左缘与行右缘都在按钮列里`() {
        // AC7「可点击区域 = 整份空白区」：贴行左缘 / 右缘都点得中（列宽 100dp，远大于文字盒）；
        // 补记 8 ① 的三等分下，最左端属上一本列、最右端属下一本列（不再是「右端是页数」）
        val (probe, view) = compose()
        val midY = probe.rowTopPx + probe.rowHeightPx / 2f
        tap(view, x = probe.xAt(0f, offsetDp = 1f), y = midY)
        assertEquals("贴左缘应触发上一本", 1, probe.prevClicks)
        assertEquals("贴左缘不得触发下一本", 0, probe.nextClicks)
        tap(view, x = probe.xAt(1f, offsetDp = -1f), y = midY)
        assertEquals("贴右缘应触发下一本", 1, probe.nextClicks)
        assertEquals("贴右缘不得触发上一本", 1, probe.prevClicks)
    }

    @Test
    fun `上一本与下一本各整列可点 列内任意位置都触发`() {
        // 补记 8 ① 后半句：两列仍**整列可点**（可点区不得缩成本体那点大小）。
        // 每列取列内 1/6、1/2、5/6 三点逐点断言「本列回调 +1、另一列不变」——
        // 可点区若只有文字宽（本体 96dp 居中），列两端的点会脱靶。
        val (probe, view) = compose()
        val midY = probe.rowTopPx + probe.rowHeightPx / 2f
        for (fraction in listOf(1f / 18f, 1f / 6f, 5f / 18f)) {
            val before = probe.prevClicks
            tap(view, x = probe.xAt(fraction), y = midY)
            assertEquals("上一本列内 ${fraction * 18} / 18 处必须触发上一本", before + 1, probe.prevClicks)
            assertEquals("上一本列内不得触发下一本", 0, probe.nextClicks)
        }
        for (fraction in listOf(13f / 18f, 5f / 6f, 17f / 18f)) {
            val before = probe.nextClicks
            val prevBefore = probe.prevClicks
            tap(view, x = probe.xAt(fraction), y = midY)
            assertEquals("下一本列内 ${fraction * 18} / 18 处必须触发下一本", before + 1, probe.nextClicks)
            assertEquals("下一本列内不得触发上一本", prevBefore, probe.prevClicks)
        }
    }

    /** 量一次按钮的**可见本体**（[BookStepLabel]，生产代码）的真实尺寸 */
    private fun measureLabel(): Pair<Int, Int> {
        var width = -1
        var height = -1
        val view = composeViewInActivity {
            MaterialTheme {
                BookStepLabel(
                    text = "上一本",
                    modifier = Modifier.onGloballyPositioned {
                        width = it.size.width
                        height = it.size.height
                    },
                )
            }
        }
        view.layoutOnce((rowWidth.value * density).roundToInt())
        assertTrue("按钮本体没被放置（测量没生效），本次断言无意义", width > 0)
        return width to height
    }

    @Test
    fun `按钮可见本体不小于 96 乘 48dp`() {
        // 票 #105 AC7「按钮加大」：改前是裸 labelLarge 文字（无内边距/背景/边框），
        // 本体尺寸就是文字盒；本票给它两个尺寸下限（批次 6 AC15 去掉底色/描边后这两个下限仍在），
        // 这里量真的放置框。补记 8 只压「可见行高」，本体的 96 × 48 不动。
        val (widthPx, heightPx) = measureLabel()
        val minWidthPx = (ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP * density).roundToInt()
        val minHeightPx = (ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP * density).roundToInt()
        assertTrue(
            "按钮本体宽 ${widthPx}px（${widthPx / density}dp）必须 ≥ 96dp（AC7）",
            widthPx >= minWidthPx,
        )
        assertTrue(
            "按钮本体高 ${heightPx}px（${heightPx / density}dp）必须 ≥ 48dp（AC7）",
            heightPx >= minHeightPx,
        )
    }

    @Test
    fun `页数不从按钮回调里漏出来`() {
        // 中列是页数、不是按钮：在 1/2 处连点两次，两个回调都必须仍是 0（旧版页数贴右端时 1/2 会触发下一本）
        val (probe, view) = compose()
        val midY = probe.rowTopPx + probe.rowHeightPx / 2f
        tap(view, x = probe.xAt(0.45f), y = midY)
        tap(view, x = probe.xAt(0.55f), y = midY)
        assertEquals("中列（页数）不得触发上一本", 0, probe.prevClicks)
        assertEquals("中列（页数）不得触发下一本", 0, probe.nextClicks)
    }
}
