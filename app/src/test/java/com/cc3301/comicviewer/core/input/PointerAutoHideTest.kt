package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指针自动隐藏的判据（纯逻辑，由 [PointerAutoHide] 承担）。
 *
 * 这一层与 `core/input/` 其余手势同一分工：**判定不是接线**——口径全在这里，`ui/` 只做事件翻译
 * （鼠标悬停移动 / 按住拖动 / 滚轮 / 触摸）与图标切换。时钟注入，下面每条口径都由用例直接驱动，
 * 不需要组合、帧时钟、真实时间。
 *
 * 钉住的口径（spec §指针自动隐藏）：
 * 1. **3 秒隐藏**：鼠标停止移动满 3 秒隐藏，计时从最后一次移动起算；
 * 2. **100dp 死区**：隐藏后累计移动满 100dp 恢复，途中停止移动（停顿超过 0.5 秒）从零重新累计；
 * 3. **立即恢复**：双击放大、打开阅读菜单；恢复后重新开始 3 秒计时，菜单关闭后同样重新计时；
 * 4. **菜单开着时不隐藏**：菜单是用鼠标点的，指针在这期间藏起来就点不着了；
 * 5. **立即隐藏**：触摸操作；
 * 6. **不恢复也不重置计时**：滚轮滚动、按左键拖动滚动。
 *
 * 数值一律用合成值（100dp 死区折算成 100px 便于算），不含任何真实设备信息。
 */
class PointerAutoHideTest {

    /** 死区 100「dp」= 100px（本用例只看判据，不涉及密度换算） */
    private val deadZonePx = 100f

    private var now = 0L

    private val pointer = PointerAutoHide(recoverDistancePx = deadZonePx) { now }

    /** 时间前进（不改变指针状态，只把时钟推过去；隐藏与否由 [PointerAutoHide.advance] 收敛） */
    private fun pass(ms: Long) {
        now += ms
        pointer.advance()
    }

    private fun hover(x: Float, y: Float = 0f) = pointer.onActivity(PointerActivity.HOVER_MOVE, x, y)

    // --- 3 秒隐藏（口径 1）---

    @Test
    fun `停止移动满 3 秒隐藏`() {
        pass(2_999)
        assertFalse("差 1 毫秒还不隐藏", pointer.hidden)

        pass(1)
        assertTrue("满 3 秒隐藏", pointer.hidden)
    }

    @Test
    fun `计时从最后一次移动起算`() {
        pass(2_000)
        hover(10f)

        pass(2_999)
        assertFalse("最后一次移动之后还没满 3 秒", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    // --- 100dp 死区（口径 2）---

    @Test
    fun `隐藏后累计移动满 100dp 恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        hover(99f)
        assertTrue("不到 100dp 不恢复", pointer.hidden)

        hover(100f)
        assertFalse("累计满 100dp 恢复", pointer.hidden)
    }

    @Test
    fun `隐藏后分段移动照常累计`() {
        pass(3_000)

        hover(60f)
        pass(100)
        hover(90f)
        assertTrue("90dp 还没到死区", pointer.hidden)

        pass(100)
        hover(100f)
        assertFalse("第三段补满 100dp", pointer.hidden)
    }

    @Test
    fun `两次移动之间停顿超过 0_5 秒从零重新累计`() {
        pass(3_000)

        hover(60f)
        pass(600) // 停顿超过 0.5 秒
        hover(120f)
        assertTrue("停顿后从零重算：60dp 不够", pointer.hidden)

        pass(100)
        hover(160f)
        assertFalse("再移 40dp 补满 100dp", pointer.hidden)
    }

    @Test
    fun `恢复后再次隐藏 累计从零重新开始`() {
        pass(3_000)
        hover(100f)
        assertFalse("满 100dp 恢复", pointer.hidden)

        pass(3_000)
        assertTrue("再停 3 秒又隐藏", pointer.hidden)

        hover(150f)
        assertTrue("上一轮的累计不沿用", pointer.hidden)
    }

    // --- 立即恢复（口径 3）---

    @Test
    fun `双击放大恢复并重新计时`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        pointer.onDoubleTapZoom()
        assertFalse("双击放大立即恢复", pointer.hidden)

        pass(2_999)
        assertFalse("恢复后重新计时", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `打开菜单立即恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        pointer.onMenuOpened()
        assertFalse("打开菜单立即恢复", pointer.hidden)
    }

    @Test
    fun `菜单开着时指针不隐藏`() {
        pointer.onMenuOpened()

        pass(10_000)
        assertFalse("菜单开着时鼠标不动也保持可见", pointer.hidden)
    }

    @Test
    fun `菜单关闭后重新计时`() {
        pointer.onMenuOpened()
        pass(5_000)

        pointer.onMenuClosed()
        pass(2_999)
        assertFalse("关闭菜单时重新起算", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    // --- 立即隐藏（口径 5）---

    @Test
    fun `触摸操作立即隐藏`() {
        assertFalse(pointer.hidden)

        pointer.onActivity(PointerActivity.TOUCH)
        assertTrue(pointer.hidden)
    }

    // --- 不恢复也不重置计时（口径 6）---

    @Test
    fun `滚轮滚动不恢复也不重置计时`() {
        pass(2_900)
        pointer.onActivity(PointerActivity.WHEEL)
        pass(100)
        assertTrue("滚轮不算移动：3 秒照常到点隐藏", pointer.hidden)

        pointer.onActivity(PointerActivity.WHEEL)
        assertTrue("隐藏后滚轮不恢复", pointer.hidden)

        hover(99f)
        assertTrue("滚轮也不累计距离", pointer.hidden)
    }

    @Test
    fun `按左键拖动不恢复也不重置计时`() {
        pass(2_900)
        pointer.onActivity(PointerActivity.DRAG_MOVE, 10f, 10f)
        pass(100)
        assertTrue("拖动不算移动：3 秒照常到点隐藏", pointer.hidden)

        pointer.onActivity(PointerActivity.DRAG_MOVE, 90f, 90f)
        assertTrue("隐藏后拖动不恢复", pointer.hidden)

        hover(99f)
        assertTrue("拖动也不累计距离", pointer.hidden)
    }
}
