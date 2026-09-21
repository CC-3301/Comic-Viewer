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
 * 判别力：
 * ① 行高实测必须 ≥ 48dp——AC7「可点击区域加大」的下限（改动前是 32dp 高的文字按钮）；
 * ② 行**左缘附近**（行宽 1/8 处）与**右缘附近**（7/8 处）分别触发上/下一本：这两个点落在页码两侧的空白区里，
 *    只有「整个槽位都是按钮」才点得中——改成从前那种窄按钮（或把按钮贴回屏幕角）就点不中；
 * ③ 行中点（页码文字处）两个回调都不触发：命中区确实被页码占着，不是整行一个按钮。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：按钮**文字在槽位里居中**只由代码结构（`contentAlignment = Center`）
 * 与真机目视把守——Robolectric 的字体度量是 stub，量不出文字盒的真实居中；页码**严格居中**同理
 * （结构保证：中央 Text 不参与权重、两侧各 weight(1f)）。
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
    fun `行右缘附近的空白区点击触发下一本`() {
        val (probe, view) = compose()
        tap(view, x = probe.rowWidthPx * 7f / 8f, y = probe.rowHeightPx / 2f)
        assertEquals("右侧空白区应触发下一本", 1, probe.nextClicks)
        assertEquals("不应触发上一本", 0, probe.prevClicks)
    }

    @Test
    fun `可点区域一直铺到行的左右两端`() {
        // AC7「可点击区域 = 整份空白区」：贴着行两端也要点得中（从前那个窄按钮点不中这两个点）
        val (probe, view) = compose()
        tap(view, x = 1f, y = probe.rowHeightPx / 2f)
        tap(view, x = probe.rowWidthPx - 1f, y = probe.rowHeightPx / 2f)
        assertEquals("贴左缘应触发上一本", 1, probe.prevClicks)
        assertEquals("贴右缘应触发下一本", 1, probe.nextClicks)
    }

    @Test
    fun `行中点（页码处）不触发上下一本`() {
        val (probe, view) = compose()
        tap(view, x = probe.rowWidthPx / 2f, y = probe.rowHeightPx / 2f)
        assertFalse("页码不是按钮", probe.prevClicks > 0)
        assertFalse("页码不是按钮", probe.nextClicks > 0)
    }
}
