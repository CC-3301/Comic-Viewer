package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的手势判定（票 #60，纯逻辑，由 [QuickScrollBarGesture] 承担）。
 *
 * 判定的三条在票面/评审里都有明确来路，逐条被下面的用例钉住：
 * 1. **只认主键**（与票 #69 的鼠标拖动同口径）：右键/中键按下整段不成手势；
 * 2. **越过触摸斜率才算拖动**：因此「压一下滑条」不带出定位（票面只要求拖动）；
 * 3. **按下即上报按住状态**（[QuickScrollBarEffect.Hold]）：界面据此在按住期间不隐藏滑条——
 *    少了这条，按住超过淡化窗（约 1.45s）再拖就会因为抓取带被整条移除、手势协程被取消而完全没反应
 *    （票 #60 r1 评审 spec P2-2 的真交互 bug）。
 *
 * 滚轮也在这一层：滑条带是命中路径上的最上层，**落在这条带里的滚轮事件到不了列表**，因此由本状态机把它
 * 换算成列表的原始位移（界面侧直接交给滚动状态）。换算必须与 foundation 内建滚动同一条
 * （`AndroidConfig.calculateMouseWheelScroll`：`Σ scrollDelta × -(64.dp)`），符号错了滚轮方向就会反过来，
 * 本用例的符号断言就是这个风险的判据。
 *
 * 算例与 `QuickScrollBarTest` 同一组：1000 条、轨道 2000px、滑条 24px、斜率 8px、64px/滚轮单位。
 */
class QuickScrollBarGestureTest {

    private val gesture = QuickScrollBarGesture(touchSlopPx = 8f, wheelPixelsPerUnit = 64f)

    /** 拖动输入要的当前几何（与 `QuickScrollBarTest` 同一组算例） */
    private fun drag(y: Float) = QuickScrollBarInput.Drag(
        y = y,
        totalItems = 1000,
        trackLengthPx = 2000f,
        thumbLengthPx = 24f,
    )

    private fun seekIndex(effects: List<QuickScrollBarEffect>): Int =
        (effects.single() as QuickScrollBarEffect.Seek).index

    private fun scrollPx(effects: List<QuickScrollBarEffect>): Float =
        (effects.single() as QuickScrollBarEffect.ScrollBy).deltaPx

    // --- 按住：界面据此不隐藏滑条（r1 评审 spec P2-2） ---

    @Test
    fun `按下即上报按住`() {
        assertEquals(listOf(QuickScrollBarEffect.Hold(true)), gesture.handle(QuickScrollBarInput.Down(y = 1000f)))
        assertTrue(gesture.holding)
    }

    @Test
    fun `按住期间不动不上报定位 但按住状态保持`() {
        gesture.handle(QuickScrollBarInput.Down(y = 1000f))
        // 4px ≤ 斜率 8px：还在「压着」，不是拖动
        assertTrue(gesture.handle(drag(1004f)).isEmpty())
        assertTrue(gesture.holding)
        assertTrue(gesture.handle(drag(992f)).isEmpty())
        assertTrue(gesture.holding)
    }

    @Test
    fun `按住两秒再拖仍能定位`() {
        gesture.handle(QuickScrollBarInput.Down(y = 1000f))
        // 模拟按住 2 秒（60Hz 约 120 帧）：每一帧都还在按，状态机不自行结束手势
        repeat(120) {
            assertTrue(gesture.handle(drag(1000f)).isEmpty())
            assertTrue(gesture.holding)
        }
        // 这才开始拖：越过斜率即定位（真机对应「按住 2 秒 → 拖到轨道中部」）
        assertEquals(505, seekIndex(gesture.handle(drag(1010f))))
    }

    @Test
    fun `抬手清按住 二次抬手无效果`() {
        gesture.handle(QuickScrollBarInput.Down(y = 1000f))
        assertEquals(listOf(QuickScrollBarEffect.Hold(false)), gesture.handle(QuickScrollBarInput.Up))
        assertFalse(gesture.holding)
        assertTrue(gesture.handle(QuickScrollBarInput.Up).isEmpty())
    }

    @Test
    fun `手势被取消同样清按住`() {
        gesture.handle(QuickScrollBarInput.Down(y = 1000f))
        assertEquals(listOf(QuickScrollBarEffect.Hold(false)), gesture.handle(QuickScrollBarInput.Cancel))
        assertFalse(gesture.holding)
    }

