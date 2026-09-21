package com.cc3301.comicviewer.core.view

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览页滚动量测的聚合口径（票 #109 E3-A「先量再改」）：掉帧判定、掉帧/秒、静止落行、
 * 封面取字节+解码统计、摘要行形状。真机上比对「改动前 / 改动后」读的就是这一行，
 * 因此键名与折算口径在本用例里锁死——改了先红。
 */
class ScrollProbeTest {

    /** 当前一帧的原始量测（真机上来自 `FrameMetrics` 的三个时长）+ 单调时钟读数 */
    private fun ScrollProbe.frame(
        nowMs: Long,
        totalMs: Double = 8.0,
        layoutMs: Double = 1.0,
        drawMs: Double = 3.0,
    ): String? = onFrame(
        totalNanos = (totalMs * 1_000_000).toLong(),
        layoutNanos = (layoutMs * 1_000_000).toLong(),
        drawNanos = (drawMs * 1_000_000).toLong(),
        nowNanos = nowMs * 1_000_000,
    )

    /** 从摘要行里取某个键的值（行是空格分隔的 key=value） */
    private fun key(line: String, name: String): String =
        line.split(' ').first { it.startsWith("$name=") }.substringAfter('=')

    /** 一次滚动活动（毫秒刻度，与 [ScrollProbeTest.frame] 同一套） */
    private fun ScrollProbe.scrollAt(nowMs: Long) = markScrollActivity(nowNanos = nowMs * 1_000_000)

    /** 只关心计数、不关心何时落行的算例：把静止阈值拉到不会触发，直接读摘要 */
    private fun countingProbe() = ScrollProbe(idleFlushNanos = Long.MAX_VALUE)

    @Test
    fun `预算内的帧不计掉帧 静止后才落行`() {
        val probe = ScrollProbe()
        probe.scrollAt(0)
        // 10 帧 16ms：最后一帧在 160ms，距最后一次活动只过 160ms，还没到静止阈值
        for (i in 1..10) assertNull("滚动中不落行", probe.frame(nowMs = i * 16L))
        val line = probe.frame(nowMs = 700)
        assertNotNull("静止超过阈值后落行", line)
        assertTrue("统一前缀", line!!.startsWith(ScrollProbe.SUMMARY_PREFIX))
        assertEquals("10 帧 + 落行那一帧", "11", key(line, "frames"))
        assertEquals("都短于 60Hz 预算", "0", key(line, "janky"))
        assertEquals("0 掉帧 → 0%", "0.0", key(line, "jankPct"))
        assertEquals("0 掉帧 → 0 掉帧每秒", "0.0", key(line, "jankPerSec"))
        assertEquals("窗口 = 首次活动到落行那一帧", "700", key(line, "windowMs"))
        assertEquals("一次可见区变化", "1", key(line, "steps"))
    }

    @Test
    fun `超过帧预算的帧计入掉帧 并折算百分点与每秒`() {
        val probe = countingProbe()
        probe.scrollAt(0)
        // 10 帧：2 帧超预算（33ms / 50ms），帧与帧的时钟间隔都是 100ms ⇒ 窗口 1 秒
        val totals = listOf(8.0, 8.0, 33.0, 8.0, 8.0, 8.0, 50.0, 8.0, 8.0, 8.0)
        totals.forEachIndexed { i, total ->
            assertNull("阈值拉长后不中途落行", probe.frame(nowMs = (i + 1) * 100L, totalMs = total))
        }
        val line = probe.summaryLine()
        assertEquals("2 帧超预算", "2", key(line, "janky"))
        assertEquals("2 / 10 帧", "20.0", key(line, "jankPct"))
        assertEquals("2 掉帧 ÷ 1.0 秒", "2.0", key(line, "jankPerSec"))
        assertEquals("窗口毫秒", "1000", key(line, "windowMs"))
        assertEquals("最长帧 = 50ms", "50", key(line, "frameMaxMs"))
        assertEquals("超预算那一帧的合成耗时", "3", key(line, "drawMaxMs"))
    }

    @Test
    fun `静止期的帧不进统计 下一次滚动另开窗口`() {
        val probe = ScrollProbe()
        probe.scrollAt(0)
        assertNull("滚动中不落行", probe.frame(nowMs = 100))
        val first = probe.frame(nowMs = 700)!!
        assertEquals("首窗口 2 帧", "2", key(first, "frames"))
        assertEquals("首窗口时长 = 0 → 700", "700", key(first, "windowMs"))
        // 落行后复位：没有新的滚动活动，来的帧一律不算
        assertNull("复位后第一帧", probe.frame(nowMs = 800))
        assertNull("复位后第二帧", probe.frame(nowMs = 900))
        // 再次滚动 = 新窗口，计数从头开始
        probe.scrollAt(1000)
        assertNull("新窗口滚动中不落行", probe.frame(nowMs = 1100))
        val second = probe.frame(nowMs = 1600)!!
        assertEquals("新窗口只算新窗口的帧", "2", key(second, "frames"))
        assertEquals("新窗口的步进数", "1", key(second, "steps"))
        assertEquals("新窗口时长 = 1000 → 1600", "600", key(second, "windowMs"))
    }

