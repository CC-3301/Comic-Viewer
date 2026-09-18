package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下拉更新的手势状态机（票 #53 AC 的纯函数落点）：
 * 一次拖拽只触发一次、未达阈值不触发、**滚轮一律不改变下拉状态**、不在顶部时不介入常规滚动。
 */
class PullRefreshGestureTest {

    /** 阈值 100px、斜率 10px → 越过阈值需要拖拽 200px（阻尼 0.5） */
    private fun gesture() = PullRefreshGesture(thresholdPx = 100f, touchSlopPx = 10f)

    /** 按住并向下拖 [totalPx]（分若干步，[atTop] 默认在顶部） */
    private fun PullRefreshGesture.pullDown(totalPx: Float, atTop: Boolean = true, steps: Int = 10): List<PullEffect> {
        val effects = mutableListOf<PullEffect>()
        effects += handle(PullInput.Down(y = 0f))
        val step = totalPx / steps
        var y = 0f
        repeat(steps) {
            y += step
            effects += handle(PullInput.Drag(y = y, deltaY = step, atTop = atTop))
        }
        return effects
    }

    // ---------- 硬约束：滚轮 ----------

    @Test
    fun `滚轮滚动不改变下拉状态也不产生任何效果`() {
        val g = gesture()
        // 滚轮滑到顶部后继续滚：一串滚动事件
        repeat(50) { g.handle(PullInput.Scroll(deltaY = 120f)) }
        assertEquals("滚轮不得把指示器拉出来", 0f, g.offsetPx, 0f)
        assertEquals(0f, g.progress, 0f)
        assertFalse("滚轮不得达到触发阈值", g.reachedThreshold)
        assertTrue(
            "滚轮之后抬起也不能触发刷新",
            g.handle(PullInput.Up).none { it is PullEffect.Trigger },
        )
    }

    @Test
    fun `滚轮混在拖拽中间不改变已累计的位移`() {
        val g = gesture()
        g.handle(PullInput.Down(y = 0f))
        g.pullDown(totalPx = 40f, steps = 4) // 已越过斜率、位移 40px
        val before = g.offsetPx
        repeat(5) { g.handle(PullInput.Scroll(deltaY = 200f)) }
        assertEquals("滚轮不得推动指示器", before, g.offsetPx, 0f)
    }

    // ---------- 触发判定 ----------

    @Test
    fun `拖拽超过阈值并松手触发一次刷新`() {
        val g = gesture()
        g.pullDown(totalPx = 240f) // 阻尼后 120px > 100px
        assertTrue(g.reachedThreshold)
        val effects = g.handle(PullInput.Up)
        assertEquals(1, effects.count { it is PullEffect.Trigger })
        assertTrue("触发后要回弹复位", effects.any { it is PullEffect.Reset })
        assertEquals(0f, g.offsetPx, 0f)
    }

    @Test
    fun `未达阈值松手只复位不触发`() {
        val g = gesture()
        g.pullDown(totalPx = 100f) // 阻尼后 50px < 100px
        val effects = g.handle(PullInput.Up)
        assertTrue(effects.none { it is PullEffect.Trigger })
        assertTrue(effects.any { it is PullEffect.Reset })
        assertEquals(0f, g.offsetPx, 0f)
    }

    @Test
    fun `刚好等于阈值不触发`() {
        val g = gesture()
        g.pullDown(totalPx = 200f) // 阻尼后恰好 100px
        assertEquals(100f, g.offsetPx, 0.001f)
        assertFalse("阈值判定是严格大于", g.reachedThreshold)
        assertTrue(g.handle(PullInput.Up).none { it is PullEffect.Trigger })
    }

    @Test
    fun `一次拖拽只触发一次`() {
        val g = gesture()
        g.pullDown(totalPx = 300f)
        val first = g.handle(PullInput.Up)
        assertEquals(1, first.count { it is PullEffect.Trigger })
        // 松手后的多余事件（同一次拖拽的尾巴）不得再触发
        val after = listOf(g.handle(PullInput.Drag(y = 400f, deltaY = 100f, atTop = true)), g.handle(PullInput.Up))
            .flatten()
        assertTrue("松手后位移已归零，不得再次触发", after.none { it is PullEffect.Trigger })
        assertEquals(0f, g.offsetPx, 0f)
    }

    @Test
    fun `取消的手势复位且不触发`() {
        val g = gesture()
        g.pullDown(totalPx = 400f)
        val effects = g.handle(PullInput.Cancel)
        assertTrue(effects.none { it is PullEffect.Trigger })
        assertTrue(effects.any { it is PullEffect.Reset })
        assertEquals(0f, g.offsetPx, 0f)
    }

    // ---------- 常规滚动不被误伤 ----------

    @Test
    fun `不在顶部时的向下拖拽不进入下拉`() {
        val g = gesture()
        val effects = g.pullDown(totalPx = 400f, atTop = false)
        assertTrue("列表不在顶部：整段交回常规滚动", effects.none { it is PullEffect.Offset })
        assertTrue(g.handle(PullInput.Up).none { it is PullEffect.Trigger })
    }

    @Test
    fun `未越过触摸斜率的微小移动不进入下拉`() {
        val g = gesture()
        g.handle(PullInput.Down(y = 0f))
        // 累计只移动 5px（斜率门槛 10px）：不介入（点击/横向滑动不受影响）
        val effects = g.handle(PullInput.Drag(y = 5f, deltaY = 5f, atTop = true))
        assertTrue(effects.isEmpty())
        assertEquals(0f, g.offsetPx, 0f)
    }

    @Test
    fun `向上推时位移不出现负值`() {
        val g = gesture()
        g.pullDown(totalPx = 60f)
        val before = g.offsetPx
        val effects = g.handle(PullInput.Drag(y = -200f, deltaY = -200f, atTop = true))
        assertEquals("向上推只回落，不产生负位移", 0f, g.offsetPx, 0f)
        assertTrue(before > 0f)
        assertTrue(effects.any { it is PullEffect.Offset })
    }

    @Test
    fun `向上推回零之后继续上推不消费增量 列表照常滚动`() {
        val g = gesture()
        g.pullDown(totalPx = 60f)
        // 第一次上推：把位移拉回 0（指示器回弹），本次仍在下拉语义内
        val toZero = g.handle(PullInput.Drag(y = -200f, deltaY = -300f, atTop = true))
        assertTrue(toZero.any { it is PullEffect.Offset })
        assertEquals(0f, g.offsetPx, 0f)
        // 继续上推：位移已是 0 ⇒ 不再消费，交回常规滚动（否则列表滑不上去）
        val up = g.handle(PullInput.Drag(y = -400f, deltaY = -200f, atTop = true))
        assertTrue("向上推到底后不再消费增量", up.isEmpty())
        assertTrue(g.handle(PullInput.Up).none { it is PullEffect.Trigger })
    }
}
