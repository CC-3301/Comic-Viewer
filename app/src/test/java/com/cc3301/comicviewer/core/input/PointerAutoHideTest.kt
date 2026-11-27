package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指针自动隐藏的判据（纯逻辑，由 [PointerAutoHide] 承担）。
 *
 * 这一层与 `core/input/` 其余手势同一分工：**判定不是接线**——口径全在这里，`ui/` 只做事件翻译
 * （鼠标移动 / 触摸 / 左键或右键按下）与图标切换。时钟注入，下面每条口径都由用例直接驱动，
 * 不需要组合、帧时钟、真实时间。
 *
 * 钉住的口径（spec §指针自动隐藏）：
 * 1. **停满 3 秒隐藏**：鼠标停止移动满 3 秒隐藏，计时从最后一次移动起算；
 * 2. **移动即恢复**：隐藏期间任何鼠标移动都按可见处理并重新计时，**没有死区**（判据里根本没有位移这一项）；
 * 3. **点击拦截**：隐藏时的左键或右键按下恢复指针、重新开始计时，并返回 true（这一整段被消费）；
 *    可见时返回 false（点击照常），也不重置计时；抬起后的下一次点击照常；
 * 4. **其他恢复**：双击放大、阅读菜单出现、跨书条出现；恢复后重新开始计时；
 * 5. **面板在场时不隐藏**：这期间触摸面板也不隐藏，退场后重新计时；
 * 6. **立即隐藏**：没有面板在场时触摸正文；
 * 7. **滚轮不参与**：判据没有滚轮这一项输入——界面对滚轮不喂任何东西，因此它既不恢复也不重置计时。
 */
class PointerAutoHideTest {
    private var now = 0L
    private val pointer = PointerAutoHide { now }

    /** 时间前进（不改变指针状态，只把时钟推过去；隐藏与否由 [PointerAutoHide.advance] 收敛） */
    private fun pass(ms: Long) {
        now += ms
        pointer.advance()
    }

    private fun move() = pointer.onActivity(PointerActivity.MOVE)

    // --- 停满 3 秒隐藏 ---
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
        move()
        pass(2_999)
        assertFalse("最后一次移动之后还没满 3 秒", pointer.hidden)
        pass(1)
        assertTrue(pointer.hidden)
    }

    // --- 移动即恢复 ---
    @Test
    fun `隐藏期间任何移动立即恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)
        move()
        assertFalse("隐藏期间的鼠标移动按可见处理", pointer.hidden)
    }

    @Test
    fun `隐藏期间不停移动就一直可见`() {
        pass(3_000)
        move()
        repeat(10) {
            pass(2_999)
            move()
        }
        assertFalse("每动一次都重新计时，永远差最后 1 毫秒", pointer.hidden)
    }

    @Test
    fun `恢复后重新计时`() {
        pass(3_000)
        move()
        pass(2_999)
        assertFalse("恢复后从这次移动重新起算", pointer.hidden)
        pass(1)
        assertTrue("重新计满 3 秒再次隐藏", pointer.hidden)
    }

    // --- 点击拦截 ---
    @Test
    fun `隐藏时按下只唤醒并消费这一段`() {
        pass(3_000)
        assertTrue(pointer.hidden)
        assertTrue("隐藏时的左键或右键按下：这一段被消费", pointer.onWakeClick())
        assertFalse("按下立即恢复指针", pointer.hidden)
    }

    @Test
    fun `唤醒后重新计时`() {
        pass(3_000)
        pointer.onWakeClick()
        pass(2_999)
        assertFalse("唤醒时重新起算", pointer.hidden)
        pass(1)
        assertTrue("唤醒满 3 秒再次隐藏", pointer.hidden)
    }

    @Test
    fun `唤醒后的下一次点击照常`() {
        pass(3_000)
        assertTrue(pointer.onWakeClick())
        assertFalse("第二次按下时指针已可见：不再消费", pointer.onWakeClick())
        assertFalse(pointer.hidden)
    }

    @Test
    fun `可见时点击不重置计时`() {
        pass(2_000)
        assertFalse("可见时的点击照常，不消费", pointer.onWakeClick())
        pass(999)
        assertFalse("点击不重置隐藏计时", pointer.hidden)
        pass(1)
        assertTrue("计时仍从最后一次移动起算", pointer.hidden)
    }

    // --- 其他恢复 ---
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
    fun `打开阅读菜单立即恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)
        pointer.setPanelPresent(PointerPanel.MENU, true)
        assertFalse("打开菜单立即恢复", pointer.hidden)
    }

    @Test
    fun `跨书条出现立即恢复`() {
        pass(3_000)
        assertTrue(pointer.hidden)
        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, true)
        assertFalse("跨书条出现立即恢复", pointer.hidden)
    }

    // --- 面板在场时不隐藏 ---
    @Test
    fun `阅读菜单在场期间到点不隐藏`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pass(10_000)
        assertFalse("菜单开着时鼠标不动也保持可见", pointer.hidden)
    }

    @Test
    fun `跨书条在场期间到点不隐藏`() {
        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, true)
        pass(10_000)
        assertFalse("跨书条在场时鼠标不动也保持可见", pointer.hidden)
    }

    @Test
    fun `两个面板都退场后才重新计时`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, true)
        pass(5_000)
        pointer.setPanelPresent(PointerPanel.MENU, false)
        pass(5_000)
        assertFalse("还有一个面板在场：不安排隐藏", pointer.hidden)
        pointer.setPanelPresent(PointerPanel.CROSS_BOOK, false)
        pass(2_999)
        assertFalse("最后一个退场时重新起算", pointer.hidden)
        pass(1)
        assertTrue(pointer.hidden)
    }

    @Test
    fun `面板开着时触摸面板不隐藏`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pointer.onActivity(PointerActivity.TOUCH)
        assertFalse("菜单开着时触摸（落在菜单上）不隐藏指针", pointer.hidden)
    }

    // --- 立即隐藏 ---
    @Test
    fun `触摸正文立即隐藏`() {
        assertFalse(pointer.hidden)
        pointer.onActivity(PointerActivity.TOUCH)
        assertTrue("没有面板在场时触摸正文立即隐藏", pointer.hidden)
    }

    @Test
    fun `菜单关闭后触摸正文立即隐藏`() {
        pointer.setPanelPresent(PointerPanel.MENU, true)
        pass(1_000)
        pointer.setPanelPresent(PointerPanel.MENU, false)
        pointer.onActivity(PointerActivity.TOUCH)
        assertTrue(pointer.hidden)
    }

    // --- 到点剩余延时（界面只等这个值）---
    @Test
    fun `剩余延时按注入时钟算出`() {
        assertEquals(POINTER_HIDE_DELAY_MILLIS, pointer.state().hideDelayMillis)
        now += 1_000
        assertEquals("剩余值出自注入的那个时钟", 2_000L, pointer.state().hideDelayMillis)
    }

    @Test
    fun `已隐藏与面板开着时没有剩余延时`() {
        pass(3_000)
        assertEquals("已隐藏：没有待处理的计时", 0L, pointer.state().hideDelayMillis)
        pointer.onDoubleTapZoom()
        assertEquals(POINTER_HIDE_DELAY_MILLIS, pointer.state().hideDelayMillis)
        pointer.setPanelPresent(PointerPanel.MENU, true)
        assertEquals("面板开着：不安排隐藏", 0L, pointer.state().hideDelayMillis)
    }
}
