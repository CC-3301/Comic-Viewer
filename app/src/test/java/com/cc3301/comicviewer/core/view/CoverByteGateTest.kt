package com.cc3301.comicviewer.core.view

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面字节取数的并发闸（票 #145）：冷缓存期一屏 12~18 张封面同时「取字节 + 解码 + 上屏」，
 * 单张 300~460ms、全叠在一起就是冷启动那 5~7 秒的整窗超预算。
 *
 * 判别用例：第 1 条（同时最多 N 张在飞）与第 2 条（可见格优先取牌、预取排队让位）——
 * 改动前没有这道闸，第 1 条量到的会是全部 12 张。
 */
class CoverByteGateTest {

    @Test
    fun `同时最多 N 张在飞`() = runTest {
        val gate = CoverByteGate(maxConcurrent = 3)
        var running = 0
        var maxSeen = 0

        val jobs = List(12) {
            launch {
                gate.withPermit(CoverBytePriority.Prefetch) {
                    running++
                    maxSeen = maxOf(maxSeen, running)
                    delay(10)
                    running--
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals("同时最多 3 张（越界的排队，不是并发冲）", 3, maxSeen)
        assertEquals("跑完全部归还干净", 0, running)
    }

    @Test
    fun `可见格优先取牌 已排队的预取让位`() = runTest {
        val gate = CoverByteGate(maxConcurrent = 1)
        val order = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()

        // 唯一一张牌先被一个预取占着（已经在飞的往返不抢占）
        val inFlight = launch { gate.withPermit(CoverBytePriority.Prefetch) { order += "在飞"; hold.await() } }
        runCurrent()
        // 两个预取排队在前
        val queued = List(2) { index ->
            launch { gate.withPermit(CoverBytePriority.Prefetch) { order += "预取$index" } }
        }
        runCurrent()
        // 可见格随后才到：它必须插在已排队的预取之前
        val visible = launch { gate.withPermit(CoverBytePriority.Visible) { order += "可见" } }
        runCurrent()

        hold.complete(Unit)
        (listOf(inFlight) + queued + listOf(visible)).forEach { it.join() }

        assertEquals(listOf("在飞", "可见", "预取0", "预取1"), order)
    }

    @Test
    fun `排队的可见请求之间按先来后到`() = runTest {
        val gate = CoverByteGate(maxConcurrent = 1)
        val order = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()

        val inFlight = launch { gate.withPermit(CoverBytePriority.Visible) { order += "在飞"; hold.await() } }
        runCurrent()
        val queued = List(2) { index ->
            launch { gate.withPermit(CoverBytePriority.Visible) { order += "可见$index" } }
        }
        runCurrent()

        hold.complete(Unit)
        (listOf(inFlight) + queued).forEach { it.join() }

        assertEquals(listOf("在飞", "可见0", "可见1"), order)
    }

    @Test
    fun `等待中被取消不让名额漏掉`() = runTest {
        val gate = CoverByteGate(maxConcurrent = 1)
        val hold = CompletableDeferred<Unit>()

        val inFlight = launch { gate.withPermit(CoverBytePriority.Prefetch) { hold.await() } }
        runCurrent()
        val waiter = launch { gate.withPermit(CoverBytePriority.Prefetch) { } }
        runCurrent()
        waiter.cancel()
        runCurrent()

        hold.complete(Unit)
        inFlight.join()

        // 名额回到闸上：下一次取字节立刻能拿到牌（被取消的那张没把牌吞掉）
        val done = CompletableDeferred<Unit>()
        launch { gate.withPermit(CoverBytePriority.Visible) { done.complete(Unit) } }
        runCurrent()
        assertTrue("取消的等待者不吞名额", done.isCompleted)
    }

    @Test
    fun `上限非正时按 1 张兜底`() = runTest {
        val gate = CoverByteGate(maxConcurrent = 0)
        var running = 0
        var maxSeen = 0

        val jobs = List(3) {
            launch {
                gate.withPermit(CoverBytePriority.Visible) {
                    running++
                    maxSeen = maxOf(maxSeen, running)
                    delay(5)
                    running--
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals("上限非法时不放行并发", 1, maxSeen)
    }
}
