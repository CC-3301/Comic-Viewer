package com.cc3301.comicviewer.core.view

/**
 * 导航过渡期的帧时长量测（票 #111 AC-9，纯逻辑，由 `NavTransitionProbeTest` 锁定）。
 *
 * 判据（票面第 5 节）：过渡期间的**主线程帧时长**——单帧 `FrameMetrics.TOTAL_DURATION` **严格大于**
 * [OVER_BUDGET_NANOS]（**32ms**）即计一次「超预算帧」；采集范围是**所有导航过渡**（进出阅读器 + 层级导航 +
 * 换书），接线挂在导航壳上（`AppNav` 的 `NavHost` 过渡 lambda + [com.cc3301.comicviewer.ui.NavTransitionFrameMetrics]）。
 * 通过标准：连续 10 次进出阅读器，合计超预算帧 **≤ 2**（层级导航与换书同标准）。因此本对象除了每次过渡的
 * 明细行，还给出**跨过渡累计**的 `overBudgetTotal` —— 10 次那一条判据直接读它，不必手工相加。
 *
 * 与 [ScrollProbe] 的关系：内核同源（活动开窗 → 每帧计入 → 主动收口落行），但**窗口不同**——[ScrollProbe]
 * 的窗口由滚动活动开关、且只有「静止 ≥ 500ms」或离开浏览页才收口；导航过渡的窗口由导航**显式开**、
 * 由过渡时长到点**显式收**（`beginNavTransitionProbe` 里的延迟收口），因此浏览页滚动窗口不会与过渡窗口混。
 * 两段窗口落到同一份日志开关（`log.tag.ComicViewerPerf`）下、用不同前缀区分（[SUMMARY_PREFIX] vs
 * `browseScroll`）。
 *
 * 时间基准与线程：[ScrollProbe] 同口径——窗口起点与每帧时间戳都取帧自己的 `INTENDED_VSYNC_TIMESTAMP`
 * （与 `System.nanoTime()` 同一单调时钟），机型不给该字段（≤ 0）时回落到 [monotonicNanos]。写方只有主线程
 * （帧回调与导航 lambda），但仍用一把锁护住全部字段——与 [ScrollProbe] 一致，且将来的调用方不必先证明单线程。
 */
internal class NavTransitionProbe(
    /** 单帧预算（纳秒）：票面 **32ms**（比 60Hz 的 16.67ms 宽松——过渡期是每帧最重的组合，量的是「明显卡顿」） */
    private val budgetNanos: Long = OVER_BUDGET_NANOS,
    /** 帧时间戳缺失（≤ 0）时的**回落时钟**（单调时钟，非墙钟），同 [ScrollProbe] 的构造参数 */
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    private val lock = Any()

    private var active = false
    private var startNanos = 0L
    private var endNanos = 0L
    private var frames = 0
    private var overBudget = 0
    private var overBudgetTotal = 0
    private var sumTotalNanos = 0L
    private var maxTotalNanos = 0L

    /** 一次导航过渡开始：开窗并清零本次明细（累计值 [overBudgetTotal] 跨过渡保留） */
    fun beginTransition(nowNanos: Long = monotonicNanos()) = synchronized(lock) {
        active = true
        startNanos = nowNanos
        endNanos = nowNanos
        frames = 0
        overBudget = 0
        sumTotalNanos = 0L
        maxTotalNanos = 0L
    }

    /**
     * 过渡窗口内的一帧。[totalNanos] = `FrameMetrics.TOTAL_DURATION`；[frameNanos] = 帧自己的时间戳
     * （≤ 0 时回落 [monotonicNanos]）。**窗口没开（不在过渡中）的帧一概不计**——浏览页滚动的帧由
     * [ScrollProbe] 统计，两者不混。
     */
    fun onFrame(totalNanos: Long, frameNanos: Long = monotonicNanos()) = synchronized(lock) {
        if (!active) return@synchronized
        frames++
        if (totalNanos > budgetNanos) {
            overBudget++
            overBudgetTotal++
        }
        sumTotalNanos += totalNanos
        maxTotalNanos = maxOf(maxTotalNanos, totalNanos)
        endNanos = maxOf(endNanos, if (frameNanos > 0L) frameNanos else monotonicNanos())
    }

    /** 过渡结束：落一行明细并关窗；没有开着的窗口时不产行（返回 null） */
    fun endTransition(): String? = synchronized(lock) {
        if (!active) return@synchronized null
        val line = summaryLine()
        active = false
        line
    }

    /** 当前窗口的明细行（空格分隔的 key=value，便于 grep 与机械比对）；窗口为空时各项为 0，不除零 */
    fun summaryLine(): String = synchronized(lock) {
        val windowMs = (endNanos - startNanos).coerceAtLeast(0L) / 1_000_000
        val meanMs = if (frames == 0) 0.0 else sumTotalNanos.toDouble() / frames / 1_000_000.0
        buildString {
            append(SUMMARY_PREFIX)
            append(" frames=").append(frames)
            append(" overBudget=").append(overBudget)
            append(" overBudgetTotal=").append(overBudgetTotal)
            append(" windowMs=").append(windowMs)
            append(" frameMeanMs=").append(oneDecimal(meanMs))
            append(" frameMaxMs=").append((maxTotalNanos + 500_000L) / 1_000_000L)
        }
    }

    companion object {

        /** 单帧预算：票面 **32ms**（严格大于才算超预算） */
        const val OVER_BUDGET_NANOS: Long = 32_000_000L

        /** 明细行前缀（`adb logcat -s ComicViewerPerf | grep navTransition`） */
        const val SUMMARY_PREFIX: String = "navTransition"
    }
}
