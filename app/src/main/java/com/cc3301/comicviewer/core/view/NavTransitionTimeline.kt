package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.source.PerfTiming

/**
 * 导航过渡的**时刻线**打点（黑帧取数，纯逻辑 + 既有 [PerfTiming] 落地）。
 *
 * 现象（设备反馈，两次报告）：**进阅读器是「先黑屏 → 滑入 → 出现封面」**，退出阅读器**也会先闪一下**；
 * 要求「不要先黑屏，先滑入」。本机没有设备、也判不出黑帧来自哪里，因此本轮的**前置约定是先取数**：
 * 把一次过渡里各个时刻的相对时间打进日志，用差值把三条候选钉死到一条：
 *
 * | 读数 | 结论 |
 * |---|---|
 * | `request → begin` 大 | 入口协程跑到「调用导航」之后，导航栈变化却迟迟没来（被重活挡住） |
 * | `begin → animIssue` 大 | **动画起晚**（结构性：栈都变了，动画还没发出去） |
 * | `animIssue → animStart` 大 | 动画发出了，但**这一屏的首帧绘制晚**（主线程被重活占住） |
 * | `animStart → contentReady` 大 | 滑行期间**屏上没有正文**（等页窗口：主题背景色）——「滑完还没好」 |
 * | `firstDraw` 行里 `offX ≈ 0` 而 `travel != 0` | 新屏**首帧就整屏画在屏上**了 ⇒ 滑之前那一帧是黑的（顺序反了） |
 * | `contentReady < animStart` | 正文比动画更早备好。**这不是异常**：命中解码缓存时页面本来就快（`offX` 那一行才是判「先黑」的那条） |
 *
 * 时刻（每一行都以 [PREFIX] 开头，`id` 相同的是同一次过渡）：
 * 1. `request` —— 调用导航那一刻（四条开书入口的共用通道在 `navigate` 前一行调 [request]）；
 * 2. `begin` —— 导航栈变化被观测到（`ui/AppNav.kt` 的 `NavSlideAnimations.observe`）；
 * 3. `compose` / `firstDraw` / `animIssue` / `animStart` —— 新屏**帧壳**首次组合 / 该屏第一帧绘制（带首帧位移）/
 *    动画发出 / 动画第一次真的动；
 *    **`compose` 记的是帧壳**：**「进阅读器」那一档**的新屏要先挂「壳 + 位移」，正文（阅读页整棵子树 / 浏览列表）比它晚
 *    `AppNav.kt` 的 `ENTERING_SHELL_FRAMES` 帧才组合；**返回档（出阅读器的新屏 = 浏览页）当帧组合同屏（不计入壳帧）**，
 *    冷启动淡变那一档与旧屏也都不挂壳、当帧组合同屏；
 *    读 `compose → animStart` 的差值时**不要**把这段差当成「动画起晚」。
 * 4. `contentReady` —— 阅读页那条「整屏慢慢显」的闸门第一次打开（= 正文开始可见）；
 * 5. 首图**真正上屏**时刻沿用既有的 `pageShown book=… index=<入口页>` 行（`PageDecoder` 打，不在这里重复）。
 *    **注意 index 不一定为 0**：续读落地时入口页是上次读到的那一页（`openBookAtLanding` 给的 `startIndex`）；
 *    这也是「首个可见帧」在自驱动画下**没有单独一行**的原因——该屏首帧就带着位移（见 [KIND_ENTER_READER] 那几条
 *    读数），像素级的「第一次露出来」与 `animStart` 同一帧，能决定「黑不黑」的是 `contentReady`。
 *
 * **每个时刻带的字段（设备 grep 用；字段名只在这里列一次）**：
 *
 * | phase | 字段 |
 * |---|---|
 * | `request` | 无（只有 `id`/`phase`/`t`） |
 * | `begin` | `kind=`（四种类别之一）`from=`上一屏路由（没有写 `none`）`to=`新屏路由；另有可选的 `staleRequest=` |
 * | `compose` | `entry=`栈项 id（该屏**帧壳**；**进阅读器那一档**的新屏正文晚 `ENTERING_SHELL_FRAMES` 帧组合，**返回档当帧组合同屏、不计入壳帧**）`role=Entering\|Exiting` `style=Slide\|Fade` `dur=<毫秒>ms` |
 * | `animIssue` / `animStart` | `entry=` `role=` |
 * | `firstDraw` | `entry=` `role=` `offX=`首帧当时的位移（px）`travel=`整屏行程（px） |
 * | `contentReady` | `book=`书 id |
 *
 * **t 的基准是这次过渡的 t0**（= 最近的 [request]，没有就用 [begin] 那一刻），单位毫秒。
 * **返回方向没有 `request`**：系统返回没有应用层入口（`BackHandler` 一旦注册就会吞掉事件，为打点加一个
 * 会改变行为），因此出方向的 t0 就是 `begin`（栈变化那一刻）——「动画起晚 / 首帧黑」两条判据照旧可读。
 *
 * **开关**：就是既有的 [PerfTiming]（`log.tag.ComicViewerPerf` 或设置页「诊断日志」，默认关）——
 * 关着时本对象**一行不产、状态也不推进、连 detail 的字符串都不拼**（`detail` 是 lambda，见各函数的签名）。
 *
 * **没有「结束」事件**：一行只记一个时刻，过渡何时结束由既有的 `navTransition` 汇总行给出。因此一次过渡的
 * 时刻线在 `begin` 之后 **10 s** 内都还认后续的 [mark]（SMB 慢来源上「首图就绪」可以晚到两三秒）；
 * 超过这个窗口的迟到打点会被**丢掉**（宁可少一行，也不把它算到另一次过渡头上）。**已知代价**：两次过渡
 * 挨得太近时，前一次屏上迟到的打点会算到后一次的 `id` 上——读日志时按 `entry=` 与 `book=` 对齐即可分辨。
 * **另一个已知代价**：开关**打开时**绘制块里每帧多一次 `isOn` 判断、每屏多两行拼接 + 一次 `Log.d`，
 * 会轻微影响帧时长——所以取 AC-9 的帧数据时不要把本打点与它一起开（应用内开关只有一个，取数时分开跑）。
 */
