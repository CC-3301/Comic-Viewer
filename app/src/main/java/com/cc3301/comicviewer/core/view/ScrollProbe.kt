package com.cc3301.comicviewer.core.view

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * 浏览页滚动量测的聚合与阈值（票 #109 E3-A「先量再改」，纯逻辑，由 `ScrollProbeTest` 锁定）。
 *
 * 真机上看的是这个方法产出的一行摘要（前缀 [SUMMARY_PREFIX]）与每次封面加载的一行明细
 * （前缀 [COVER_LOAD_PREFIX]），开关是既有的 [PerfTiming.isOn]（`log.tag.ComicViewerPerf` 或应用内「诊断日志」
 * 设置，两者取或；应用内那个开关每次现读、开完立即生效，平台 tag 才需要重启 APP）：
 *
 * ```
 * adb shell setprop log.tag.ComicViewerPerf DEBUG   # 设完重启 APP（isLoggable 按进程缓存）
 * adb logcat -s ComicViewerPerf | grep browse        # 滚动书柜，一行摘要 = 一次滚动
 * ```
 *
 * 改前/改后各抓同一段滚动，比 `janky` / `jankPerSec` / `frameP95Ms` / `drawMaxMs` / `layoutMaxMs`／`coverLoadMaxMs`
 * 与 `coverLoadThreads`（字段含义见下）。量测协议（怎么开 tag、抓哪些行、怎么算指标）见工单 #109；
 * 字段口径如下——**改这里就是改前后对比的数字口径**，因此全部收在一处：
 *
 * - **掉帧（jank）**：一帧的 `FrameMetrics.TOTAL_DURATION` **严格大于** [FRAME_BUDGET_NANOS]（60Hz 一帧预算）。
 * - **掉帧/秒（`jankPerSec`）**：窗口内掉帧数 ÷ 窗口**纳秒**跨度——不随滚动时长漂移，前后可比；
 *   分母**不先截断成整毫秒**（否则亚毫秒窗口会打出 `windowMs=0` 却 `janky=1` 的自相矛盾行）。
 *   百分点 `jankPct` 是同一批掉帧的另一种折算（按帧数，不按时间）。
 * - **滚动活动**：**可见区变化** 或 **滚动偏移变化** 都算一次活动（接线侧 `ui/BrowseScroll` 的键把两者都带上）；
 *   `steps` 就是本窗口登记到的活动次数。偏移进键只补上「collector 跑了但可见区没变」这一支——
 *   活动登记走主线程 `snapshotFlow` collector，发射率受主线程调度约束，因此「一段连续滚动只落一行」
 *   是**真机判据**（工单 #109 取数时核），不是本实现的保证。
 * - **窗口**：第一次滚动活动开窗，之后每帧进统计。窗口时长 `windowMs` 是**窗口内时间戳的包络**：
 *   起点 = min(开窗那次活动的时刻, 窗口内最早一帧的时间戳)，终点 = max(同上, 窗口内最晚一帧的时间戳)。
 *   每帧用的是**帧自己的时间戳**（真机取 `FrameMetrics.INTENDED_VSYNC_TIMESTAMP`，与 `System.nanoTime()`
 *   同一时钟），**不是回调投递时刻**——主线程忙时回调会被突发投递，用投递时刻算窗口会把 `windowMs`
 *   压小、把 `jankPerSec`/`jankPct` 的分母弄成不可信（#109 r4 真机：推出 200+ fps，平台侧同期只有约 105 fps）。
 *   机型不给这个字段（≤ 0）时回落到回调投递时刻（见构造参数 KDoc）——口径退化回 r5 之前，但仍能落行。
 *   **自动落行的唯一触发点是「帧时间戳距最后一次活动 ≥ [IDLE_FLUSH_NANOS] 的那一帧」**——那一帧自己不进统计，
 *   但**它之前**、最后一次活动之后到达的帧照常计入，因此窗口末端可比最后一次滚动长最多 [IDLE_FLUSH_NANOS]
 *   （末尾静止尾巴，`windowMs` 与 `jankPerSec` 的分母都含这一段）。
 * - **收口**：窗口由 [onScrollSessionEnd] 主动收口（落行 + 清零）——浏览页离开组合时调用，因此换层或离开
 *   浏览页不会把两段并进同一行；没有开着的窗口时它不产行（不写空行）。同屏内若停下来后**一帧都不再出**
 *   （无动画、无重绘），窗口不会自动落行，直到下一次滚动与上一段并进同一行（`windowMs` 还会含中间空档）——
 *   这种静止到零帧的情形未做主动计时，读日志时按行数与 `steps=` 交叉核对。
 *   `itemsComposed` / `coversComposed` / 封面加载统计同样**只统计窗口内**的事件：落行后、下一次滚动前的
 *   空闲期事件既不算进上一段、也不算进下一段。**非空**列表首次布局也算一次活动（可见区由空变非空），
 *   因此「一进屏没滚」也会落一行——读日志时按 `steps=` 筛（空目录/首帧尚未布局时的空可见区由接线侧丢掉，
 *   不会开出假窗口）。
 * - **组合次数**：`itemsComposed` / `coversComposed` 是条目 / 封面 composable **体执行次数**，
 *   即这两个层级的实际重组次数（Compose 跳过重组时体不执行、不计数）。
 * - **封面加载**：`coverLoads` / `coverLoadTotalMs` / `coverLoadMaxMs` / `coverLoadThreads` 统计
 *   「取字节 + 解码」**整段**耗时（`CoverThumb` 在 IO 工作线程上量），粒度到单个格子；明细行给出每一次与它的线程名。
 *   **口径边界（票 #112 第 4 条）**：这份统计只覆盖**可见行**那一条路。**预取**（`ui/CoverPrefetchLoad`，
 *   可见区 ±1 屏）没有探针，它解出来的封面不进 `coverLoads`。为什么不补探针（本轮选了改注释而不是补探针，
 *   理由记在 #112 证据）：预取与可见行共用同一份封面分区，同一张封面两条路各报一行会让
 *   `coverLoads` 的「谁慢」变得更难读，而改前/改后对比要的是同一口径的两份数——两份数里都只有可见行那条路，
 *   可比性不受影响。
 *
 * - **打点自身的开销（票 #112 第 5 条）**：开关打开时接线侧会注册一个 `snapshotFlow` 收集器
 *   （每帧构造一次活动键 `BrowseScrollActivity`）并每帧取一次本对象的锁，**这段开销记在被量测的那次运行里**，
 *   因此开着打点的绝对值（`frameMaxMs` / `jankPerSec` / `frameP95Ms`）比关着时**略微偏高**。
 *   改前/改后对比要两边都开着（同一份开销，差值仍可比），不要把开着打点的绝对值当平台基线。
 *   默认关（`PerfTiming.isOn` 为假）时两处接线都不注册，零开销（#109 已验收）。
 *
 * 时间基准：活动登记与帧时间戳都是单调时钟（`System.nanoTime()` / `FrameMetrics` 的 vsync 时间戳同源，可相减）；
 * 帧耗时只用 `FrameMetrics` 给的时长。
 *
 * 与平台侧口径的关系：本判据是「60Hz 一帧预算 + **仅滚动窗口**」，**不与 `adb shell dumpsys gfxinfo` 的全时段
 * 口径对比**（后者含非滚动帧、且按实际刷新率算掉帧）；真机面板在滚动期会提频（实测 ~105–120 fps）⇒
 * `frames ÷ windowMs` 读出的是**当时实际渲染帧率**，不是 60fps，掉帧百分比也按同一份数据读。
 *
 * 线程安全：写方不止一个线程——帧回调与条目/封面组合计数在主线程，封面加载计数在 `Dispatchers.IO` 工作线程
 * （多格可并发）。因此全部字段由一把内部锁护住：计数不会丢更新，`coverLoadThreads` 的迭代也不会遇到
 * `ConcurrentModificationException`（取基线时正是写着读着同时发生）。
 */
