package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** `MotionEvent.ACTION_BUTTON_RELEASE`（映射不处理抬起，只用于证明它不算唤醒） */
private const val ACTION_BUTTON_RELEASE = 12

/** `MotionEvent.BUTTON_MIDDLE`（不属于左/右键或侧键） */
private const val MOUSE_BUTTON_MIDDLE = 4

/** 指针捕获期间的鼠标事件映射（spec §指针自动隐藏） */
class CapturedPointerTest {
    /** 主键按下在捕获里以 ACTION_DOWN 到达，其余键位以 ACTION_BUTTON_PRESS 到达 */
    private fun press(button: Int, buttons: Int = button) =
        capturedPointerAction(CAPTURED_ACTION_BUTTON_PRESS, button, buttons)

    @Test
    fun `移动只消费 不改显隐`() {
        assertEquals(CapturedPointerAction.Ignore, capturedPointerAction(CAPTURED_ACTION_MOVE, 0, 0))
        // 按住移动同样只消费：唤醒那一段的拖动不落地
        assertEquals(
            CapturedPointerAction.Ignore,
            capturedPointerAction(CAPTURED_ACTION_MOVE, 0, MOUSE_BUTTON_PRIMARY),
        )
    }

    @Test
    fun `左键与右键按下都唤醒`() {
        assertEquals(CapturedPointerAction.WakeClick, press(MOUSE_BUTTON_PRIMARY))
        assertEquals(CapturedPointerAction.WakeClick, press(MOUSE_BUTTON_SECONDARY))
    }

    @Test
    fun `主键以 ACTION_DOWN 到达也算唤醒`() {
        assertEquals(
            CapturedPointerAction.WakeClick,
            capturedPointerAction(CAPTURED_ACTION_DOWN, MOUSE_BUTTON_PRIMARY, MOUSE_BUTTON_PRIMARY),
        )
    }

    @Test
    fun `侧键回退与前进`() {
        assertEquals(CapturedPointerAction.HistoryBack, press(MOUSE_BUTTON_BACK))
        assertEquals(CapturedPointerAction.HistoryForward, press(MOUSE_BUTTON_FORWARD, buttons = 0))
    }

    @Test
    fun `其余键位不动作`() {
        assertEquals(CapturedPointerAction.Ignore, press(MOUSE_BUTTON_MIDDLE))
    }

    @Test
    fun `抬起不算唤醒`() {
        assertEquals(
            CapturedPointerAction.Ignore,
            capturedPointerAction(ACTION_BUTTON_RELEASE, MOUSE_BUTTON_PRIMARY, 0),
        )
    }
}
