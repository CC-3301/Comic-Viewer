package com.cc3301.comicviewer.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航过渡期帧量测的判据：单帧**严格大于 32ms** 才算一次「超预算帧」；窗口只覆盖
 * 一次导航过渡；**读数 = B1 − B0**（B0 = 热身那一行 X 的 `overBudgetTotal`，B1 = 序号 ≥ X+20 的第一行的值），
 * 而**读数取哪一行**由每行的 `transitions` 序号定位（累计值本身没有复位点，直接读 B1 会把冷启动与热身那几拍算进来）。
 *
 * 设备数据（每次过渡的明细行、10 次的合计）本机取不到（要设备跑 10 次进出阅读器），属残余风险；
 * 本文件只钉「怎么数」——数错的话设备那 10 次读数就没有意义。
 *
 * 另外钉两件让读数可信的事：**收口认代次**（过期的收口定时器不得关掉下一次过渡的窗口）、
 * **迟到帧单独计数**（时间戳早于窗口起点的帧本来属于上一拍）。
 */
class NavTransitionProbeTest {

    @Test
    fun `过渡中超过 32ms 的帧才计数`() {
        val probe = NavTransitionProbe()
        val token = probe.beginTransition(nowNanos = 0L)

        probe.onFrame(totalNanos = 16_000_000L, frameNanos = 1L) // 未超
        probe.onFrame(totalNanos = NavTransitionProbe.OVER_BUDGET_NANOS, frameNanos = 2L) // 恰好 32ms：**不**算超
        probe.onFrame(totalNanos = 32_000_001L, frameNanos = 3L) // 超

        val line = probe.endTransition(token)
        assertTrue("必须有明细行", line != null)
        assertEquals(
            "一帧超预算（恰好 32ms 不算）；均值 = (16 + 32 + 32.000001) / 3 ≈ 26.7ms；最大取整 = 32ms",
            "${NavTransitionProbe.SUMMARY_PREFIX} transitions=1 frames=3 framesBeforeStart=0 overBudget=1 " +
                "overBudgetTotal=1 windowMs=0 frameMeanMs=26.7 frameMaxMs=32 overBudgetBeforeStart=0",
            line,
        )
    }

    @Test
    fun `没开窗的帧一概不计 浏览页滚动的帧不混进来`() {
        val probe = NavTransitionProbe()

        probe.onFrame(totalNanos = 100_000_000L, frameNanos = 1L)

        assertNull("没有过渡在跑时不产行", probe.endTransition(NO_WINDOW))
        assertEquals(
            "${NavTransitionProbe.SUMMARY_PREFIX} transitions=0 frames=0 framesBeforeStart=0 overBudget=0 " +
                "overBudgetTotal=0 windowMs=0 frameMeanMs=0.0 frameMaxMs=0 overBudgetBeforeStart=0",
            probe.summaryLine(),
        )
    }

    @Test
    fun `每行带过渡序号 读数取序号不早于 X+20 的第一行`() {
        // 累计值没有复位点，而序号起点**不是 1**（取数前已经跑过滑动档导航）——
        // 协议是「先记下开始时的序号 X，10 次进出后取序号 **≥ X+20 的第一行**」，不能硬编码读第 20 行；
        // 窗口被下一次 begin 提前打断时计数照样前进而那一拍不产行，因此 `transitions=X+20` 那一行可能不存在。
        val probe = NavTransitionProbe()

        // 取数前已经跑过的拍（生产里例如冷启动直进阅读器那一跳；冷启动落到浏览层的那两跳是硬切，不计拍）
        repeat(3) {
            val token = probe.beginTransition(nowNanos = 0L)
            probe.onFrame(totalNanos = 40_000_000L, frameNanos = 1L)
            probe.endTransition(token)
        }
        val start = transitionsOf(probe.summaryLine())
        assertEquals("开始时的序号 X 不是 1（取数前已经跑过若干拍）", 3, start)

        // 10 次进出阅读器 = 20 次过渡；超预算帧放在 i=0/9/18（不是每 5 拍一个）：
        // 这样「硬编码读 transitions=20」（落在 i=16）与「序号 ≥ X+20 的第一行」（i=19）会数到不同的帧数，
        // 断言因此能咬住读数协议（旧写法两者同值，断言不判别）。
        var readAt: String? = null
        repeat(20) { i ->
            val token = probe.beginTransition(nowNanos = 0L)
            if (i % 9 == 0) probe.onFrame(totalNanos = 40_000_000L, frameNanos = 1L)
            val line = probe.endTransition(token)!!
            if (transitionsOf(line) == start + 20) readAt = line
        }

        assertTrue("序号 X+20 那一行必须存在（每行都带序号）", readAt != null)
        assertTrue(
            "B1（本行累计值）= 3 拍热身 + 这 20 拍里的 3 帧（i=0/9/18）= 6；" +
                "硬编码读 transitions=20（i=16）只数到 2 帧（累计 5），本断言即红：$readAt",
            readAt!!.contains("overBudgetTotal=6"),
        )
    }

    /** 明细行里的窗口序号（从 1 起、跨过渡累计） */
    private fun transitionsOf(line: String): Int = line
        .split(' ')
        .first { it.startsWith("transitions=") }
        .substringAfter('=')
        .toInt()