    @Test
    fun `松手后可以再次按下并拖动`() {
        gesture.handle(QuickScrollBarInput.Down(y = 1000f))
        gesture.handle(QuickScrollBarInput.Up)
        assertEquals(listOf(QuickScrollBarEffect.Hold(true)), gesture.handle(QuickScrollBarInput.Down(y = 1000f)))
        assertEquals(505, seekIndex(gesture.handle(drag(1010f))))
    }

    // --- 拖动：越斜率才定位、跟手、夹在两端 ---

    @Test
    fun `越过斜率后逐步跟手定位`() {
        gesture.handle(QuickScrollBarInput.Down(y = 1000f))
        // 轨道中部 → 0-based 500 附近（纯函数算例，见 QuickScrollBarTest）
        assertEquals(505, seekIndex(gesture.handle(drag(1010f))))
        assertEquals(999, seekIndex(gesture.handle(drag(2000f))))
        assertEquals(0, seekIndex(gesture.handle(drag(-100f))))
        assertTrue(gesture.holding)
    }

    // --- 只认主键（票 #69 同口径） ---

    @Test
    fun `右键按下整段不成手势`() {
        assertTrue(gesture.handle(QuickScrollBarInput.Down(y = 1000f, secondaryMouse = true)).isEmpty())
        assertFalse(gesture.holding)
        // 之后的移动也不产生定位（本次按下没被接）
        assertTrue(gesture.handle(drag(1010f)).isEmpty())
        assertTrue(gesture.handle(QuickScrollBarInput.Up).isEmpty())
    }

    // --- 滚轮：带内滚轮代列表算位移（r1 评审 spec P2-3） ---

    @Test
    fun `滚轮换算与内建滚动同一条 滚动方向不反`() {
        // Compose 的 scrollDelta 约定（ui `MotionEventAdapter` 把 AXIS_VSCROLL 取反）：滚轮向下 = 负。
        // 列表原始位移正 = 向后滚（与 foundation `AndroidConfig.calculateMouseWheelScroll` 的 ΣscrollDelta × -(64dp) 一致）
        assertEquals(64f, scrollPx(gesture.handle(QuickScrollBarInput.Scroll(deltaY = -1f))), 0.01f)
        assertEquals(-64f, scrollPx(gesture.handle(QuickScrollBarInput.Scroll(deltaY = 1f))), 0.01f)
        assertEquals(160f, scrollPx(gesture.handle(QuickScrollBarInput.Scroll(deltaY = -2.5f))), 0.01f)
        assertEquals(0f, scrollPx(gesture.handle(QuickScrollBarInput.Scroll(deltaY = 0f))), 0.01f)
    }

    @Test
    fun `按住时滚轮照样换算且不清按住`() {
        gesture.handle(QuickScrollBarInput.Down(y = 1000f))
        assertEquals(-64f, scrollPx(gesture.handle(QuickScrollBarInput.Scroll(deltaY = 1f))), 0.01f)
        assertTrue(gesture.holding)
        assertEquals(505, seekIndex(gesture.handle(drag(1010f))))
    }

    // --- 哪种效果算一次「动作」：界面据此刷新出现/隐藏计时（票面 AC11 / r1 评审 spec P2-2） ---

    @Test
    fun `拖动定位与带内滚轮都算一次动作`() {
        assertTrue(QuickScrollBarEffect.Seek(0).countsAsActivity())
        // 带内滚轮**必须**算进来：它由滑条代列表滚，不一定改变首个可见条目索引（网格档一行 ≈ 220dp
        // > 一个滚轮单位的 64dp），界面那条「滚动读数变了才现身」的订阅会漏掉它 → 连续带内滚轮时
        // 滑条会在滚动中淡出（r1 评审 spec P2-2）
        assertTrue(QuickScrollBarEffect.ScrollBy(64f).countsAsActivity())
    }

    @Test
    fun `按住与松手不算动作 倒计时由界面按按住状态冻结`() {
        // 「按住或拖动期间不计时」是另一条规则（界面按 Hold 冻结倒计时），不靠活动计数
        assertFalse(QuickScrollBarEffect.Hold(true).countsAsActivity())
        assertFalse(QuickScrollBarEffect.Hold(false).countsAsActivity())
    }
}
