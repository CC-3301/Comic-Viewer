package com.cc3301.comicviewer.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回前台点击屏蔽的判据（spec §回前台点击屏蔽）：**窗口重新获得焦点起算 [FOREGROUND_CLICK_BLOCK_MILLIS]
 * 内的点击一律丢弃**，起点是焦点而不是「是否从后台回来」。
 *
 * 判据是纯逻辑、时钟注入（[ForegroundClickGate] 的 `nowMillis`），本文件把时间轴摆出来：
 * 屏蔽窗口的边界（299 / 300）、每次重新获得焦点都重新起算、以及「窗口一直没失焦就放行」。
 * 界面侧只剩接线（焦点事件 → [ForegroundClickGate.onWindowFocused]、按下 → [ForegroundClickGate.allowsClick]），
 * 走手动验收。
 */
class ForegroundClickGateTest {

    /** 假时钟：用例直接把时间推到想要的那一毫秒 */
    private var now = 0L

    private val gate = ForegroundClickGate(nowMillis = { now })

    @Test
    fun `屏蔽时长就是 300 毫秒`() {
        assertEquals(300L, FOREGROUND_CLICK_BLOCK_MILLIS)
    }

    @Test
    fun `还没收到焦点事件时一律放行`() {
        now = 10_000L
        assertTrue("窗口一直没失焦 ⇒ 没有这 300ms 的起点", gate.allowsClick())
    }

    @Test
    fun `焦点后 300 毫秒内的点击丢弃 到点起放行`() {
        now = 1_000L
        gate.onWindowFocused()

        now = 1_299L
        assertFalse("299ms 仍在屏蔽窗口内", gate.allowsClick())

        now = 1_300L
        assertTrue("满 300ms 起放行", gate.allowsClick())
    }

    @Test
    fun `每次重新获得焦点都重新起算`() {
        now = 1_000L
        gate.onWindowFocused()
        now = 2_000L
        assertTrue("第一次的屏蔽早已过期", gate.allowsClick())

        // 点通知 / 来电 / 弹窗关闭后回前台：又来一次焦点
        gate.onWindowFocused()
        now = 2_100L
        assertFalse("重新起算，100ms 内不生效", gate.allowsClick())
        now = 2_300L
        assertTrue("重新起算后满 300ms 放行", gate.allowsClick())
    }

    @Test
    fun `点击本身不算起点 屏蔽窗口不会因为点过而顺延`() {
        now = 1_000L
        gate.onWindowFocused()
        now = 1_100L
        assertFalse("窗口内点一次", gate.allowsClick())
        now = 1_300L
        assertTrue("屏蔽仍按焦点那一下起算，不从上次点击重来", gate.allowsClick())
    }
}
