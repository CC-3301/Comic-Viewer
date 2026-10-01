package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 重建失败的退避。
 *
 * 为什么测这里而不是 `SmbjTransport`：smbj 的 `SMBClient`/`Connection`/`Session` 在单测里不可注入
 * （`SmbjTransport` 直接 new），真实建连与「会话失效」在 JVM 上跑不出来。按仓库既有先例
 * （`SmbSessionGate`、`SmbSessionReporter`）把「失败多久之后才允许再试」摘进纯类
 * [SmbRebuildBackoff]，在这里锁死三条语义：
 *
 * ① **相邻失败翻倍**：1s → 2s → 4s，封顶 8s（数字是 2026-09-29 定下的口径）；
 * ② **窗口内不再尝试**：这期间进来的读就地快速失败（不排队、不再建）——设备日志里
 *    「十几秒里连续失败十几次连接」要压成「最多几次」靠的就是它；
 * ③ **成功即清零**：会话建起来了就不再拦任何读，且下一轮的尝试号从 1 重新数。
 *
 * 时钟是注入的假时钟：退避是纯状态机，用真实时钟的单测只能靠 `sleep` 决定成败（那是 flaky 的来源）。
 * **同一时刻不并发重建**这条还有一半在 `SmbSessionGateTest`（同一代只有第一个失败者拆会话）。
 */
class SmbRebuildBackoffTest {

    /** 假时钟（纳秒）；用例自己推它，不睡 */
    private var clock = 0L

    private val backoff = SmbRebuildBackoff { clock }

    private fun advance(ms: Long) {
        clock += ms * 1_000_000
    }

    @Test
    fun `第一次失败退避 1 秒 窗口一过就放行下一次尝试`() {
        backoff.recordFailure()

        assertTrue("刚失败就在窗口里", backoff.isBackingOff())
        advance(999)
        assertTrue("还差 1 毫秒也算窗口内", backoff.isBackingOff())
        advance(1)
        assertFalse("窗口一过就要放行（不然会话永远建不回来）", backoff.isBackingOff())
    }

    @Test
    fun `窗口长度按 1 到 2 到 4 翻倍 封顶 8 秒`() {
        // 用 1 毫秒步进探出真实窗口长度，而不是在用例里复述一遍公式（复述公式等于没测）
        assertEquals("第 1 次失败", 1_000L, windowMsAfterFailure())
        assertEquals("第 2 次失败", 2_000L, windowMsAfterFailure())
        assertEquals("第 3 次失败", 4_000L, windowMsAfterFailure())
        assertEquals("第 4 次失败已经封顶", 8_000L, windowMsAfterFailure())
        assertEquals("再失败也还是封顶（不会无限翻倍）", 8_000L, windowMsAfterFailure())
    }

    @Test
    fun `重建成功即清零退避`() {
        backoff.recordFailure()
        backoff.recordFailure()
        assertTrue("两次失败之后仍在窗口里", backoff.isBackingOff())

        backoff.recordSuccess()

        assertFalse("成功即清零：窗口还剩一半也要立刻放行", backoff.isBackingOff())
        assertEquals("下一轮重建从第 1 次尝试重新数", 1, backoff.nextAttempt())
    }

    @Test
    fun `窗口从这一次失败的时刻起算 而不是沿用上一次的到期时刻`() {
        backoff.recordFailure() // t=0：窗口到 1000ms
        advance(900)
        backoff.recordFailure() // t=900：窗口到 900+2000=2900ms

        advance(1_100) // t=2000：第一次的窗口早就过了
        assertTrue("第二次失败把到期时刻推后了", backoff.isBackingOff())
        advance(899) // t=2899
        assertTrue("还差 1 毫秒", backoff.isBackingOff())
        advance(1)
        assertFalse("2900ms 到点", backoff.isBackingOff())
    }

    @Test
    fun `尝试号按连续失败计数`() {
        assertEquals("第一次建立（新建或重建）都是第 1 次尝试", 1, backoff.nextAttempt())
        backoff.recordFailure()
        assertEquals(2, backoff.nextAttempt())
        backoff.recordFailure()
        assertEquals("`smbRebuild attempt=` 报的就是它（真机上「一共试了几次」）", 3, backoff.nextAttempt())
    }

    @Test
    fun `退避窗口内并发进来的读一条都不开始尝试`() {
        // 时钟停在 0：窗口不会自己过去，模拟「一批读同时撞上来」
        backoff.recordFailure()

        val started = AtomicInteger()
        val done = CountDownLatch(8)
        repeat(8) {
            Thread {
                // 传输层的判定就是这个形状：先问能不能建，能建才动 socket
                if (!backoff.isBackingOff()) started.incrementAndGet()
                done.countDown()
            }.start()
        }

        assertTrue("8 条读都要跑完", done.await(5, TimeUnit.SECONDS))
        assertEquals("窗口内 0 次尝试（这就是「不排队、不再建」）", 0, started.get())
    }

    /** 记一次失败，返回这个窗口的长度（毫秒）：按 1 毫秒步进探到窗口结束为止 */
    private fun windowMsAfterFailure(): Long {
        backoff.recordFailure()
        var ms = 0L
        while (backoff.isBackingOff()) {
            clock += 1_000_000
            ms++
        }
        return ms
    }
}
