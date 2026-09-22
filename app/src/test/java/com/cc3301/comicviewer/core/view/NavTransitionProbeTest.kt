package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航过渡期帧量测的判据（票 #111 AC-9）：单帧**严格大于 32ms** 才算一次「超预算帧」；窗口只覆盖
 * 一次导航过渡；跨过渡累计（`overBudgetTotal`）是票面「连续 10 次进出，> 32ms 的帧 ≤ 2 帧」的读数口径。
 *
 * 真机数据（每次过渡的明细行、10 次的合计）本机取不到（要真机跑 10 次进出阅读器），属残余风险；
 * 本文件只钉「怎么数」——数错的话真机那 10 次读数就没有意义。
 */
class NavTransitionProbeTest {

    @Test
    fun `过渡中超过 32ms 的帧才计数`() {
        val probe = NavTransitionProbe()
        probe.beginTransition(nowNanos = 0L)

        probe.onFrame(totalNanos = 16_000_000L, frameNanos = 1L) // 未超
        probe.onFrame(totalNanos = NavTransitionProbe.OVER_BUDGET_NANOS, frameNanos = 2L) // 恰好 32ms：**不**算超
        probe.onFrame(totalNanos = 32_000_001L, frameNanos = 3L) // 超

        val line = probe.endTransition()
        assertTrue("必须有明细行", line != null)
        assertEquals(
            "一帧超预算（恰好 32ms 不算）；均值 = (16 + 32 + 32.000001) / 3 ≈ 26.7ms；最大取整 = 32ms",
            "${NavTransitionProbe.SUMMARY_PREFIX} frames=3 overBudget=1 overBudgetTotal=1 windowMs=0 " +
                "frameMeanMs=26.7 frameMaxMs=32",
            line,
        )
    }

    @Test
    fun `没开窗的帧一概不计 浏览页滚动的帧不混进来`() {
        val probe = NavTransitionProbe()

        probe.onFrame(totalNanos = 100_000_000L, frameNanos = 1L)

        assertNull("没有过渡在跑时不产行", probe.endTransition())
        assertEquals(
            "${NavTransitionProbe.SUMMARY_PREFIX} frames=0 overBudget=0 overBudgetTotal=0 windowMs=0 " +
                "frameMeanMs=0.0 frameMaxMs=0",
            probe.summaryLine(),
        )
    }

    @Test
    fun `累计值跨过渡保留 每段明细清零`() {
        val probe = NavTransitionProbe()

        probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 40_000_000L, frameNanos = 1L)
        val first = probe.endTransition()
        probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 10_000_000L, frameNanos = 1L)
        val second = probe.endTransition()

        assertTrue("第一次过渡有 1 帧超预算", first!!.contains("frames=1 overBudget=1 overBudgetTotal=1"))
        assertTrue(
            "10 次那一条判据读累计值：第二次不超预算，累计仍是 1",
            second!!.contains("frames=1 overBudget=0 overBudgetTotal=1"),
        )
    }

    @Test
    fun `连续快速操作时后一次 begin 重新开窗 不把两段并成一行`() {
        val probe = NavTransitionProbe()

        probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 40_000_000L, frameNanos = 1L)
        // 第二段过渡打断第一段（连续快速操作）：begin 清零明细、保留累计
        probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 8_000_000L, frameNanos = 2L)

        val line = probe.endTransition()
        assertTrue("明细只剩第二段那一帧", line!!.contains("frames=1 overBudget=0"))
        assertTrue("第一段那一帧仍记在累计里", line.contains("overBudgetTotal=1"))
    }

    @Test
    fun `重复收口不产第二行`() {
        val probe = NavTransitionProbe()
        probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 8_000_000L, frameNanos = 1L)

        assertTrue(probe.endTransition() != null)
        assertNull("同一次过渡多收口一次（重复触发）不产生假数据", probe.endTransition())
    }
}