internal object NavTransitionTimeline {

    /** 时刻线的类别（`begin` 行的 `kind=`）：进阅读器（四条开书入口与冷启动落地都算它） */
    const val KIND_ENTER_READER: String = "enterReader"

    /** 时刻线的类别：退出阅读器（返回到浏览页） */
    const val KIND_EXIT_READER: String = "exitReader"

    /** 时刻线的类别：换书（阅读器 → 阅读器） */
    const val KIND_SWAP_READER: String = "swapReader"

    /** 时刻线的类别：层级导航（文件夹之间 / 抽屉入口 / 书柜进柜） */
    const val KIND_HIERARCHY: String = "hierarchy"

    /**
     * 行前缀（`adb logcat -s ComicViewerPerf | grep navTransitionDetail`）。
     * **刻意与既有的汇总行 `navTransition` 同头**：`grep navTransition` 一次能把明细与汇总都捞出来，
     * 只要汇总行就写 `grep 'navTransition '`（带空格）。
     */
    const val PREFIX: String = "navTransitionDetail"

    /**
     * [request] 的有效期（毫秒）：导航守卫为假（点了但这次不算数）时不会有 [begin] 来消费它，
     * 过期的请求不得冒充下一次过渡的 t0。
     */
    const val REQUEST_TTL_MILLIS: Long = 2_000

    /** 一次过渡之后还认多久的迟到打点（毫秒），见类 KDoc */
    const val LATE_WINDOW_MILLIS: Long = 10_000

    private val lock = Any()

    /** 过渡序号（从 1 起，process 内递增） */
    private var nextId = 0

    /** 最近一次过渡的 id 与它的 t0（开窗之后不清：迟到的 [mark] 还要按它算偏移） */
    private var lastId = 0
    private var startNanos = 0L
    private var openUntilNanos = 0L

