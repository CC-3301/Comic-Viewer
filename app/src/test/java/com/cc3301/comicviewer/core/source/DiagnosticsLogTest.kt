package com.cc3301.comicviewer.core.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 诊断日志的内存环形缓冲与应用内开关（票 #113 修复轮）。
 *
 * 两条**必须能失败**的判据（改坏实现就红）：
 * ① 开关打开 → 打点行落进缓冲（把 [PerfTiming.isOn] 里的 `DiagnosticsLog.enabled` 去掉，本用例第一条红）；
 * ② 开关关闭 → 缓冲为空，且**消息 lambda 一次都不执行**（把 [PerfTiming.log] 的短路或 [PerfTiming.emit] 的
 * 入口判断去掉，本用例第二条红）——这就是票面「关闭时零开销」的可执行口径。
 *
 * 平台值（`log.tag.ComicViewerPerf`）在 JVM 单测里不可用（`android.util.Log` 是空实现，`runCatching` 兜住），
 * 因此这里量到的开关**只可能**来自应用内开关。用 `PerfTiming.forcedForTest` 的既有用例不受影响：它是三者的最高优先。
 */
class DiagnosticsLogTest {

    @Before
    fun 清空状态() {
        DiagnosticsLog.clear()
        DiagnosticsLog.enabled = false
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
    }

    @After
    fun 还原开关() {
        DiagnosticsLog.enabled = false
        DiagnosticsLog.clear()
        PerfTiming.forcedForTest = null
        PerfTiming.recordedLinesForTest = null
    }

    @Test
    fun `应用内开关打开时打点落进环形缓冲`() {
        DiagnosticsLog.enabled = true

        PerfTiming.log { "sourceOpen instance=DocumentTreeSource#abc" }

        assertEquals("开关打开就该记下这一行", 1, DiagnosticsLog.count)
        val line = DiagnosticsLog.snapshot().single()
        assertEquals("sourceOpen instance=DocumentTreeSource#abc", line.text)
        assertTrue("行上要带墙钟时刻（导出按它算时间范围与行首时间戳）", line.atMs > 0L)
    }

    @Test
    fun `应用内开关关闭时缓冲为空且不拼字符串`() {
        var built = 0

        PerfTiming.log { built++; "sourceOpen instance=不该被拼出来" }

        assertEquals("关闭时不允许记任何行", 0, DiagnosticsLog.count)
        assertEquals("关闭时连字符串都不该拼（零开销口径）", 0, built)
    }

    @Test
    fun `关闭时直接走 emit 也不写缓冲`() {
        // 旁路调用（将来若有人绕过 log 直接 emit）：入口判断必须挡住它
        PerfTiming.emit("旁路行")

        assertEquals(0, DiagnosticsLog.count)
    }

    @Test
    fun `环形缓冲满了丢最旧的一行`() {
        DiagnosticsLog.enabled = true
        repeat(DiagnosticsLog.CAPACITY + 3) { i -> DiagnosticsLog.record("line-$i", atMs = i.toLong() + 1) }

        val snapshot = DiagnosticsLog.snapshot()
        assertEquals("容量就是上界", DiagnosticsLog.CAPACITY, snapshot.size)
        assertEquals("满了丢最旧", "line-3", snapshot.first().text)
        assertEquals("最新的那行在末尾", "line-" + (DiagnosticsLog.CAPACITY + 2), snapshot.last().text)
    }
}
