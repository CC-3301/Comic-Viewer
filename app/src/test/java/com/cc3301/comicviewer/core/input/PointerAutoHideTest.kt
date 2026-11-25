package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指针自动隐藏的判据（纯逻辑，由 [PointerAutoHide] 承担）。
 *
 * 这一层与 `core/input/` 其余手势同一分工：**判定不是接线**——口径全在这里，`ui/` 只做事件翻译
 * （鼠标悬停移动 / 按住拖动 / 滚轮 / 触摸 / 左键或右键按下）与图标切换。时钟注入，下面每条口径都由用例直接驱动，
 * 不需要组合、帧时钟、真实时间。
 *
 * 钉住的口径（spec §指针自动隐藏）：
 * 1. **3 秒隐藏**：鼠标停止移动满 3 秒隐藏，计时从最后一次悬停移动起算；
 * 2. **移动不恢复**：隐藏期间的悬停移动、左键拖动与滚轮都不恢复，也不重置计时；
 * 3. **点击唤醒**：隐藏后的鼠标左键或右键按下立即恢复并重新开始计时（[PointerAutoHide.onWakeClick]
 *    返回 true = 这一次按下整段被消费）；可见时的按下不唤醒、也不重置计时，抬起后的下一次点击照常处理；
 * 4. **立即恢复**：双击放大、面板（阅读菜单 / 跨书条）出现；恢复后重新开始计时，面板退场后同样重新计时；
 * 5. **面板在场时不隐藏**：面板是用鼠标点的，指针在这期间藏起来就点不着了；这期间触摸面板也不隐藏；
 * 6. **立即隐藏**：没有面板在场时触摸正文。
 *
 * 数值一律用合成值，不含任何真实设备信息。
 */
class PointerAutoHideTest {

    private var now = 0L

    private val pointer = PointerAutoHide { now }

    /** 时间前进（不改变指针状态，只把时钟推过去；隐藏与否由 [PointerAutoHide.advance] 收敛） */
    private fun pass(ms: Long) {
        now += ms
        pointer.advance()
    }

    private fun hover() = pointer.onActivity(PointerActivity.HOVER_MOVE)

    /**
     * 鼠标左键或右键按下（左键与右键同一口径：界面侧两条路径各调用一次 [PointerAutoHide.onWakeClick]）。
     * true = 这次按下唤醒了指针，整段被消费。
     */
    private fun wakeClick() = pointer.onWakeClick()

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
        hover()

