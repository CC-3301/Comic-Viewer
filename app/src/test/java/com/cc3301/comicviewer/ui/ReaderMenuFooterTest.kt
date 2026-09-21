package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.ReaderMenuLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 底部行（票 #105 AC7/AC8）的**真实点击行为与行高**：真组合生产代码 [ReaderMenuFooter]，
 * 真往 [ComposeView] 发触摸事件，看哪个回调被触发。
 *
 * 版式（第 6 轮真机反馈第 ② 条）：**上一本（左半）/ 下一本（右半）两个等权槽位 + 页数在最右端**
 * （替换上一轮的「上一本 / 页码 / 下一本 三槽、页码严格居中」）。
 *
 * 判别力：
 * ① 行高实测必须 ≥ 48dp——AC7「可点击区域加大」的下限（改动前是 32dp 高的文字按钮）；
 * ② 行**左缘附近**（行宽 1/8 处）与**右半**（2/3 处）分别触发上/下一本：这两个点落在页数左侧的空白区里，
 *    只有「整个槽位都是按钮」才点得中——改成从前那种窄按钮（或把按钮贴回屏幕角）就点不中；
 * ③ 行**最右端**（页数文字处）两个回调都不触发：命中区确实被页数占着，不是整行一个按钮。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：按钮**文字在槽位里居中**只由代码结构（`contentAlignment = Center`）
 * 与真机目视把守——Robolectric 的字体度量是 stub，量不出文字盒的真实居中；页数文字落在行右端同理
 * （结构保证：两个 `weight(1f)` 槽位在前、页数 Text 在后）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderMenuFooterTest {

    /** 复刻的底部行宽度：取 300dp（Robolectric 默认屏宽 320dp 之内） */
    private val rowWidth = 300.dp

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /** 一次组合的观测结果：行的实测尺寸、以及两个回调各被触发了几次 */
    private class Probe {
        var rowWidthPx = -1
        var rowHeightPx = -1
        var prevClicks = 0
        var nextClicks = 0
    }

    /** 组合生产代码 [ReaderMenuFooter] 并布局成 [rowWidth] 宽，返回观测器与可发事件的 View */
    private fun compose(pageCount: Int = 340, displayPage: Int = 12): Pair<Probe, View> {
        val probe = Probe()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            MaterialTheme {
                ReaderMenuFooter(
                    displayPage = displayPage,
                    pageCount = pageCount,
                    panelInnerWidth = 320.dp,
                    onPrevBook = { probe.prevClicks++ },
                    onNextBook = { probe.nextClicks++ },
                    modifier = Modifier.onGloballyPositioned {
                        val frame = it.boundsInWindow()
                        probe.rowWidthPx = frame.width.roundToInt()
                        probe.rowHeightPx = frame.height.roundToInt()
                    },
                )
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec((rowWidth.value * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
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

    @Test
    fun `底部行高不小于 48dp`() {
        val (probe, _) = compose()
        val minPx = (48f * density).roundToInt()
        assertTrue(
            "实测行高 ${probe.rowHeightPx}px（${probe.rowHeightPx / density}dp）必须 ≥ 48dp（AC7 触摸目标下限）",
            probe.rowHeightPx >= minPx,
        )
    }

    @Test
    fun `行左缘附近的空白区点击触发上一本`() {
        val (probe, view) = compose()
        tap(view, x = probe.rowWidthPx / 8f, y = probe.rowHeightPx / 2f)
        assertEquals("左侧空白区应触发上一本", 1, probe.prevClicks)
        assertEquals("不应触发下一本", 0, probe.nextClicks)
    }

    @Test
    fun `行右半的空白区点击触发下一本`() {
        // 三分之二处落在「下一本」槽位内（第 6 轮起页数占住行的最右端，所以不再取 7/8）
        val (probe, view) = compose()
        tap(view, x = probe.rowWidthPx * 2f / 3f, y = probe.rowHeightPx / 2f)
        assertEquals("右侧空白区应触发下一本", 1, probe.nextClicks)
        assertEquals("不应触发上一本", 0, probe.prevClicks)
    }

    @Test
    fun `可点区域从行左缘一直铺到页数左侧`() {
        // AC7「可点击区域 = 整份空白区」：贴着行左缘也要点得中（从前那个窄按钮点不中点不到）；
        // 第 6 轮真机反馈第 ② 条把**页数放到行的右端**，所以行的最右端是页数、不是按钮
        val (probe, view) = compose()
        tap(view, x = 1f, y = probe.rowHeightPx / 2f)
        assertEquals("贴左缘应触发上一本", 1, probe.prevClicks)
        tap(view, x = probe.rowWidthPx * 3f / 4f, y = probe.rowHeightPx / 2f)
        assertEquals("四分之三处（下一本槽位内）应触发下一本", 1, probe.nextClicks)
    }

    @Test
    fun `行右端的页数不触发上下一本`() {
        // 第 6 轮真机反馈第 ② 条：页数放在行的右端，因此最右端那一段是页数、不是按钮
        val (probe, view) = compose()
        tap(view, x = probe.rowWidthPx - 1f, y = probe.rowHeightPx / 2f)
        assertFalse("页数不是按钮", probe.prevClicks > 0)
        assertFalse("页数不是按钮", probe.nextClicks > 0)
    }

    /** 量一次按钮的**可见本体**（[BookStepLabel]，生产代码）的真实尺寸 */
    private fun measurePill(): Pair<Int, Int> {
        var width = -1
        var height = -1
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
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
        view.measure(
            View.MeasureSpec.makeMeasureSpec((rowWidth.value * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("按钮本体没被放置（测量没生效），本次断言无意义", width > 0)
        return width to height
    }

    @Test
    fun `按钮可见本体不小于 96 乘 48dp`() {
        // 票 #105 AC7「按钮加大」：改前是裸 labelLarge 文字（无内边距/背景/边框），
        // 本体尺寸就是文字盒；本票给它两个尺寸下限（批次 6 AC15 去掉底色/描边后这两个下限仍在），
        // 这里量真的放置框
        val (widthPx, heightPx) = measurePill()
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
    fun `矮视口底部行压到 36dp 且两个按钮仍可点`() {
        // 票 #105 批次 6 AC11「固定行已压扁」：行高 48 → 36dp（矮视口限定）。
        // 本用例直接给生产代码 [ReaderMenuFooter] 传压缩后的行高，验两件事：
        // ① 行高真的按参数走；② 压扁后两个槽位仍然点得中（可点区域 = 整行，不随行高变化而变窄）
        val rowHeight = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_SHORT_DP.dp
        assertEquals(36f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_SHORT_DP, 0.01f)
        val probe = Probe()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            MaterialTheme {
                ReaderMenuFooter(
                    displayPage = 12,
                    pageCount = 340,
                    panelInnerWidth = 320.dp,
                    onPrevBook = { probe.prevClicks++ },
                    onNextBook = { probe.nextClicks++ },
                    rowHeight = rowHeight,
                    modifier = Modifier.onGloballyPositioned {
                        probe.rowWidthPx = it.size.width
                        probe.rowHeightPx = it.size.height
                    },
                )
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec((rowWidth.value * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((rowHeight.value * density).roundToInt(), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        // 行高本身由纯函数锁定（ReaderMenuLayoutTest：panelFooterHeightDp(true) == 36dp）：
        // Robolectric 在 UNSPECIFIED 高度下报的节点尺寸不可靠（同一接缝的既有用例只用它当 ≥48dp 的下限），
        // 因此这里量的是「压扁后还点得中」——可点区域 = 整行，行高变小不影响左右两个槽位
        val midRowY = (rowHeight.value * density) / 2f
        tap(view, x = probe.rowWidthPx / 8f, y = midRowY)
        tap(view, x = probe.rowWidthPx * 7f / 8f, y = midRowY)
        assertEquals("压扁后左侧空白区仍应触发上一本", 1, probe.prevClicks)
        assertEquals("压扁后右侧空白区仍应触发下一本", 1, probe.nextClicks)
    }
}