    @Test
    fun `累计值跨过渡保留 每段明细清零`() {
        val probe = NavTransitionProbe()

        val first = probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 40_000_000L, frameNanos = 1L)
        val firstLine = probe.endTransition(first)
        val second = probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 10_000_000L, frameNanos = 1L)
        val secondLine = probe.endTransition(second)

        assertTrue("第一次过渡有 1 帧超预算", firstLine!!.contains("frames=1 framesBeforeStart=0 overBudget=1 overBudgetTotal=1"))
        assertTrue(
            "10 次那一条判据读累计值：第二次不超预算，累计仍是 1",
            secondLine!!.contains("frames=1 framesBeforeStart=0 overBudget=0 overBudgetTotal=1"),
        )
    }

    /**
     * **连续快速操作**（点开一本书马上返回）：后一次 `begin` 重新开窗——明细只剩第二段、累计值保留；
     * 第一段的收口定时器到点时什么都不做（代次已过期）⇒ 那一拍不产行（见 [NavTransitionProbe] 的读数口径）。
     */
    @Test
    fun `过期的收口不能关掉下一次过渡的窗口`() {
        val probe = NavTransitionProbe()

        val interrupted = probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 40_000_000L, frameNanos = 1L)
        // 用户马上返回：第二段过渡打断第一段
        val current = probe.beginTransition(nowNanos = 1_000_000L)
        probe.onFrame(totalNanos = 8_000_000L, frameNanos = 2_000_000L)

        assertNull("第一段的收口到点时，窗口已经属于第二段", probe.endTransition(interrupted))
        val line = probe.endTransition(current)
        assertTrue("第二段的明细只剩自己那一帧", line!!.contains("frames=1 framesBeforeStart=0 overBudget=0"))
        assertTrue("第一段那一帧仍记在累计里", line.contains("overBudgetTotal=1"))
    }

    /**
     * **迟到帧单独计数**：帧回调在主线程排队后会迟到——**画完在窗口开之前**的那些帧整段属于上一拍。
     * 它们计进 `framesBeforeStart`；其中超预算的另计 `overBudgetBeforeStart`——两者相等就说明这一拍自己的
     * 帧一帧没超（设备读数靠它把「上一段的账」和「本拍的掉帧」分开）。
     */
    @Test
    fun `画完在窗口开之前的帧单独计数`() {
        val probe = NavTransitionProbe()
        val token = probe.beginTransition(nowNanos = 100_000_000L)

        probe.onFrame(totalNanos = 40_000_000L, frameNanos = 20_000_000L) // 60ms 就画完了：整段在窗口之前（且超预算）
        probe.onFrame(totalNanos = 8_000_000L, frameNanos = 112_000_000L) // 本窗口自己的帧

        assertEquals(
            "两帧都计进 frames；超预算那帧是上一拍那帧（overBudget == overBudgetBeforeStart）",
            "${NavTransitionProbe.SUMMARY_PREFIX} transitions=1 frames=2 framesBeforeStart=1 overBudget=1 " +
                "overBudgetTotal=1 windowMs=12 frameMeanMs=24.0 frameMaxMs=40 overBudgetBeforeStart=1",
            probe.endTransition(token),
        )
    }

    /**
     * **本拍第一帧不算「上一拍」**：求值过渡 lambda 的那一帧，vsync 按构造早于窗口起点，但由本拍画完
     * （新目的地的首次组合就发生在这里）。它一旦掉帧必须留在本拍账上，否则「`overBudgetBeforeStart == overBudget`
     * ⇒ 掉帧全是上一拍的账」这条读数不成立。
     */
    @Test
    fun `本拍第一帧的时间戳早于窗口起点 仍算本拍`() {
        val probe = NavTransitionProbe()
        val token = probe.beginTransition(nowNanos = 100_000_000L)

        probe.onFrame(totalNanos = 40_000_000L, frameNanos = 95_000_000L) // vsync 早于起点，画完在窗口开着时

        val line = probe.endTransition(token)
        assertTrue("不许算成上一拍：$line", line!!.contains("frames=1 framesBeforeStart=0"))
        assertTrue("它超预算就该记在本拍账上：$line", line.contains("overBudget=1 overBudgetTotal=1"))
        assertTrue("本拍账里没有「上一拍那一帧」：$line", line.contains("overBudgetBeforeStart=0"))
    }

    @Test
    fun `重复收口不产第二行`() {
        val probe = NavTransitionProbe()
        val token = probe.beginTransition(nowNanos = 0L)
        probe.onFrame(totalNanos = 8_000_000L, frameNanos = 1L)

        assertTrue(probe.endTransition(token) != null)
        assertNull("同一次过渡多收口一次（重复触发）不产生假数据", probe.endTransition(token))
    }

    /**
     * **同一次导航只认第一次求值**：过渡 lambda 在同一次导航里会被求值多次
     * （`AnimatedContent` 对每个内容各求一次），每次都重新开窗并再排一个收口定时器。
     * 闩的键是**前后两条栈项的 id**——每次导航都是新栈项，所以键不会重复（进出方向相反也算两拍）。
     */
    @Test
    fun `同一次导航的重复求值只认第一次`() {
        val once = NavTransitionOnce()

        assertTrue("第一次求值：开一拍", once.openIfFirst(from = "browser-1", to = "reader-2"))
        assertFalse("同一条栈项对的重复求值：不再开拍", once.openIfFirst(from = "browser-1", to = "reader-2"))
        assertTrue("方向反过来是另一拍", once.openIfFirst(from = "reader-2", to = "browser-1"))
        assertTrue("下一次进档换了新栈项 ⇒ 新的键", once.openIfFirst(from = "browser-1", to = "reader-3"))
    }

    private companion object {
        /** 没有开过窗时代次号取 0：窗口代次从 1 起，对不上就不产行 */
        const val NO_WINDOW: Long = 0L
    }
}