    @Test
    fun `p95 取最近秩 与最大值分开`() {
        val probe = countingProbe()
        probe.scrollAt(0)
        // 20 帧：18 帧 10ms + 2 帧 100ms ⇒ 最近秩 p95 = 排序后第 19 个 = 100ms
        val totals = List(18) { 10.0 } + listOf(100.0, 100.0)
        totals.forEachIndexed { i, total -> probe.frame(nowMs = (i + 1) * 50L, totalMs = total) }
        val line = probe.summaryLine()
        assertEquals("p95 命中长帧", "100", key(line, "frameP95Ms"))
        assertEquals("平均 = (18×10 + 2×100) ÷ 20", "19.0", key(line, "frameAvgMs"))
        assertEquals("最大值", "100", key(line, "frameMaxMs"))
    }

    @Test
    fun `条目与封面组合次数 以及封面取字节加重解码统计都进摘要`() {
        val probe = ScrollProbe(idleFlushNanos = Long.MAX_VALUE)
        probe.scrollAt(0)
        repeat(3) { probe.onItemComposed() }
        repeat(3) { probe.onCoverComposed() }
        probe.onCoverLoad(millis = 12, thread = "DefaultDispatcher-worker-2")
        probe.onCoverLoad(millis = 40, thread = "main")
        probe.onCoverLoad(millis = 5, thread = "DefaultDispatcher-worker-1")
        // 恰好等于预算的帧不算掉帧（判定是严格大于）
        assertNull(
            "阈值拉长后不中途落行",
            probe.onFrame(
                totalNanos = ScrollProbe.FRAME_BUDGET_NANOS,
                layoutNanos = 1_000_000,
                drawNanos = 2_000_000,
                nowNanos = 600_000_000,
            ),
        )
        val line = probe.summaryLine()
        assertEquals("0", key(line, "janky"))
        assertEquals("条目 composable 体执行 3 次", "3", key(line, "itemsComposed"))
        assertEquals("封面 composable 体执行 3 次", "3", key(line, "coversComposed"))
        assertEquals("3 次封面加载", "3", key(line, "coverLoads"))
        assertEquals("12 + 40 + 5", "57", key(line, "coverLoadTotalMs"))
        assertEquals("最慢那次", "40", key(line, "coverLoadMaxMs"))
        assertEquals(
            "线程分布按名排序、便于前后对齐",
            "DefaultDispatcher-worker-1:1,DefaultDispatcher-worker-2:1,main:1",
            key(line, "coverLoadThreads"),
        )
    }

    @Test
    fun `还没滚动时落行不除零`() {
        val line = ScrollProbe().summaryLine()
        assertEquals("0", key(line, "frames"))
        assertEquals("0.0", key(line, "jankPct"))
        assertEquals("0.0", key(line, "jankPerSec"))
        assertEquals("0.0", key(line, "frameAvgMs"))
        assertEquals("0", key(line, "windowMs"))
    }

    @Test
    fun `封面加载明细行带前缀与线程名`() {
        val line = ScrollProbe.coverLoadLine(millis = 12, thread = "DefaultDispatcher-worker-2")
        assertTrue("统一前缀", line.startsWith(ScrollProbe.COVER_LOAD_PREFIX))
        assertEquals("ms=12 thread=DefaultDispatcher-worker-2", line.substringAfter(' '))
    }

    @Test
    fun `线程名里的空白折成下划线 不破坏 key=value 切分`() {
        val line = ScrollProbe.coverLoadLine(millis = 1, thread = "my thread\t2")
        assertEquals("browseCoverLoad ms=1 thread=my_thread_2", line)
    }

    @Test
    fun `一位小数格式化不依赖默认 Locale`() {
        val before = Locale.getDefault()
        try {
            // 德语区小数点是逗号：直接用 String.format 会让行里出现 "12,3"，前后数字没法机械比对
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.3", oneDecimal(12.34))
            assertEquals("0.0", oneDecimal(0.0))
            assertEquals("四舍五入到一位", "2.0", oneDecimal(1.96))
            assertEquals("负数", "-1.5", oneDecimal(-1.46))
        } finally {
            Locale.setDefault(before)
        }
    }
}
