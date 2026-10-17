package com.cc3301.comicviewer.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
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
 * 跳页弹窗的**真实点击位置与真实回调**：真组合生产代码 [JumpToPageDialogContent]，
 * 真往 [ComposeView] 发触摸 / 按键事件，看哪个回调被触发。
 *
 * 组合的是**无状态**那一层（输入内容由用例给）：量「点了会怎样」不必先会打字。
 * 有状态外壳 [JumpToPageDialog] 只多一个 `remember`（打开即空），由 `ReaderMenu` 的接线覆盖。
 *
 * 判别力：
 * ① 输入合法（`1..总页数` 的整数）时点「确定」回传 **0-based 页位**；
 * ② 输入不合法（空 / `0` / 超总页数 / 非数字 / 带小数点）时点「确定」**两个回调都不动**——
 *    「置灰」不是画上去的灰字，而是这条回调不触发；
 * ③ 点弹窗**外**只关弹窗、不跳页；点弹窗**内**（输入框那一带）不关弹窗——
 *    否则点输入框会把弹窗点掉；
 * ④ 宽度真的收口：视口 320dp 下在中心左右各 90dp 处点一下必须落在卡外
 *    （弹窗若被写成铺满屏宽，这两点会在卡内）。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：标题与「确定」的字号一致、输入框文字居中、灰字底文
 * 由结构（同一个字号常量、`TextAlign.Center`、`placeholder` 槽）与设备目视把守——
 * Robolectric 的字体度量是 stub，量不出文字盒的真实居中。宽度算式与输入合法性判据的算例在
 * `core/view/ReaderMenuLayoutTest`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JumpToPageDialogTest {

    private val density: Float = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /** 组合时用的视口宽（dp）：手机竖屏档，弹窗宽因此落到 150dp 下限 */
    private val viewportWidthDp = 393f

    /** 一次组合的观测结果：三个回调各被触发几次、确定回传的页位 */
    private class Probe {
        var confirmCount = 0
        var confirmedIndex = -1
        var dismissCount = 0
    }

    /**
     * 组合生产代码 [JumpToPageDialogContent]（[input] 由用例给），返回观测器与可发事件的 View。
     * 视口固定 [VIEW_WIDTH_DP] × [VIEW_HEIGHT_DP]（Robolectric 默认窗口），卡片在其中居中。
     */
    private fun compose(input: String, pageCount: Int = 1240, currentPage: Int = 3): Pair<Probe, ComposeView> {
        val probe = Probe()
        val view = composeViewInActivity {
            MaterialTheme {
                JumpToPageDialogContent(
                    input = input,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    viewportWidthDp = viewportWidthDp,
                    onInputChange = { },
                    onConfirm = { index ->
                        probe.confirmCount++
                        probe.confirmedIndex = index
                    },
                    onDismiss = { probe.dismissCount++ },
                )
            }
        }
        view.layoutOnce((VIEW_WIDTH_DP * density).roundToInt(), (VIEW_HEIGHT_DP * density).roundToInt())
        assertTrue("弹窗没被放置（测量没生效），本次断言无意义", view.width > 0 && view.height > 0)
        return probe to view
    }

    /**
     * 「确定」文字中心相对卡片中心的纵向偏移（dp）：卡片自上而下是
     * 内边距 + 标题行 + 行距 + 输入框 + 行距 + 确定行 + 内边距，卡片中心在总高一半处。
     */
    private val confirmOffsetDp: Float = run {
        val padding = ReaderMenuLayout.JUMP_DIALOG_PADDING_DP
        val row = ReaderMenuLayout.JUMP_DIALOG_ROW_HEIGHT_DP
        val gap = ReaderMenuLayout.JUMP_DIALOG_ROW_GAP_DP
        val field = ReaderMenuLayout.JUMP_DIALOG_FIELD_HEIGHT_DP
        val total = padding * 2 + row * 2 + gap * 2 + field
        padding + row + gap + field + gap + row / 2f - total / 2f
    }

    /** 「确定」文字中心的 y（px） */
    private fun confirmY(view: View): Float = view.height / 2f + confirmOffsetDp * density

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
    fun `输入合法时点确定回传 0-based 页位`() {
        val (probe, view) = compose(input = "3")
        tap(view, view.width / 2f, confirmY(view))
        assertEquals("点一次确定只触发一次", 1, probe.confirmCount)
        assertEquals("输入 3 = 第 3 页 = 0-based 页位 2", 2, probe.confirmedIndex)
        assertEquals("跳页后关不关弹窗由调用方决定，本组件不自己关", 0, probe.dismissCount)
    }

    @Test
    fun `输入不合法时点确定两个回调都不动`() {
        // 空串 = 刚打开弹窗的样子（确定一开始就是灰的），其余是各种打错的形态
        for (bad in listOf("", "0", "1241", "abc", "3.5")) {
            val (probe, view) = compose(input = bad)
            tap(view, view.width / 2f, confirmY(view))
            assertEquals("输入「$bad」时确定必须点不动", 0, probe.confirmCount)
            assertEquals("点灰着的确定不得顺带关掉弹窗", 0, probe.dismissCount)
        }
    }

    @Test
    fun `输入等于总页数时合法`() {
        val (probe, view) = compose(input = "1240", pageCount = 1240)
        tap(view, view.width / 2f, confirmY(view))
        assertEquals("末页也要能跳", 1, probe.confirmCount)
        assertEquals("末页 = 0-based 页位 1239", 1239, probe.confirmedIndex)
    }

    @Test
    fun `点弹窗外关闭 不跳页`() {
        val (probe, view) = compose(input = "3")
        tap(view, view.width / 2f, 10f * density)
        assertEquals("点弹窗外应关闭弹窗", 1, probe.dismissCount)
        assertEquals("点弹窗外不得跳页", 0, probe.confirmCount)
    }

    @Test
    fun `点弹窗内空白处不关闭`() {
        val (probe, view) = compose(input = "3")
        // 卡片内、但不在确定上：输入框就在卡片正中（= 视口中心）
        tap(view, view.width / 2f, view.height / 2f)
        assertEquals("点卡片内不得关弹窗（否则点输入框会把弹窗点掉）", 0, probe.dismissCount)
        assertEquals("点输入框不得跳页", 0, probe.confirmCount)
    }

    @Test
    fun `弹窗宽度收口到 150dp 而不是铺满屏宽`() {
        // 视口 320dp、弹窗按 393dp 屏算得 150dp ⇒ 半宽 75dp，卡片横向只占 [85, 235]dp。
        // 在中心左右各 90dp 处点一下必须落在卡外；若弹窗被写成铺满屏宽，这两点会在卡内。
        val (probe, view) = compose(input = "3")
        val centerX = view.width / 2f
        val midY = view.height / 2f
        tap(view, centerX - 90f * density, midY)
        assertEquals("中心左 90dp 应在 150dp 卡片之外", 1, probe.dismissCount)
        tap(view, centerX + 90f * density, midY)
        assertEquals("中心右 90dp 应在 150dp 卡片之外", 2, probe.dismissCount)
        assertEquals("卡外两拍都不得跳页", 0, probe.confirmCount)
    }

    @Test
    fun `回车与点确定等价`() {
        // 弹窗打开时输入框不自动聚焦，所以先点一下输入框把它拿上焦点，再发硬件回车。
        // Compose 的单行输入框把 Enter 映射成 IME action（本组件设的是 Done）⇒ 与点确定回同一个 confirm。
        val (probe, view) = compose(input = "3")
        view.requestFocus()
        tap(view, view.width / 2f, view.height / 2f)
        shadowOf(Looper.getMainLooper()).idle()
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("回车必须与点确定等价", 1, probe.confirmCount)
        assertEquals("回车跳的页与点确定一致", 2, probe.confirmedIndex)
    }

    private companion object {
        /** 组合用的视口宽（px 换算的 dp 基准）：Robolectric 默认窗口宽 */
        const val VIEW_WIDTH_DP = 320f

        /** 组合用的视口高：Robolectric 默认窗口高，卡片在其中居中 */
        const val VIEW_HEIGHT_DP = 480f
    }
}
