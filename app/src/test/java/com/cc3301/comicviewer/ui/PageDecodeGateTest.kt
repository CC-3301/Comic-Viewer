package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.view.CoverBytePriority
import com.cc3301.comicviewer.core.view.PAGE_DECODE_MAX_CONCURRENT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页面**解码**的并发闸（[PageImages.withPageDecodePermit]）。
 *
 * 取字节那道闸（[PAGE_MAX_CONCURRENT_LOADS]）只管「同时对来源发几次请求」；解码本身是纯 CPU 活，
 * 条漫预取一次会备好后面 7 页、前面 2 页，不设闸就会同时解 9 张、把帧管线需要的核占满。
 *
 * 判别用例：同时最多三张在解（没有这道闸时量到的是全部 8 张）与
 * 可见页优先取牌、已排队的预取让位（排队序不看优先级时可见页会排在两条预取之后）。
 * 两条都打在**生产那个实例**上：闸位与优先级接线各写一份就钉不住。
 */
class PageDecodeGateTest {

    private val images = PageImages(log = {}, cache = decodedImageCache())

    @Test
    fun `页面解码闸位是 3`() {
        assertEquals("与取字节同一个口径", 3, PAGE_DECODE_MAX_CONCURRENT)
    }

    @Test
    fun `同时最多三张在解`() = runTest {
        var running = 0
        var maxSeen = 0

        val jobs = List(8) {
            launch {
                images.withPageDecodePermit(CoverBytePriority.Prefetch) {
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
    fun `可见页优先取牌 已排队的预取让位`() = runTest {
        val order = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()

        // 三张牌都被预取占着（已经在解的不抢占）
        val inFlight = List(3) { index ->
            launch { images.withPageDecodePermit(CoverBytePriority.Prefetch) { order += "在飞" + index; hold.await() } }
        }
        runCurrent()
        val queued = List(2) { index ->
            launch { images.withPageDecodePermit(CoverBytePriority.Prefetch) { order += "预取" + index } }
        }
        runCurrent()
        val visible = launch { images.withPageDecodePermit(CoverBytePriority.Visible) { order += "可见" } }
        runCurrent()

        hold.complete(Unit)
        (inFlight + queued + listOf(visible)).forEach { it.join() }

        assertEquals(listOf("在飞0", "在飞1", "在飞2", "可见", "预取0", "预取1"), order)
    }
}
