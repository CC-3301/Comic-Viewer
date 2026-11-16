package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.source.PerfTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 回前台位置量测的诊断行（锚点口径与阅读页记账同源，行格式见 [ReaderFocusProbe] 的 KDoc）。
 */
class ReaderFocusProbeTest {

    private val lines = PerfTiming.newRecordedLinesForTest()
    private var nowNanos = 0L
    private val probe = ReaderFocusProbe { nowNanos }

    @Before
    fun setUp() {
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
    }

    @After
    fun tearDown() {
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
    }

    @Test
    fun `失焦与重新获得焦点各一行 位置取顶部可见页与页内比例`() {
        probe.onWindowFocused(focused = false, anchor = ReaderScrollAnchor(page = 3, percent = 0.5f))
        nowNanos = 7_000_000L
        probe.onWindowFocused(focused = true, anchor = ReaderScrollAnchor(page = 3, percent = 0.5f))

        assertEquals(
            listOf(
                "readerFocus phase=lost page=3 percent=0.5",
                "readerFocus phase=gained page=3 percent=0.5",
            ),
            lines.toList(),
        )
    }

    @Test
    fun `焦点恢复后两秒内锚点每变化一次一行 ms 从焦点恢复起算`() {
        probe.onWindowFocused(focused = true, anchor = ReaderScrollAnchor(page = 1, percent = 0.25f))
        lines.clear()
        nowNanos = 42_000_000L
        probe.onAnchorChanged(ReaderScrollAnchor(page = 1, percent = 0.5f))
        nowNanos = 1_500_000_000L
        probe.onAnchorChanged(ReaderScrollAnchor(page = 2, percent = 0.0f))

        assertEquals(
            listOf(
                "readerAnchor phase=afterFocus ms=42 page=1 percent=0.5",
                "readerAnchor phase=afterFocus ms=1500 page=2 percent=0.0",
            ),
            lines.toList(),
        )
    }

    @Test
    fun `超过两秒的锚点变化不产行`() {
        probe.onWindowFocused(focused = true, anchor = ReaderScrollAnchor(page = 1, percent = 0.0f))
        lines.clear()
        nowNanos = 2_001_000_000L
        probe.onAnchorChanged(ReaderScrollAnchor(page = 2, percent = 0.0f))

        assertEquals(emptyList<String>(), lines.toList())
    }

    @Test
    fun `锚点没变不产行`() {
        val anchor = ReaderScrollAnchor(page = 4, percent = 0.5f)
        probe.onWindowFocused(focused = true, anchor = anchor)
        lines.clear()
        nowNanos = 10_000_000L
        probe.onAnchorChanged(anchor)

        assertEquals(emptyList<String>(), lines.toList())
    }

    @Test
    fun `还没有可见页时焦点行写不出位置 不产行`() {
        probe.onWindowFocused(focused = false, anchor = null)
        nowNanos = 10_000_000L
        probe.onWindowFocused(focused = true, anchor = null)

        assertEquals(emptyList<String>(), lines.toList())
    }

    @Test
    fun `开关关着时一行都不记`() {
        PerfTiming.forcedForTest = false
        probe.onWindowFocused(focused = false, anchor = ReaderScrollAnchor(page = 1, percent = 0.0f))
        nowNanos = 10_000_000L
        probe.onWindowFocused(focused = true, anchor = ReaderScrollAnchor(page = 1, percent = 0.0f))
        probe.onAnchorChanged(ReaderScrollAnchor(page = 2, percent = 0.5f))

        assertEquals(emptyList<String>(), lines.toList())
    }
}
