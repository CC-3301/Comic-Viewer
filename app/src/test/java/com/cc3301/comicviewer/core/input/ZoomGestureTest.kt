package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放大态的平移与双指捏合（纯函数落点）：
 * 该页未放大时单指整段让位（列表照旧滚）、放大后单指按斜率起手并逐帧跟手、
 * 双指立即接管并以质心/指距输出变换、捏合抬走一根继续平移不跳变、手势结束即复位。
 */
class ZoomGestureTest {

    /** 触摸斜率 10px */
    private fun gesture() = ZoomGesture(panSlopPx = 10f)

    private fun ZoomGesture.frame(
        count: Int,
        x: Float = 0f,
        y: Float = 0f,
        span: Float = 0f,
        panEnabled: Boolean = false,
    ) = handle(
        ZoomGestureInput.Frame(
            pointerCount = count,
            centroidX = x,
            centroidY = y,
            spanPx = span,
            panEnabled = panEnabled,
        ),
    )

    private fun ZoomGestureStep.pan(): Pair<Float, Float>? =
        effects.filterIsInstance<ZoomGestureEffect.Pan>().firstOrNull()?.let { it.deltaX to it.deltaY }

    private fun ZoomGestureStep.pinch(): ZoomGestureEffect.Pinch? =
        effects.filterIsInstance<ZoomGestureEffect.Pinch>().firstOrNull()

    // ---------- 单指：未放大的页整段让位 ----------

    @Test
    fun `未放大时单指拖动不产生平移也不消费`() {
        val g = gesture()
        g.frame(count = 1, x = 100f, y = 100f, panEnabled = false)
        val step = g.frame(count = 1, x = 400f, y = 380f, panEnabled = false)
        assertTrue("该页未放大：列表滚动照旧，本手势不得介入", step.effects.isEmpty())
        assertFalse("不消费 = 内建滚动与鼠标拖动滚动照旧拿到这段拖动", step.consume)
    }

    // ---------- 单指：放大态平移 ----------

    @Test
    fun `放大态单指斜率以内不动作`() {
        val g = gesture()
        g.frame(count = 1, x = 100f, y = 100f, panEnabled = true)
        val step = g.frame(count = 1, x = 106f, y = 108f, panEnabled = true)
        assertTrue("斜率以内 = 点按，不该挪画面", step.effects.isEmpty())
        assertFalse(step.consume)
    }

    @Test
    fun `放大态单指越过斜率后按增量平移并消费`() {
        val g = gesture()
        g.frame(count = 1, x = 100f, y = 100f, panEnabled = true)
        g.frame(count = 1, x = 105f, y = 100f, panEnabled = true)
        // 越过斜率那一帧：增量只取与上一帧的差（斜率本身不产生跳动）
        val crossed = g.frame(count = 1, x = 118f, y = 104f, panEnabled = true)
        assertEquals(13f to 4f, crossed.pan())
        assertTrue(crossed.consume)
        // 继续拖：逐帧跟手
        val moved = g.frame(count = 1, x = 110f, y = 140f, panEnabled = true)
        assertEquals(-8f to 36f, moved.pan())
        assertTrue(moved.consume)
    }

    @Test
    fun `恰好等于斜率还不算起手`() {
        val g = gesture()
        g.frame(count = 1, x = 0f, y = 0f, panEnabled = true)
        val step = g.frame(count = 1, x = 10f, y = 0f, panEnabled = true)
        assertTrue("判定是严格大于", step.effects.isEmpty())
    }

    @Test
    fun `放大态单指原地不动也继续消费`() {
        // 已在平移：即使这一帧没位移也不得把拖动交还给列表（否则列表会在中途抢走）
        val g = gesture()
        g.frame(count = 1, x = 0f, y = 0f, panEnabled = true)
        g.frame(count = 1, x = 40f, y = 0f, panEnabled = true)
        val step = g.frame(count = 1, x = 40f, y = 0f, panEnabled = true)
        assertEquals(0f to 0f, step.pan())
        assertTrue(step.consume)
    }

    @Test
    fun `平移中途该页退出放大态即让位`() {
        val g = gesture()
        g.frame(count = 1, x = 0f, y = 0f, panEnabled = true)
        g.frame(count = 1, x = 40f, y = 0f, panEnabled = true)
        val step = g.frame(count = 1, x = 90f, y = 0f, panEnabled = false)
        assertTrue(step.effects.isEmpty())
        assertFalse(step.consume)
    }

    // ---------- 双指捏合 ----------

    @Test
    fun `双指首帧只建立基线 第二帧起输出倍率与质心位移`() {
        val g = gesture()
        val first = g.frame(count = 2, x = 100f, y = 200f, span = 50f, panEnabled = true)
        assertTrue("首帧没有可比基线，不产生变换", first.effects.isEmpty())
        assertTrue("但双指立即接管，列表不得再看到这段拖动", first.consume)

        val second = g.frame(count = 2, x = 110f, y = 190f, span = 150f, panEnabled = true)
        val pinch = second.pinch()
        assertNotNull(pinch)
        assertEquals("指距 50 → 150 = 3 倍", 3f, pinch!!.zoomChange, 0.001f)
        assertEquals(10f, pinch.panChangeX, 0.001f)
        assertEquals(-10f, pinch.panChangeY, 0.001f)
        assertEquals(110f, pinch.centroidX, 0.001f)
        assertEquals(190f, pinch.centroidY, 0.001f)
        assertTrue(second.consume)
    }

    @Test
    fun `未放大的页上双指也接管`() {
        val g = gesture()
        val step = g.frame(count = 2, x = 0f, y = 0f, span = 80f, panEnabled = false)
        assertTrue("捏合要先禁掉列表滚动，才能由本手势把画面放大", step.consume)
    }

    @Test
    fun `指距为 0 时不产生倍率`() {
        val g = gesture()
        g.frame(count = 2, x = 0f, y = 0f, span = 0f, panEnabled = true)
        val step = g.frame(count = 2, x = 0f, y = 0f, span = 0f, panEnabled = true)
        assertTrue("两指重合：没有可比基线，不得算出 Infinity/NaN", step.effects.isEmpty())
    }

    @Test
    fun `捏合抬走一根手指后继续平移且不跳变`() {
        val g = gesture()
        g.frame(count = 2, x = 100f, y = 100f, span = 50f, panEnabled = true)
        g.frame(count = 2, x = 120f, y = 100f, span = 100f, panEnabled = true)
        // 抬走一根：就地重基线，这一帧不得输出位移（否则会按两指质心与单指位置的差跳一下）
        val afterPinch = g.frame(count = 1, x = 120f, y = 100f, panEnabled = true)
        assertTrue(afterPinch.effects.isEmpty())
        assertTrue(afterPinch.consume)
        // 继续拖：直接平移，不再过一次斜率
        val moved = g.frame(count = 1, x = 121f, y = 160f, panEnabled = true)
        assertEquals(1f to 60f, moved.pan())
    }

    // ---------- 手势结束 ----------

    @Test
    fun `手势结束后重新按斜率判定`() {
        val g = gesture()
        g.frame(count = 1, x = 0f, y = 0f, panEnabled = true)
        g.frame(count = 1, x = 40f, y = 0f, panEnabled = true)
        g.frame(count = 0)
        // 新的一段：未放大 → 让位；放大 → 重新从斜率起手
        assertFalse(g.frame(count = 1, x = 200f, y = 200f, panEnabled = false).consume)
        val step = g.frame(count = 1, x = 205f, y = 200f, panEnabled = true)
        assertTrue("新的一段仍在斜率以内", step.effects.isEmpty())
    }
}
