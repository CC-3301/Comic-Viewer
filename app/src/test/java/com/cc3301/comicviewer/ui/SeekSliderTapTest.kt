package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
 * 跳页滑动条（票 #105 AC9：**点线上任意位置都跳到对应页**）的**端到端接线**用例：真组合 [SeekSlider]、
 * 真往 `ComposeView` 发按下 + 抬起，看 `onSeek` 拿到第几页。
 *
 * 为什么必须有这一层（第 6 轮补）：AC9 是**第三次**真机未通过。前两轮的用例全部落在纯状态层
 * （`SliderGestureStateTest` 直接调 `onTapFraction` / `onGestureFinished`），**绕过了 Compose 接线**——
 * 而失败恰好发生在接线里（同一次点按有两条通路：Material3 `Slider` 自己的按压换算 + `SeekSlider` 自接的
 * `pointerInput`），纯状态用例把那条竞争关系整个 stub 掉了，所以一直全绿。
 * 本用例断言的是「行上按下比例 → onSeek 的页」，接线退回「只有一个位置有效」时**必须红**。
 *
 * 用例按 [ReaderMenuFooterTest] 的同一条路发事件（`View.dispatchTouchEvent` + 主线程 idle），
 * 不依赖 `performTouchInput`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeekSliderTapTest {

    private val rowWidth = 300.dp

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    private class Probe {
        var rowWidthPx = -1
        var rowHeightPx = -1
        val seeks = mutableListOf<Int>()
    }

    /** 组合生产代码 [SeekSlider]（3 页书、当前第 1 页）并布局成 [rowWidth] 宽 */
    private fun compose(pageCount: Int = 3, initialPage: Int = 0): Pair<Probe, View> {
        val probe = Probe()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            MaterialTheme {
                val state = androidx.compose.runtime.remember(pageCount) { SliderGestureState(initialPage = initialPage, pageCount = pageCount) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    SeekSlider(
                        seekState = state,
                        onSeek = { probe.seeks += it },
                        modifier = Modifier.onGloballyPositioned {
                            val frame = it.boundsInWindow()
                            probe.rowWidthPx = frame.width.roundToInt()
                            probe.rowHeightPx = frame.height.roundToInt()
                        },
                    )
                }
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec((rowWidth.value * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("滑动条没被放置（测量没生效），本次断言无意义", probe.rowWidthPx > 0)
        return probe to view
    }

    /** 在 [view] 的 (x, y) 发一次按下 + 抬起（中间让主线程跑一轮，手势协程才看得见抬起） */
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
    fun `三页书点行上任意位置都跳到对应页`() {
        // 3 页书：值域 0..2。第 1 页覆盖按下位置 0%–25%，第 2 页 25%–75%，第 3 页 75%–100%。
        // 每一段都取一个**非端点**位置（端点正好是「只有最左/最中/最右有效」那个 bug 的漏网点）。
        for ((fraction, expectedPage) in listOf(0.1f to 0, 0.3f to 1, 0.5f to 1, 0.7f to 1, 0.9f to 2)) {
            val (probe, view) = compose(pageCount = 3, initialPage = 1)
            val y = probe.rowHeightPx / 2f
            tap(view, x = probe.rowWidthPx * fraction, y = y)
            assertEquals(
                "按下在行宽 ${fraction * 100}% 处应跳到页位 $expectedPage（3 页书）",
                listOf(expectedPage),
                probe.seeks.toList(),
            )
        }
    }

    @Test
    fun `长书点行上任意位置也跳到对应页`() {
        // 200 页：值域 0..199，按下比例直接换算页位（线性）
        for ((fraction, expectedPage) in listOf(0.25f to 50, 0.5f to 100, 0.9f to 179)) {
            val (probe, view) = compose(pageCount = 200, initialPage = 0)
            tap(view, x = probe.rowWidthPx * fraction, y = probe.rowHeightPx / 2f)
            assertEquals(
                "按下在行宽 ${fraction * 100}% 处应跳到页位 $expectedPage（200 页书）",
                listOf(expectedPage),
                probe.seeks.toList(),
            )
        }
    }

    @Test
    fun `点当前页所在位置只发当前页不发别的页`() {
        val (probe, view) = compose(pageCount = 3, initialPage = 1)
        tap(view, x = probe.rowWidthPx * 0.5f, y = probe.rowHeightPx / 2f)
        assertEquals("按下位置对应的就是当前页 → 只发这一页（不会跑到别的页）", listOf(1), probe.seeks.toList())
    }

    @Test
    fun `滑动条行的命中高度不小于 48dp`() {
        val (probe, _) = compose()
        val minPx = (48f * density).roundToInt()
        assertTrue(
            "实测行高 ${probe.rowHeightPx}px（${probe.rowHeightPx / density}dp）必须 ≥ 48dp（触摸目标下限）",
            probe.rowHeightPx >= minPx,
        )
    }
}
