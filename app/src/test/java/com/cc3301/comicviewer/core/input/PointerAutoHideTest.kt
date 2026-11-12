package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
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
 * 2. **100dp 净位移**：隐藏后指针离锚点的直线距离满 100dp 恢复，来回摆动互相抵消、不累加；
 * 3. **立即恢复**：双击放大、打开阅读菜单；恢复后重新开始 3 秒计时，菜单关闭后同样重新计时；
 * 4. **菜单开着时不隐藏**：菜单是用鼠标点的，指针在这期间藏起来就点不着了，这期间触摸菜单也不隐藏；
 * 5. **立即隐藏**：菜单关着时触摸正文；
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
    fun `隐藏后离锚点满 100dp 恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        hover(99f)
        assertTrue("离锚点不到 100dp 不恢复", pointer.hidden)

        hover(100f)
        assertFalse("离锚点满 100dp 恢复", pointer.hidden)
    }

    @Test
    fun `隐藏后分段靠近锚点 90dp 不恢复 100dp 恢复`() {
        pass(3_000)

        hover(60f)
        pass(100)
        hover(90f)
        assertTrue("离锚点 90dp 还不恢复", pointer.hidden)

        pass(100)
        hover(100f)
        assertFalse("离锚点满 100dp 恢复", pointer.hidden)
    }

    @Test
    fun `上下来回摆动路径合计超过 100dp 仍保持隐藏`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        // 上下各 10dp 抖动 10 个来回：走过的路程 200dp，离锚点最远只有 10dp
        repeat(10) {
            hover(0f, 10f)
            hover(0f, 0f)
        }
        assertTrue("来回摆动互相抵消：路径再长也不恢复", pointer.hidden)
    }

    @Test
    fun `恢复后再次隐藏 锚点重新记`() {
        pass(3_000)
        hover(100f)
        assertFalse("满 100dp 恢复", pointer.hidden)

        pass(3_000)
        assertTrue("再停 3 秒又隐藏", pointer.hidden)

        hover(150f)
        assertTrue("上一轮的位移不沿用，按新锚点算", pointer.hidden)
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

    @Test
    fun `菜单开着时触摸菜单不隐藏`() {
        pointer.onMenuOpened()

        pointer.onActivity(PointerActivity.TOUCH)
        assertFalse("菜单开着时触摸（落在菜单上）不隐藏指针", pointer.hidden)
    }

    // --- 立即隐藏（口径 5）---

    @Test
    fun `触摸操作立即隐藏`() {
        assertFalse(pointer.hidden)

        pointer.onActivity(PointerActivity.TOUCH)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `菜单关闭后触摸正文立即隐藏`() {
        pointer.onMenuOpened()
        pass(1_000)
        pointer.onMenuClosed()

        pointer.onActivity(PointerActivity.TOUCH)
        assertTrue("菜单关着时触摸正文立即隐藏", pointer.hidden)
    }

    // --- 到点剩余延时（界面只等这个值）---

    @Test
    fun `剩余延时按注入时钟算出`() {
        assertEquals(POINTER_HIDE_DELAY_MILLIS, pointer.state().hideDelayMillis)

        now += 1_000
        assertEquals("剩余值出自注入的那个时钟", 2_000L, pointer.state().hideDelayMillis)
    }

    @Test
    fun `已隐藏与菜单开着时没有剩余延时`() {
        pass(3_000)
        assertEquals("已隐藏：没有待处理的计时", 0L, pointer.state().hideDelayMillis)

        pointer.onDoubleTapZoom()
        assertEquals(POINTER_HIDE_DELAY_MILLIS, pointer.state().hideDelayMillis)

        pointer.onMenuOpened()
        assertEquals("菜单开着：不安排隐藏", 0L, pointer.state().hideDelayMillis)
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
        assertTrue("滚轮不改变锚点：离锚点 99dp 仍隐藏", pointer.hidden)
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
        assertTrue("拖动不改变锚点：离锚点 99dp 仍隐藏", pointer.hidden)
    }
}