    /** 已经发生、但还没有 [begin] 来认领的用户动作（[request] 写，[begin] 消费） */
    private var pendingKind: String? = null
    private var pendingAtNanos = 0L

    /** 本次过渡里已经打过的「只记一次」键（`compose:entryId` / `draw:entryId` 一类） */
    private val firedOnce = mutableSetOf<String>()

    /**
     * 记下「导航已经被调用」：下一次同类 [begin] 以它作 t0。四条开书入口的共用通道在 `navigate` 之前
     * 调它一次（`ui/ReaderPrelude.kt` 的 `awaitReaderPrelude`）；没有 [request] 的导航（返回、层级、抽屉）
     * 会在 [begin] 那一刻自己当 t0。
     */
    fun request(kind: String, nowNanos: Long = System.nanoTime()) {
        if (!PerfTiming.isOn) return
        synchronized(lock) {
            pendingKind = kind
            pendingAtNanos = nowNanos
        }
    }

    /**
     * 导航栈变化被观测到：开一次时刻线。t0 = **未过期且同类**的 [request] 时刻，否则就是现在
     * （不同类的年轻请求只作为 `staleRequest=` 留在行里：它属于另一次被放弃/被顶替的导航，不能当这次的 t0）。
     */
    fun begin(kind: String, nowNanos: Long = System.nanoTime(), detail: () -> String = { "" }) {
        if (!PerfTiming.isOn) return
        val line = synchronized(lock) {
            val pending = pendingKind
            val fresh = pending != null && pending == kind && nowNanos - pendingAtNanos <= REQUEST_TTL_MILLIS * 1_000_000L
            val t0 = if (fresh) pendingAtNanos else nowNanos
            val extra = if (pending == null || fresh) "" else " staleRequest=" + pending
            pendingKind = null
            nextId++
            lastId = nextId
            startNanos = t0
            openUntilNanos = t0 + LATE_WINDOW_MILLIS * 1_000_000L
            firedOnce.clear()
            timelineLine(nextId, "begin", (nowNanos - t0) / 1_000_000L, "kind=" + kind + " " + detail() + extra)
        }
        PerfTiming.emit(line)
    }

    /**
     * 记一个时刻。[onceKey] 非空时同一次过渡内只记一次（`graphicsLayer` 的绘制块每帧都会被求值）。
     * 没有开着的时刻线（还没 [begin]，或已超出 [LATE_WINDOW_MILLIS]）时**静默丢弃**。
     * [detail] 是 lambda：开关关着时**连字符串都不拼**（与 `PerfTiming.log` 同一口径）。
     */
    fun mark(
        phase: String,
        onceKey: String? = null,
        nowNanos: Long = System.nanoTime(),
        detail: () -> String = { "" },
    ) {
        if (!PerfTiming.isOn) return
        val line = synchronized(lock) {
            if (lastId == 0 || nowNanos > openUntilNanos) return
            if (onceKey != null && !firedOnce.add(onceKey)) return
            timelineLine(lastId, phase, (nowNanos - startNanos) / 1_000_000L, detail())
        }
        PerfTiming.emit(line)
    }

    /** 一行时刻（纯拼接，由 `NavTransitionTimelineTest` 钉住；**唯一**的行格式出处） */
    internal fun timelineLine(id: Int, phase: String, offsetMs: Long, detail: String): String = buildString {
        append(PREFIX)
        append(" id=").append(id)
        append(" phase=").append(phase)
        append(" t=").append(offsetMs).append("ms")
        if (detail.isNotEmpty()) append(' ').append(detail)
    }

    /**
     * **仅测试用**：把时刻线清回初始状态。本对象是单例（生产与用例共用同一个），上一轮用例留下的
     *「还开着的时刻线」（[LATE_WINDOW_MILLIS] 那个窗口）会吞掉下一轮的打点——不清就没法逐用例断言行数。
     */
    internal fun resetForTest() = synchronized(lock) {
        nextId = 0
        lastId = 0
        startNanos = 0L
        openUntilNanos = 0L
        pendingKind = null
        pendingAtNanos = 0L
        firedOnce.clear()
    }
}
