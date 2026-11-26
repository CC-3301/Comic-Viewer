package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指针自动隐藏的判据（纯逻辑，由 [PointerAutoHide] 承担）。
 *
 * 这一层与 `core/input/` 其余手势同一分工：**判定不是接线**——口径全在这里，`ui/` 只做事件翻译
 * （鼠标悬停移动 / 按住拖动 / 滚轮 / 触摸 / 左键或右键按下）与图标切换。时钟注入，下面每条口径都由用例
 * 直接驱动，不需要组合、帧时钟、真实时间。
 *
 * 钉住的口径（spec §指针自动隐藏）：
 * 1. **3 秒隐藏**：鼠标停止悬停移动满 3 秒隐藏，计时从最后一次悬停移动起算；
 * 2. **隐藏期间移动不恢复**：悬停移动既不恢复指针，也不重置计时；
 * 3. **点击唤醒**：隐藏时的左键或右键按下恢复指针、重新开始 3 秒计时，并返回 true（这一整段被消费）；
 *    可见时返回 false（点击照常），也不重置计时；
 * 4. **立即恢复**：双击放大、面板（阅读菜单 / 跨书条）出现；恢复后重新开始 3 秒计时，面板退场后同样重新计时；
 * 5. **面板在场时不隐藏**：面板是用鼠标点的，指针在这期间藏起来就点不着了，这期间触摸面板也不隐藏；
 * 6. **立即隐藏**：没有面板在场时触摸正文；
 * 7. **不恢复也不重置计时**：可见时的左键拖动与滚轮、以及隐藏期间的一切移动；
 * 8. **窗口失焦按可见处理**：失焦立即恢复并重新计时，失焦期间到点也不隐藏，回焦后从回焦时刻重新计时。
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

    // --- 隐藏期间移动不恢复（口径 2）---

    @Test
    fun `隐藏期间悬停移动不恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        hover()
        assertTrue("隐藏期间的移动不恢复指针", pointer.hidden)

        repeat(10) {
            pass(100)
            hover()
        }
        assertTrue("连续移动多久都不恢复", pointer.hidden)
    }

    @Test
    fun `隐藏期间大幅移动不恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        hover()
        assertTrue(pointer.hidden)

        // 大幅移动、来回摆动都不恢复：判据里没有位移这一条
        repeat(10) {
            hover()
            hover()
        }
        assertTrue(pointer.hidden)
    }

    @Test
    fun `隐藏期间移动不重置计时 唤醒后按唤醒时刻重新计时`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        hover()
        pass(5_000)
        assertTrue(pointer.hidden)

        assertTrue("隐藏时的移动之后点击仍然算唤醒", pointer.onWakeClick())
        assertFalse(pointer.hidden)
        pass(2_999)
        assertFalse("唤醒时重新起算", pointer.hidden)
        pass(1)
        assertTrue("唤醒满 3 秒再次隐藏", pointer.hidden)
    }

    // --- 点击唤醒（口径 3）---

    @Test
    fun `隐藏时点击唤醒并重新计时`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        assertTrue("隐藏时的左键或右键按下：这一段被消费", pointer.onWakeClick())
        assertFalse("点击立即恢复指针", pointer.hidden)

        pass(2_999)
        assertFalse("唤醒后重新计时", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `可见时点击不消费也不重置计时`() {
        pass(2_000)

        assertFalse("可见时的点击照常，不消费", pointer.onWakeClick())
        assertFalse(pointer.hidden)

        pass(999)
        assertFalse("点击不重置隐藏计时", pointer.hidden)

        pass(1)
        assertTrue("计时仍从最后一次悬停移动起算", pointer.hidden)
    }

    @Test
    fun `唤醒后的下一次点击照常`() {
        pass(3_000)

        assertTrue(pointer.onWakeClick())
        assertFalse("第二次按下时指针已可见：不再消费", pointer.onWakeClick())
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

    // --- 窗口失焦按可见处理（口径 8）---

    @Test
    fun `窗口失焦立即恢复并重新计时`() {
        pass(3_000)
        assertTrue(pointer.hidden)

        pointer.setWindowFocused(false)
        assertFalse("失焦立即恢复指针", pointer.hidden)

        pointer.setWindowFocused(true)
        pass(2_999)
        assertFalse("回焦后重新计时", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `失焦期间到点不隐藏`() {
        pointer.setWindowFocused(false)

        pass(10_000)
        assertFalse("失焦期间鼠标不动也不隐藏", pointer.hidden)
        assertEquals("失焦期间没有待处理的计时", 0L, pointer.state().hideDelayMillis)
    }

    @Test
    fun `失焦再回焦后重新计时`() {
        pointer.setWindowFocused(false)
        pass(5_000)

        pointer.setWindowFocused(true)
        pass(2_999)
        assertFalse("回焦时刻起算", pointer.hidden)

        pass(1)
        assertTrue(pointer.hidden)
    }

    // --- 不恢复也不重置计时（口径 7）---

    @Test
    fun `滚轮不恢复也不重置计时`() {
        pass(2_900)
        pointer.onActivity(PointerActivity.WHEEL)
        pass(100)
        assertTrue("滚轮不算移动：3 秒照常到点隐藏", pointer.hidden)

        pointer.onActivity(PointerActivity.WHEEL)
        assertTrue("隐藏后滚轮不恢复", pointer.hidden)

        hover()
        pointer.onActivity(PointerActivity.WHEEL)
        assertTrue("隐藏后滚轮与移动都不恢复", pointer.hidden)
    }

    @Test
    fun `按左键拖动不恢复也不重置计时`() {
        pass(2_900)
        pointer.onActivity(PointerActivity.DRAG_MOVE)
        pass(100)
        assertTrue("拖动不算移动：3 秒照常到点隐藏", pointer.hidden)

        pointer.onActivity(PointerActivity.DRAG_MOVE)
        assertTrue("隐藏后拖动不恢复", pointer.hidden)
    }

    @Test
    fun `拖动不重置隐藏计时`() {
        hover()
        pass(2_000)
        pointer.onActivity(PointerActivity.DRAG_MOVE)
        pass(999)
        assertFalse("拖动不算移动：计时仍从最后一次悬停移动起算", pointer.hidden)

        pass(1)
        assertTrue("拖动之后照常满 3 秒隐藏", pointer.hidden)
    }
}
