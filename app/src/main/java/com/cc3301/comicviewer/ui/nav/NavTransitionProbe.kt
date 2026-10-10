package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.core.view.oneDecimal

/**
 * 导航过渡期的帧时长量测（纯逻辑，由 `NavTransitionProbeTest` 锁定）。
 *
 * 判据：过渡期间的**主线程帧时长**——单帧 `FrameMetrics.TOTAL_DURATION` **严格大于**
 * [OVER_BUDGET_NANOS]（**32ms**）即计一次「超预算帧」；采集范围是**所有导航过渡**（进出阅读器 + 层级导航 +
 * 换书），接线挂在导航壳上（`AppNav` 的 `NavHost` 过渡 lambda + [NavTransitionFrameMetrics]）。
 * 通过标准：连续 10 次进出阅读器，合计超预算帧 **≤ 2**（层级导航与换书同标准）。因此本对象除了每次过渡的
 * 明细行，还给出**跨过渡累计**的 `overBudgetTotal`。
 *
 * **读数口径**：累计值**没有显式复位点**——它只在 `AppNav` 的 `remember { NavTransitionProbe() }`
 * 那个实例的存活期内累计（`remember` 跨重组、**不跨** Activity 重建：转屏/重建会新建对象、序号与累计都从头开始，
 * 取数时不要转屏），单看 `overBudgetTotal` 分不清「刚跑的这 10 次」与
 * 「进程以来全部」。因此每行还带 `transitions`（本对象开过的过渡窗口序号，从 1 起）：取数时**重启 APP**
 * （平台 tag 的开关同样要重启才生效；应用内「诊断日志」开关立即生效），先做一次进出并**记下那一行的序号 X 与它的累计值 B0**，
 * 再连做 10 次进出阅读器（理想 20 拍），把序号 **≥ X+20 的第一行**的累计值记为 B1——**窗口被下一次 begin
 * 提前打断时计数照样前进而那一拍不产行**（[endTransition] 代次对不上或没有开着的窗口就不落行），因此 `transitions=X+20`
 * 那一行可能不存在，取第一条不早于它的行即可。
 * **读数 = B1 − B0（≤ 2）**：`overBudgetTotal` 是累计值，B1 里**含着 X 之前的帧**（X 那次热身那几帧），
 * 直接读 B1 会把它们算进来（方向是偏保守的误判，不是漏判）；不要手工相加、也不要读最后一行。
 * **序号每次导航涨 1**：硬切（层级导航 / 换书）时长 0 ⇒ 不开窗、序号也不涨；冷启动**落到浏览层**的那两跳
 * （`navigate(HOME)` 与逐层 `pushBrowserPath`）同样是硬切、不计拍，冷启动**直进阅读器**是进档、计一拍。
 * 两次取数之间插入进出阅读器的导航时按实际拍数往后推。
 *
 * **帧归哪一拍**：`framesBeforeStart` > 0 说明这一行里混了**画完在窗口开之前**的帧（回调在主线程排队后迟到），
 * 它们**照样计进** `frames` 与各项统计；其中超预算的另计 `overBudgetBeforeStart`——它与 `overBudget` 相等
 * 就说明**这一拍的掉帧全是上一拍的账**，本拍自己的帧一帧没超。量化读数取 `framesBeforeStart=0` 的行。
 * 判据是「帧起点 + 该帧时长 < 窗口起点」，**不能只比时间戳**：求值过渡 lambda 的那一帧就是本拍第一帧
 * （新目的地的首次组合就发生在这里），它的 vsync 按构造早于窗口起点，但画完在窗口开着的时候。
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
    /** 单帧预算（纳秒）：**32ms**（比 60Hz 的 16.67ms 宽松——过渡期是每帧最重的组合，量的是「明显卡顿」） */
    private val budgetNanos: Long = OVER_BUDGET_NANOS,
    /** 帧时间戳缺失（≤ 0）时的**回落时钟**（单调时钟，非墙钟），同 [ScrollProbe] 的构造参数 */
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    private val lock = Any()

    private var active = false
    /** 当前窗口的代次号（从 1 起、进程内单调）：收口要带它回来，见 [endTransition] */
    private var generation = 0L
    private var startNanos = 0L
    private var endNanos = 0L
    private var frames = 0
    /** 时间戳早于本窗口起点的帧（上一拍的迟到帧），见 [onFrame] */
    private var framesBeforeStart = 0
    /** 其中**超预算**的那些：与 [overBudget] 相等 ⇒ 这一拍的掉帧全是上一拍的账，见 [onFrame] */
    private var overBudgetBeforeStart = 0
    private var overBudget = 0
    private var overBudgetTotal = 0
    /** 本对象开过的过渡窗口序号（从 1 起、进程内单调）：「连续 10 次」据它定位读数那一行，见类 KDoc */
    private var transitions = 0
    private var sumTotalNanos = 0L
    private var maxTotalNanos = 0L

    /**
     * 一次导航过渡开始：开窗并清零本次明细（累计值 [overBudgetTotal] 与窗口序号 `transitions` 跨过渡保留）。
     *
     * 返回**本次窗口的代次号**（从 1 起）：收口时必须带回来——一次导航排下的收口定时器在到点前
     * 可能已经被下一次导航顶掉（点开一本书马上返回），没有代次号就会把下一次的窗口当自己的收掉。
     */
    fun beginTransition(nowNanos: Long = monotonicNanos()): Long = synchronized(lock) {
        active = true
        transitions++
        generation++
        startNanos = nowNanos
        endNanos = nowNanos
        frames = 0
        framesBeforeStart = 0
        overBudget = 0
        overBudgetBeforeStart = 0
        sumTotalNanos = 0L
        maxTotalNanos = 0L
        generation
    }

    /**
     * 过渡窗口内的一帧。[totalNanos] = `FrameMetrics.TOTAL_DURATION`；[frameNanos] = 帧自己的时间戳
     * （≤ 0 时回落 [monotonicNanos]）。**窗口没开（不在过渡中）的帧一概不计**——浏览页滚动的帧由
     * [ScrollProbe] 统计，两者不混。
     */
    fun onFrame(totalNanos: Long, frameNanos: Long = monotonicNanos()) = synchronized(lock) {
        if (!active) return@synchronized
        frames++
        // 画完在窗口开之前的帧（回调迟到）整段属于上一拍：单独计数。
        // 只比时间戳会错杀本拍第一帧（它由本拍画完，vsync 却早于窗口起点），因此用帧起点 + 帧时长判
        val beforeStart = frameNanos > 0L && frameNanos + totalNanos < startNanos
        if (beforeStart) framesBeforeStart++
        if (totalNanos > budgetNanos) {
            overBudget++
            overBudgetTotal++
            if (beforeStart) overBudgetBeforeStart++
        }
        sumTotalNanos += totalNanos
        maxTotalNanos = maxOf(maxTotalNanos, totalNanos)
        endNanos = maxOf(endNanos, if (frameNanos > 0L) frameNanos else monotonicNanos())
    }

    /**
     * 过渡结束：落一行明细并关窗。没有开着的窗口、或 [token] 不是当前窗口的代次时都不产行（返回 null）
     * ——过期的收口定时器因此无害：它既不打假数据，也不会关掉下一次过渡的窗口。
     */
    fun endTransition(token: Long): String? = synchronized(lock) {
        if (!active || token != generation) return@synchronized null
        val line = summaryLine()
        active = false
        line
    }

    /**
     * 当前窗口的明细行（空格分隔的 key=value，便于 grep 与机械比对）；窗口为空时各项为 0，不除零。
     * `framesBeforeStart` = **画完在窗口开之前**的帧数（回调迟到，整段属于上一拍）：>0 ⇒ 这一行混了上一拍的账，
     * 判读要打折扣。`overBudgetBeforeStart` = 其中超预算的那些：与 `overBudget` 相等 ⇒ 这一拍的掉帧全是上一拍的账。
     */
    fun summaryLine(): String = synchronized(lock) {
        val windowMs = (endNanos - startNanos).coerceAtLeast(0L) / 1_000_000
        val meanMs = if (frames == 0) 0.0 else sumTotalNanos.toDouble() / frames / 1_000_000.0
        buildString {
            append(SUMMARY_PREFIX)
            append(" transitions=").append(transitions)
            append(" frames=").append(frames)
            append(" framesBeforeStart=").append(framesBeforeStart)
            append(" overBudget=").append(overBudget)
            append(" overBudgetTotal=").append(overBudgetTotal)
            append(" windowMs=").append(windowMs)
            append(" frameMeanMs=").append(oneDecimal(meanMs))
            append(" frameMaxMs=").append((maxTotalNanos + 500_000L) / 1_000_000L)
            append(" overBudgetBeforeStart=").append(overBudgetBeforeStart)
        }
    }

    companion object {

        /** 单帧预算：**32ms**（严格大于才算超预算） */
        const val OVER_BUDGET_NANOS: Long = 32_000_000L

        /** 明细行前缀（`adb logcat -s ComicViewerPerf | grep navTransition`） */
        const val SUMMARY_PREFIX: String = "navTransition"
    }
}