internal class ScrollProbe(
    private val idleFlushNanos: Long = IDLE_FLUSH_NANOS,
    /**
     * 帧时间戳缺失（≤ 0）时的**回落时钟**（票 #109 r6）：默认 `System.nanoTime()`（**单调时钟**，非墙钟），
     * 即「回调被投递的时刻」。
     * 机型上 `FrameMetrics.INTENDED_VSYNC_TIMESTAMP` 恒为 0 时，不回落的后果是窗口起点被 `minOf` 拉到 0
     * （`windowMs` 变成设备开机时长量级）且静止判据恒为负 ⇒ **永不自动落行**——只剩离开浏览层时收口那一行。
     * 用例注入可控时钟来锁这条回落。
     */
    private val monotonicNanos: () -> Long = System::nanoTime,
) {

    /** 一把锁护住下面全部字段（见 KDoc「线程安全」）：方法内部互相调用是同线程重入，不会再取锁 */
    private val lock = Any()

    private var active = false
    private var windowStartNanos = 0L
    private var windowEndNanos = 0L
    private var lastActivityNanos = 0L
    private var frames = 0
    private var janky = 0
    private var steps = 0
    private var totalSumNanos = 0L
    private var maxTotalNanos = 0L
    private var maxLayoutNanos = 0L
    private var maxDrawNanos = 0L
    private val samples = ArrayList<Long>()
    private var itemsComposed = 0
    private var coversComposed = 0
    private var coverLoads = 0
    private var coverLoadTotalMs = 0L
    private var coverLoadMaxMs = 0L
    private val coverLoadThreads = HashMap<String, Int>()

    /** 一次滚动活动（可见区变化 **或** 滚动偏移变化）：开窗 / 续窗 + 计一次步进（取锁） */
    fun markScrollActivity(nowNanos: Long) = synchronized(lock) {
        if (!active) {
            active = true
            windowStartNanos = nowNanos
            windowEndNanos = nowNanos
        }
        steps++
        lastActivityNanos = nowNanos
    }

    /**
     * 一帧的原始量测（真机上分别来自 `FrameMetrics` 的 TOTAL / LAYOUT_MEASURE / DRAW，以及**帧自己的时间戳**
     * `INTENDED_VSYNC_TIMESTAMP`）。
     *
     * [frameNanos] 必须是帧时间戳而不是回调投递时刻：主线程忙时回调会被突发投递，用投递时刻算窗口会把
     * `windowMs` 压小（见类 KDoc 的窗口口径）。**[frameNanos] ≤ 0（机型不给这个字段）时回落到
     * [monotonicNanos]，否则窗口与静止判据都不成立**（见构造参数 KDoc）。
     *
     * 返回非空 = 本窗口已静止，该把这一行摘要打出去（窗口随后清零；**本帧不进统计**，见类 KDoc 的窗口口径）。
     */
    fun onFrame(totalNanos: Long, layoutNanos: Long, drawNanos: Long, frameNanos: Long): String? =
        synchronized(lock) {
            if (!active) return@synchronized null
            val frameTs = if (frameNanos > 0L) frameNanos else monotonicNanos()
            // 静止帧只负责落行：先判静止、再决定要不要计数（静止判据同样用帧时间戳）
            if (frameTs - lastActivityNanos >= idleFlushNanos) {
                val line = summaryLine()
                resetWindow()
                return@synchronized line
            }
            frames++
            if (totalNanos > FRAME_BUDGET_NANOS) janky++
            totalSumNanos += totalNanos
            // 帧耗时样本只用于 p95：超上限后不再收集（长滚动下 p95 取前 MAX_WINDOW_FRAMES 帧的样本，仍是同一口径的分布）
            if (samples.size < MAX_WINDOW_FRAMES) samples.add(totalNanos)
            maxTotalNanos = maxOf(maxTotalNanos, totalNanos)
            maxLayoutNanos = maxOf(maxLayoutNanos, layoutNanos)
            maxDrawNanos = maxOf(maxDrawNanos, drawNanos)
            // 窗口 = 活动时刻与窗口内帧时间戳的包络：登记被压后时帧时间戳说了算，窗口因此不被压小
            windowStartNanos = minOf(windowStartNanos, frameTs)
            windowEndNanos = maxOf(windowEndNanos, frameTs)
            null
        }

    /**
     * 收口当前滚动段：落一行摘要并清零窗口，返回那一行（没有开着的窗口时返回 null、不产空行）。
     *
     * 由**离开浏览层**的存活期钩子调用（`ui/BrowseScroll` 的 `onDispose`）：自动落行要等「静止 ≥
     * [IDLE_FLUSH_NANOS] 的那一帧」，而滚动后不到 500ms 就换层（返回上级 / 进子目录 / 点书切阅读页）时
     * 那一帧永远不会来——不在这里收口，窗口就会跨屏存活，下一屏的条目/封面事件并进同一行。
     */
    fun onScrollSessionEnd(): String? = synchronized(lock) {
        if (!active) return@synchronized null
        val line = summaryLine()
        resetWindow()
        line
    }

    /** 条目 composable（列表行 / 网格格）体执行一次 = 条目层一次实际重组；**只统计活动窗口内**的（取锁） */
    fun onItemComposed() = synchronized(lock) {
        if (active) itemsComposed++
    }

    /** 封面 composable 体执行一次 = 封面层一次实际重组；**只统计活动窗口内**的（取锁） */
    fun onCoverComposed() = synchronized(lock) {
        if (active) coversComposed++
    }

    /**
     * 一格封面的「取字节 + 解码」整段耗时与执行它的线程（在 IO 工作线程上量）。
     * **只统计活动窗口内**的（取锁；写方是多个 IO 线程，见类 KDoc）。
     * 唯一调用点是可见行 `ui/CoverThumb`：预取那条路没有探针（口径边界见类 KDoc 的「封面加载」那条）。
     */
    fun onCoverLoad(millis: Long, thread: String) = synchronized(lock) {
        if (!active) return@synchronized
        coverLoads++
        coverLoadTotalMs += millis
        coverLoadMaxMs = maxOf(coverLoadMaxMs, millis)
        val name = token(thread)
        coverLoadThreads[name] = (coverLoadThreads[name] ?: 0) + 1
    }

    /** 当前窗口的摘要行（空格分隔的 key=value，便于 grep 与机械比对）；窗口为空时各项为 0，不除零（取锁） */
    fun summaryLine(): String = synchronized(lock) {
        val windowNanos = (windowEndNanos - windowStartNanos).coerceAtLeast(0L)
        // 人读的 windowMs 取整毫秒；**分母用纳秒跨度**（不先截断，否则亚毫秒窗口会打出 jankPerSec=0.0 而 janky=1）
        val windowMs = windowNanos / 1_000_000
        val seconds = windowNanos / 1e9
        val jankPct = if (frames == 0) 0.0 else janky * 100.0 / frames
        val jankPerSec = if (seconds <= 0.0) 0.0 else janky / seconds
        val avgMs = if (frames == 0) 0.0 else totalSumNanos.toDouble() / frames / 1_000_000.0
        return buildString {
            append(SUMMARY_PREFIX)
            append(" frames=").append(frames)
            append(" janky=").append(janky)
            append(" jankPct=").append(oneDecimal(jankPct))
            append(" jankPerSec=").append(oneDecimal(jankPerSec))
            append(" windowMs=").append(windowMs)
            append(" frameAvgMs=").append(oneDecimal(avgMs))
            append(" frameP95Ms=").append(millisRounded(percentileNanos(0.95)))
            append(" frameMaxMs=").append(millisRounded(maxTotalNanos))
            append(" layoutMaxMs=").append(millisRounded(maxLayoutNanos))
            append(" drawMaxMs=").append(millisRounded(maxDrawNanos))
            append(" steps=").append(steps)
            append(" itemsComposed=").append(itemsComposed)
            append(" coversComposed=").append(coversComposed)
            append(" coverLoads=").append(coverLoads)
            append(" coverLoadTotalMs=").append(coverLoadTotalMs)
            append(" coverLoadMaxMs=").append(coverLoadMaxMs)
            append(" coverLoadThreads=").append(
                coverLoadThreads.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" },
            )
        }
    }

    /** 最近秩分位（`ceil(q × n)` 名）：p95 因此是真实帧耗时、不是插值出来的中间值；只在持锁的调用方里用 */
    private fun percentileNanos(q: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** 窗口清零（落行后调用）；只在持锁的调用方里用 */
    private fun resetWindow() {
        active = false
        windowStartNanos = 0L
        windowEndNanos = 0L
        lastActivityNanos = 0L
        frames = 0
        janky = 0
        steps = 0
        totalSumNanos = 0L
        maxTotalNanos = 0L
        maxLayoutNanos = 0L
        maxDrawNanos = 0L
        samples.clear()
        itemsComposed = 0
        coversComposed = 0
        coverLoads = 0
        coverLoadTotalMs = 0L
        coverLoadMaxMs = 0L
        coverLoadThreads.clear()
    }

    companion object {

        /** 一帧预算：60Hz 的 16.67ms。超过它即掉帧（真机帧率不是 60Hz 时按同一判据读，改动前后一致即可比） */
        const val FRAME_BUDGET_NANOS: Long = 16_666_667L

        /** 最后一次滚动活动之后静止这么久就落一行摘要 */
        const val IDLE_FLUSH_NANOS: Long = 500_000_000L

        /** 帧耗时样本上限（p95 用；只防长滚动下无界增长，不影响 frames/janky 计数） */
        const val MAX_WINDOW_FRAMES: Int = 8192

        /** 摘要行前缀（`adb logcat -s ComicViewerPerf | grep browseScroll`） */
        const val SUMMARY_PREFIX: String = "browseScroll"

        /** 单次封面加载明细行前缀（`… | grep browseCoverLoad`） */
        const val COVER_LOAD_PREFIX: String = "browseCoverLoad"

        /** 单次封面加载明细行（线程名里的空白折成下划线，保证整行是空格分隔的 key=value） */
        fun coverLoadLine(millis: Long, thread: String): String =
            "$COVER_LOAD_PREFIX ms=$millis thread=${token(thread)}"
    }
}

/**
 * 一位小数的定点格式：**不走 `String.format`**——默认 Locale 里小数点是逗号（德语区等）时，行里会出现
 * `jankPct=6,7`，前后数字没法机械比对（票 #109）。
 */
internal fun oneDecimal(value: Double): String {
    val scaled = round(value * 10).toLong()
    val abs = abs(scaled)
    return (if (scaled < 0) "-" else "") + (abs / 10) + "." + (abs % 10)
}

/** 纳秒 → 毫秒（四舍五入到整毫秒）：摘要里的时长字段都过它 */
private fun millisRounded(nanos: Long): Long = (nanos + 500_000L) / 1_000_000L

/** 令牌化：空格/制表/换行折成下划线（线程名可能带空白，摘要行靠空格切分） */
private fun token(value: String): String = value.map { if (it.isWhitespace()) '_' else it }.joinToString("")
