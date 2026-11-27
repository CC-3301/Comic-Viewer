package com.cc3301.comicviewer.ui

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 隐藏期间右键那一整段（按下、按住移动、抬起）到不了点按识别器（spec §指针自动隐藏）：
 * 真组合 [detectReaderTapGestures]，真往 [ComposeView] 发鼠标事件，看有没有点按 / 双击效果落地。
 *
 * 要锁住的三件事：**不呼菜单、不翻页**（两者都是点按效果）、**不进双击历史**（第一次按下不被算作双击的第一下）。
 * 判据：右键在窗口层只发得出 generic motion（`ACTION_BUTTON_PRESS` / 悬停移动 / `ACTION_BUTTON_RELEASE`），
 * 识别器等的是「新按下的指针」，这一段里没有任何一根按下 ⇒ 一个效果都不发。
 * 另一条用例走「只有移动与抬起、没有按下」的鼠标事件流：那正是「Compose 没收到 down」的兜底形态，同样不得落地。
 *
 * 正向对照（`触摸点击照常产生一次单击`）：同一份组合里真按一次屏幕要能数到一次单击——
 * 否则「没有效果」可能是组合没跑起来，那两条用例就没有判别力。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderRightClickSegmentTest {
    /** 识别器发出的效果计数 */
    private class Probe {
        var taps = 0
        var doubles = 0
    }

    private fun screen(): Pair<Probe, ComposeView> {
        val probe = Probe()
        val view = composeViewInActivity {
            MaterialTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectReaderTapGestures(
                                onDoubleTap = { probe.doubles++ },
                                onTap = { probe.taps++ },
                                // 可见态的识别器：回前台闸与底边手势带都不拦，这一段只看「有没有新按下的指针」
                                clickAllowed = { true },
                                bottomGestureStripPx = 0f,
                                wakeClickConsumed = { false },
                            )
                        },
                )
            }
        }
        view.layoutOnce(SCREEN_W, SCREEN_H)
        return probe to view
    }

    /** 鼠标事件：源标成 [InputDevice.SOURCE_MOUSE]（识别器按指针类型分流） */
    private fun mouseEvent(action: Int): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, CENTER_X, CENTER_Y, 0).apply {
            source = InputDevice.SOURCE_MOUSE
        }

    /** 发一次鼠标事件（generic motion 通路），并让主线程跑一轮——手势协程才看得见它 */
    private fun ComposeView.mouse(action: Int) {
        val event = mouseEvent(action)
        dispatchGenericMotionEvent(event)
        event.recycle()
    }

    /** 真按一次屏幕（触摸）：按下 + 抬起 */
    private fun ComposeView.tapScreen() {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, CENTER_X, CENTER_Y, 0)
        dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_UP, CENTER_X, CENTER_Y, 0)
        dispatchTouchEvent(up)
        up.recycle()
    }

    @Test
    fun `右键整段不产生点按效果`() {
        val (probe, view) = screen()
        // 按下 → 按住移动 → 抬起
        view.mouse(MotionEvent.ACTION_BUTTON_PRESS)
        view.mouse(MotionEvent.ACTION_HOVER_MOVE)
        view.mouse(MotionEvent.ACTION_BUTTON_RELEASE)
        view.layoutUntil(SCREEN_W, SCREEN_H) { probe.taps + probe.doubles > 0 }
        assertEquals("右键那一段不得呼菜单或翻页（都是点按效果），也不得算作双击的第一下", 0, probe.taps)
        assertEquals("右键不构成双击", 0, probe.doubles)
    }

    @Test
    fun `没有按下的鼠标事件流不产生点按效果`() {
        val (probe, view) = screen()
        // 「Compose 没收到 down」的形态：只有移动与抬起，没有按下
        view.mouse(MotionEvent.ACTION_MOVE)
        view.mouse(MotionEvent.ACTION_UP)
        view.layoutUntil(SCREEN_W, SCREEN_H) { probe.taps + probe.doubles > 0 }
        assertEquals("没有按下的那一段不该落地成单击", 0, probe.taps)
        assertEquals("没有按下的那一段不该落地成双击", 0, probe.doubles)
    }

    @Test
    fun `鼠标左键点击照常产生一次单击`() {
        val (probe, view) = screen()
        // 左键在窗口层是 touch 流（按下 + 抬起），源标鼠标：同一份判据下它必须落地成单击
        val down = mouseEvent(MotionEvent.ACTION_DOWN)
        view.dispatchTouchEvent(down)
        down.recycle()
        val up = mouseEvent(MotionEvent.ACTION_UP)
        view.dispatchTouchEvent(up)
        up.recycle()
        assertTrue(
            "鼠标左键按下要能进点按识别器（否则右键那一整段「没有效果」可能只是因为事件根本没进来）",
            view.layoutUntil(SCREEN_W, SCREEN_H) { probe.taps == 1 },
        )
        assertEquals("左键单击只发一次", 1, probe.taps)
    }

    @Test
    fun `触摸点击照常产生一次单击`() {
        val (probe, view) = screen()
        view.tapScreen()
        // 单击要等满双击窗口（150ms）才落地
        assertTrue(
            "同一份组合里真按一次屏幕要能数到一次单击，否则「没有效果」的用例没有判别力",
            view.layoutUntil(SCREEN_W, SCREEN_H) { probe.taps == 1 },
        )
        assertEquals("单击只发一次", 1, probe.taps)
        assertEquals("单击不构成双击", 0, probe.doubles)
    }

    private companion object {
        /** 屏幕规格（px）：够放下一整块正文，坐标取正中 */
        const val SCREEN_W = 320
        const val SCREEN_H = 480
        const val CENTER_X = SCREEN_W / 2f
        const val CENTER_Y = SCREEN_H / 2f
    }
}
