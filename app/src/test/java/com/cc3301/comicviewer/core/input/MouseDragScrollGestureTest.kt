package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鼠标左键拖动滚动（票 #69 验收的纯函数落点）：
 * 触摸斜率以内不算拖动（左键单击不受影响）、越过斜率后按增量跟手滚动、松手按指针速度给惯性、
 * 被下拉更新消费的整段让位、触摸源一律不介入。
 */
class MouseDragScrollGestureTest {

    /** 触摸斜率 10px */
    private fun gesture() = MouseDragScrollGesture(touchSlopPx = 10f)

    private fun MouseDragScrollGesture.press(y: Float = 0f, fromMousePrimary: Boolean = true) =
        handle(MouseDragInput.Down(y = y, fromMousePrimary = fromMousePrimary))

    private fun MouseDragScrollGesture.moveTo(y: Float, consumed: Boolean = false) =
        handle(MouseDragInput.Drag(y = y, consumed = consumed))

    /** 本次输入产生的滚动量（px，正 = 向后滚 / 内容上移） */
    private fun List<MouseDragEffect>.scrolledBy(): Float =
        filterIsInstance<MouseDragEffect.ScrollBy>().sumOf { it.deltaPx.toDouble() }.toFloat()

    private fun List<MouseDragEffect>.fling(): Float? =
        filterIsInstance<MouseDragEffect.Fling>().firstOrNull()?.velocityPxPerSec

    // ---------- 与点击的分界：触摸斜率 ----------

    @Test
    fun `触摸斜率以内的移动不算拖动 不产生滚动也不消费`() {
        val g = gesture()
        g.press()
        val effects = g.moveTo(y = 5f)
        assertTrue("斜率以内 = 点击，本修饰符不得介入", effects.isEmpty())
        assertEquals(0f, effects.scrolledBy(), 0f)
    }

    @Test
    fun `恰好等于触摸斜率还不算拖动`() {
        val g = gesture()
        g.press()
        assertTrue("判定是严格大于（与下拉更新同一口径）", g.moveTo(y = 10f).isEmpty())
    }

    // ---------- 跟手滚动 ----------

    @Test
    fun `越过触摸斜率后按增量滚动 方向与鼠标反向`() {
        val g = gesture()
        g.press(y = 0f)
        // 鼠标向下拖 20px（越过 10px 斜率）：内容跟手向下 = 向后回滚，滚动量为负
        assertEquals(-20f, g.moveTo(y = 20f).scrolledBy(), 0f)
        // 继续向下拖 30px：增量照样跟手，不被斜率吃掉
        assertEquals(-30f, g.moveTo(y = 50f).scrolledBy(), 0f)
        // 反向向上拖 200px：内容向上 = 向后滚，滚动量为正
        assertEquals(200f, g.moveTo(y = -150f).scrolledBy(), 0f)
    }

    @Test
    fun `越过斜率那一步就把整段增量跟上`() {
        val g = gesture()
        g.press(y = 0f)
        // 一步越过斜率：整段 40px 都跟上（不因斜率扣掉一段位移）
        assertEquals(-40f, g.moveTo(y = 40f).scrolledBy(), 0f)
    }

    @Test
    fun `纯横向拖动（纵向位移为 0）不产生滚动`() {
        val g = gesture()
        g.press(y = 0f)
        // 纵向累计没越过斜率：横向拖动留给横向手势（阅读器里放大后的平移）
        assertTrue(g.moveTo(y = 0f).isEmpty())
    }

    // ---------- 松手惯性 ----------

    @Test
    fun `松手按指针速度给惯性 方向与滚动一致`() {
        val g = gesture()
        g.press(y = 0f)
        g.moveTo(y = -20f)
        // 指针向上 1500px/s → 内容继续向上 = 向后滚 1500px/s
        assertEquals(1500f, g.handle(MouseDragInput.Up(velocityY = -1500f)).fling()!!, 0f)
    }

    @Test
    fun `没进入拖动的抬手不给惯性`() {
        val g = gesture()
        g.press()
        g.moveTo(y = 5f) // 斜率以内 = 点击
        assertTrue("点击不得带出惯性", g.handle(MouseDragInput.Up(velocityY = -1500f)).fling() == null)
    }

    @Test
    fun `一次拖动只给一次惯性`() {
        val g = gesture()
        g.press()
        g.moveTo(y = -20f)
        assertEquals(1500f, g.handle(MouseDragInput.Up(velocityY = -1500f)).fling()!!, 0f)
        assertTrue("同一次拖动的尾巴不得再给惯性", g.handle(MouseDragInput.Up(velocityY = -1500f)).fling() == null)
    }

    @Test
    fun `取消的手势复位且不给惯性`() {
        val g = gesture()
        g.press()
        g.moveTo(y = -20f)
        assertTrue(g.handle(MouseDragInput.Cancel).isEmpty())
        assertTrue(g.handle(MouseDragInput.Up(velocityY = -1500f)).fling() == null)
    }

    // ---------- 与下拉更新的分层 ----------

    @Test
    fun `被外层消费的拖动整段让位 之后不再滚动`() {
        val g = gesture()
        g.press(y = 0f)
        // 列表在顶部向下拖：下拉更新先消费掉这一段
        assertTrue(g.moveTo(y = 30f, consumed = true).isEmpty())
        // 本次按下不再介入（不出现「又刷新又滚动」）
        assertTrue(g.moveTo(y = 60f).isEmpty())
        assertTrue("让位之后抬手也不给惯性", g.handle(MouseDragInput.Up(velocityY = -1500f)).fling() == null)
    }

    // ---------- 触摸源不介入（回归） ----------

    @Test
    fun `触摸源（含右键）的拖动不产生任何效果`() {
        val g = gesture()
        g.press(y = 0f, fromMousePrimary = false)
        assertTrue(g.moveTo(y = 200f).isEmpty())
        assertTrue(g.handle(MouseDragInput.Up(velocityY = -1500f)).fling() == null)
    }

    // ---------- 下一次按下从头开始 ----------

    @Test
    fun `松开后再次按下重新累计斜率`() {
        val g = gesture()
        g.press(y = 0f)
        g.moveTo(y = -20f)
        g.handle(MouseDragInput.Up(velocityY = -1500f))
        g.press(y = 0f)
        // 上一次的位移不得带到这一次：斜率以内的移动仍是点击
        assertTrue(g.moveTo(y = 5f).isEmpty())
        assertEquals(-20f, g.moveTo(y = 25f).scrolledBy(), 0f)
    }
}
