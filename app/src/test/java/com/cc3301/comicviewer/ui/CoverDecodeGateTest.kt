package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.view.CoverBytePriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面**解码**的并发闸。
 *
 * 为什么需要它：解码跑在 `Dispatchers.IO`（核心线程数 = 核数，阻塞任务还能继续开线程，硬上限 64），
 * 于是冷启动首屏 12 张封面同时解、把 4 个大核全占满，帧管线抢不到核——设备读数是首窗 13.0ms/帧、
 * 稳定态 8.4ms/帧。
 *
 * 判别用例：`同时最多两张在解`（改动前没有这道闸，量到的会是全部 6 张）与
 * `可见格优先取牌 已排队的预取让位`（排队序不看优先级时「可见」会排在两条预取之后）。
 * 两条都打在**生产那个实例**（[PageDecoder.coverDecodeGate]）上：闸位与优先级接线各写一份就钉不住。
 */
class CoverDecodeGateTest {

    @Test
    fun `封面解码闸位是 2`() {
        // 闸位是口径（规格写的就是 2、真机判据也按它算）：只注入小闸位的用例钉不住常量本身
        assertEquals("4 个大核里留 2 个给帧管线", 2, COVER_DECODE_MAX_CONCURRENT)
    }

    @Test
    fun `同时最多两张在解`() = runTest {
        val gate = PageDecoder.coverDecodeGate
        var running = 0
        var maxSeen = 0

        val jobs = List(6) {
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

        assertEquals("同时最多 2 张（越界的排队，不是并发冲）", 2, maxSeen)
        assertEquals("跑完全部归还干净", 0, running)
    }

    @Test
    fun `可见格优先取牌 已排队的预取让位`() = runTest {
        val gate = PageDecoder.coverDecodeGate
        val order = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()

        // 两张牌都被预取占着（已经在解的不抢占）
        val inFlight = List(2) { index ->
            launch { gate.withPermit(CoverBytePriority.Prefetch) { order += "在飞$index"; hold.await() } }
        }
        runCurrent()
        val queued = List(2) { index ->
            launch { gate.withPermit(CoverBytePriority.Prefetch) { order += "预取$index" } }
        }
        runCurrent()
        val visible = launch { gate.withPermit(CoverBytePriority.Visible) { order += "可见" } }
        runCurrent()

        hold.complete(Unit)
        (inFlight + queued + listOf(visible)).forEach { it.join() }

        assertEquals(listOf("在飞0", "在飞1", "可见", "预取0", "预取1"), order)
    }
}