        pass(2_999)
        assertFalse("最后一次移动之后还没满 3 秒", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    // --- 移动不恢复（口径 2）---

    @Test
    fun `隐藏后悬停移动不恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        hover()
        assertTrue("隐藏后悬停移动不恢复", pointer.hidden)

        repeat(10) { hover() }
        assertTrue("连续移动与来回摆动都不恢复", pointer.hidden)

        pass(10_000)
        hover()
        assertTrue("隐藏后过了很久再移动也不恢复", pointer.hidden)
    }

    @Test
    fun `拖动不恢复也不重置计时`() {
        pass(2_900)
        pointer.onActivity(PointerActivity.DRAG_MOVE)
        pass(100)
        assertTrue("拖动不算移动：3 秒照常到点隐藏", pointer.hidden)

        pointer.onActivity(PointerActivity.DRAG_MOVE)
        assertTrue("隐藏后左键拖动不恢复", pointer.hidden)
    }

    @Test
    fun `滚轮不恢复也不重置计时`() {
        pass(2_900)
        pointer.onActivity(PointerActivity.WHEEL)
        pass(100)
        assertTrue("滚轮不算移动：3 秒照常到点隐藏", pointer.hidden)

        pointer.onActivity(PointerActivity.WHEEL)
        assertTrue("隐藏后滚轮不恢复", pointer.hidden)
    }

    @Test
    fun `隐藏后触摸不唤醒`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        pointer.onActivity(PointerActivity.TOUCH)
        assertTrue("触摸不是点击唤醒：仍然隐藏", pointer.hidden)
    }

    // --- 点击唤醒（口径 3）---

    @Test
    fun `隐藏后按下唤醒并重新计时`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        assertTrue("隐藏后的按下唤醒指针，并声明这一次按下整段被消费", wakeClick())
        assertFalse("按下立即恢复", pointer.hidden)

        pass(2_999)
        assertFalse("唤醒后重新开始计时", pointer.hidden)

        pass(1)
        assertTrue("唤醒后满 3 秒再隐藏", pointer.hidden)
    }

    @Test
    fun `唤醒后的下一次按下不唤醒也不重置计时`() {
        pass(3_000)
        assertTrue(wakeClick())
        assertFalse(pointer.hidden)

        pass(2_000)
        assertFalse("可见时的按下不算唤醒，也不消费", wakeClick())
        assertFalse(pointer.hidden)

        pass(999)
        assertFalse("可见时的按下不重置计时", pointer.hidden)

        pass(1)
        assertTrue("仍按最后一次恢复起算", pointer.hidden)
    }

    @Test
    fun `再次隐藏后按下仍唤醒`() {
        pass(3_000)
        assertTrue(wakeClick())
        pass(3_000)
        assertTrue(pointer.hidden)

        assertTrue("重新隐藏后的按下再次唤醒", wakeClick())
        assertFalse(pointer.hidden)
    }

    // --- 捕获丢失 ---
    @Test
    fun `捕获丢失按可见处理并重新计时`() {
        pass(3_000)
        assertTrue(pointer.hidden)
        pointer.onCaptureLost()
        assertFalse("捕获没生效就不能假装隐藏", pointer.hidden)
        pass(2_999)
        assertFalse("捕获丢失后重新开始计时", pointer.hidden)
        pass(1)
        assertTrue("满 3 秒再隐藏", pointer.hidden)
    }

    @Test
    fun `捕获丢失后悬停移动重新计时`() {
        pass(3_000)
        assertTrue(pointer.hidden)
        pointer.onCaptureLost()
        pass(2_000)
        hover()
        pass(2_999)
        assertFalse("移动把隐藏计时推到移动那一刻", pointer.hidden)
        pass(1)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `菜单开着时按下不唤醒`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)

        assertFalse("面板在场时指针本来就可见，按下不消费", wakeClick())
        assertFalse(pointer.hidden)
    }

    // --- 立即恢复（口径 4）---

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

        pointer.setPanelPresent(PointerPanel.MENU, true)
        assertFalse("打开菜单立即恢复", pointer.hidden)
    }

    @Test
    fun `菜单开着时指针不隐藏`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pass(10_000)
        assertFalse("菜单开着时鼠标不动也保持可见", pointer.hidden)
    }

    @Test
    fun `菜单关闭后重新计时`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pass(5_000)
        pointer.setPanelPresent(PointerPanel.MENU, false)

        pass(2_999)
        assertFalse("关闭菜单时重新起算", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `菜单开着时触摸菜单不隐藏`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pointer.onActivity(PointerActivity.TOUCH)
        assertFalse("菜单开着时触摸（落在菜单上）不隐藏指针", pointer.hidden)
    }

    // --- 跨书条与菜单同一口径（口径 4 / 5）---

    @Test
    fun `跨书条出现立即恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, true)
        assertFalse("跨书条出现立即恢复", pointer.hidden)

        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, false)
        pass(2_999)
        assertFalse("退场后重新计时", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `跨书条在场期间到点不隐藏`() {
        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, true)
        pass(10_000)
        assertFalse("跨书条在场时鼠标不动也保持可见", pointer.hidden)
    }

    @Test
    fun `跨书条退场后重新计时`() {
        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, true)
        pass(5_000)
        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, false)

        pass(2_999)
        assertFalse("退场时重新起算", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    // --- 立即隐藏（口径 6）---

    @Test
    fun `触摸操作立即隐藏`() {
        assertFalse(pointer.hidden)

        pointer.onActivity(PointerActivity.TOUCH)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `菜单关闭后触摸正文立即隐藏`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pass(1_000)
        pointer.setPanelPresent(PointerPanel.MENU, false)

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

        pointer.setPanelPresent(PointerPanel.MENU, true)
        assertEquals("菜单开着：不安排隐藏", 0L, pointer.state().hideDelayMillis)
    }
}
