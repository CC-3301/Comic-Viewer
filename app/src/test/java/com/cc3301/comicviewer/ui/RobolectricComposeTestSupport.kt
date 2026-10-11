package com.cc3301.comicviewer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * `ui` 包的 Robolectric 组合测量脚手架：把「起 [ComponentActivity] → 挂 [ComposeView]
 * → measure → layout → idle」合成一份。起初只把三处 + PreviewStrip 两处搬了过来；
 * 后来把 `ui` 包**其余 9 处**手写副本（`EntryNameTextTest` / `BrowseItemCountTest` /
 * `BrowseScrollRestoreTest` / `CrossBookBarTest` / `GridCellNameVisibleTest` / `GridProgressScrimTest` /
 * `ReaderMenuFooterTest` ×2 / `ReaderMenuTitleLineCountTest` / `SeekSliderTapTest`）一并收进来——
 * 现在 `ui` 测试源集里除本文件外**没有**第二处 `measure`+`layout`+`idle` 序列。
 * 换测量方式（改 idle 口径、换测量驱动）从此只改这一处，不再各处漏改。
 *
 * 放在测试源集、`ui` 包内：只有 ui 的 Robolectric 测试用得到它，生产侧不需要这个概念。
 *
 * 另一组口：**像素口径**的「渲染一步」[renderStep] 与「推到读数稳定」[renderUntilStable]——
 * 逐帧取位图看绘制结果的那类用例（如 `PullRefreshIndicatorProgressTest`）走这两个，不再在用例里另写一份
 * `invalidate + requestLayout + idleFor + draw` 与有界等待。
 */

/** 起一个 [ComponentActivity] 并把 [content] 组合进它的 [ComposeView]（挂载顺序与原三处脚手架一致） */
internal fun composeViewInActivity(content: @Composable () -> Unit): ComposeView {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val view = ComposeView(activity)
    activity.setContentView(view)
    view.setContent(content)
    return view
}

/**
 * 跑一轮「测量 → 布局 → idle」：[widthPx] 是 EXACTLY 宽度；[heightPx] 为空 = 高度 UNSPECIFIED(0)
 * （内容自撑高，原来的三处取法）。Compose 的放置请求在后一轮布局里生效，需要多轮时重复调用。
 */
internal fun ComposeView.layoutOnce(widthPx: Int, heightPx: Int? = null) {
    val heightSpec = if (heightPx == null) {
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    } else {
        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
    }
    measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), heightSpec)
    layout(0, 0, measuredWidth, measuredHeight)
    shadowOf(Looper.getMainLooper()).idle()
    healComposeDispatchScheduling()
}

/**
 * 跑到 [condition] 成立为止的**有界等待**：每轮 [layoutOnce]，最多 [timeoutMillis] 毫秒，返回**是否在上限内等到**
 * （对齐本仓既有的等待口：`CountingSmbTransport.awaitInFlight` 也是「返回是否等到；不抛异常，由用例断言」，
 * 用例侧 `BrowsingSourceSessionTest.awaitCloseCount` 则是「轮询到上限后用值断言带消息地失败」）。
 *
 * 为什么要有它：一轮「测量 → 布局 → idle」**不保证** Compose 的时机已经走到位——
 * `DisposableEffect.onDispose` 这类**回调**里写下的值落在 **idle 段**：`measure` + `layout` 跑完它还没落地，
 * 同一轮的 `idle()` 才轮到它（探针：`raw measure+layout` 后仍是初值 `-1`，再 `idle()` 才变终值）。
 * 机器一忙（全量跑上百个测试类）那一轮就可能没轮到 ⇒ 断言跑在回调之前。所以断言前要**轮询到条件成立**，
 * 而不是假设一轮就够。
 *
 * **上限按真实时间、不按轮数**：上限取轮数时，「等到」要多久取决于**每轮多贵** ——
 * 一轮「测量 → 布局 → idle」的耗时跨两个数量级（同 [CoverShownProbeTest.composeFrames] 的口径：4ms ~ 280ms），
 * 机器一忙时轮数先耗光、条件还没到。
 *
 * 每轮**显式推进一帧假时钟**（`ShadowLooper.idleFor`，一步 = 一个 60Hz 帧距）再回看一次条件：组合内的
 * `LaunchedEffect` / 帧回调都排在假时钟上，只 `idle()`（不推进时钟）就轮不到它们。
 * 每轮之间 `Thread.sleep` 让出**真实**时间：牵到真线程的等待靠的是真实时间，主线程空转会把那些线程饿死
 *（同 [CoverShownProbeTest.composeFrames] 里那句 `Thread.sleep(5)`）。
 *
 * **调用点必须消费返回值**：`false` = 到上限仍未成立，要写进断言消息（否则「等待超时」与「值不对」
 * 在日志里同形）；等待器自身不抛异常、也不写失败标记，断言照旧以**真实的终值**失败（`-1` / `600` 语义不动）。
 */
internal fun ComposeView.layoutUntil(
    widthPx: Int,
    heightPx: Int? = null,
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (true) {
        layoutOnce(widthPx, heightPx)
        if (condition()) return true
        // 改状态后的重组靠快照的**应用通知**去失效组合，而那一跳由 Compose 在后台线程（`Dispatchers.Default`）上投递：
        // 整包跑时它偶发丢 ⇒ 只有「改状态后等落地」的等待会空转到上限（测量型的等待第 1 轮就落地）。这里自己泵一次。
        Snapshot.sendApplyNotifications()
        if (condition()) return true
        shadowOf(Looper.getMainLooper()).idleFor(FRAME_STEP_MILLIS, TimeUnit.MILLISECONDS)
        if (condition()) return true
        if (System.nanoTime() >= deadlineNanos) return false
        Thread.sleep(YIELD_MILLIS)
    }
}

/** [layoutUntil] 每轮推进的假时钟步长：一个 60Hz 帧距 */
private const val FRAME_STEP_MILLIS = 16L

/** [layoutUntil] 每轮让出的真实时间：与 [CoverShownProbeTest.composeFrames] 的 `Thread.sleep(5)` 同一口径 */
private const val YIELD_MILLIS = 5L

/**
 * **像素口径**推进一步并把整屏真渲染成位图：[invalidate] + [requestLayout] + 一帧
 * （`idleFor(16ms)`）+ `draw(`[Canvas]`)。调用方自己的类要开 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
 * （Robolectric 的原生渲染），否则取到的像素是空的。
 *
 * 为什么只推 **16ms**：要逐帧看清的东西（`animateFloatAsState` 的 snap / 回弹）在推进过头之后只剩终值。
 * 需要「推到读数稳定」的用 [renderUntilStable]；只需要一帧落位的循环调本函数。
 */
internal fun ComposeView.renderStep(): Bitmap {
    invalidate()
    requestLayout()
    shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS)
    healComposeDispatchScheduling()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    return bitmap
}

/**
 * 推到读数**稳定**再取（有界 [maxRounds] 步）：[renderStep] 一步不保证 Compose 的时机已走到位
 * （与 [layoutUntil] 同一个理由），直接断言会量到上一段的旧值。
 *
 * 稳定判据 = 「连着两步 [read] 一样」（初值取 `Int.MIN_VALUE`，所以第一轮永远不会被当成稳定）；
 * 一直不稳定就返回最后一轮的读数——**不在这里抛**，断言照旧以真实读数带消息地失败（同 [layoutUntil]）。
 */
internal fun ComposeView.renderUntilStable(maxRounds: Int = 12, read: (Bitmap) -> Int): Int {
    var previous = Int.MIN_VALUE
    repeat(maxRounds) {
        val now = read(renderStep())
        if (now == previous) return now
        previous = now
    }
    return previous
}

/**
 * 「推进基建自愈」：修复 Robolectric 下 `AndroidUiDispatcher`（主线程单例）的派发楔死。
 *
 * 背景：该调度器用两个粘性标志（`scheduledTrampolineDispatch` / `scheduledFrameDispatch`）
 * 加双路投递派发组合与效果的续体——handler 消息 + Choreographer 帧回调（PAUSED 模式下 vsync
 * 靠假时钟监听器内联触发）。整包跑 + 重负载下偶发两条投递路都丢、标志卡在 true ⇒ 之后
 * `dispatch()` 只入队不投递、`withFrameNanos` 等待者永不触发 ⇒ 所有「改状态后等落地」的等待
 * 空转到上限（测量型等待走 `composeInitial` 同步组合与 measure 直读，免疫）。
 * 红时读数（dump 实证）：`toRunTrampolined` 单调增长永不排空、主 looper 队列里没有它的消息、
 * Choreographer 队列里陈旧条目 due 远落后于假时钟、两标志均 true。
 *
 * 规则（判据，两者任一即视为楔死，下轮修复）：
 * - trampoline：`toRunTrampolined` 非空且主 looper 队列里没有 `dispatchCallback` 的消息
 *  （健康态下入队与投递同锁原子配对，队列非空必有消息在途）；
 * - frame：`toRunOnFrame` 非空且 ANIMATION 队列里没有该回调条目，或条目 due 已过期超过两个帧距
 *  （健康态下 vsync 在一个帧距内开帧并清条目）。
 *
 * 修复动作：两标志清零 → 清陈旧帧回调条目 → `handler.post(dispatchCallback)` 重投
 * trampoline（下一轮 idle 排空全部积压，包括楔死期间堆的续体）→ `toRunOnFrame` 非空时置回
 * frame 标志并直接以当前时刻调 `dispatchCallback.doFrame` 替 vsync 开一帧（等待者当场恢复）。
 *
 * 健康轮不开销：只反射读几个字段；只在判据命中时才写（负载整包跑约 1/8 概率楔死）。
 * 只修测试脚手架的推进，不碰产品代码与生产语义。
 */
@Suppress("PrivateApi")
internal fun healComposeDispatchScheduling() {
    runCatching {
        // 主线程上 CurrentThread 就是 Main，同一实例（见 AndroidUiDispatcher.CurrentThread 的实现）
        var cur: Any? = androidx.compose.ui.platform.AndroidUiDispatcher.CurrentThread
        var dispatcher: Any? = null
        repeat(8) {
            if (cur == null || dispatcher != null) return@repeat
            val fs = cur.javaClass.declaredFields.map { it.isAccessible = true; it to runCatching { it.get(cur) }.getOrNull() }
            val elem = fs.firstOrNull { it.first.name == "element" }?.second
            if (elem != null && elem.javaClass.name.endsWith("AndroidUiDispatcher")) dispatcher = elem
            cur = fs.firstOrNull { it.first.name == "left" }?.second
        }
        val d = dispatcher ?: return
        fun field(name: String): Pair<java.lang.reflect.Field, Any?> {
            val f = d.javaClass.getDeclaredField(name)
            f.isAccessible = true
            return f to f.get(d)
        }
        val (trampFlagField, _) = field("scheduledTrampolineDispatch")
        val (frameFlagField, _) = field("scheduledFrameDispatch")
        val (_, trampQueue) = field("toRunTrampolined")
        val (_, frameQueue) = field("toRunOnFrame")
        val (_, callbackAny) = field("dispatchCallback")
        val (_, choreographerAny) = field("choreographer")
        val (_, handlerAny) = field("handler")
        trampQueue as ArrayDeque<*>
        frameQueue as MutableList<*>
        val frameCallback = callbackAny as android.view.Choreographer.FrameCallback
        val runnableCallback = callbackAny as Runnable
        val choreographer = choreographerAny as android.view.Choreographer
        val handler = handlerAny as android.os.Handler
        if (trampQueue.isEmpty() && frameQueue.isEmpty()) return

        val nowMs = android.os.SystemClock.uptimeMillis()
        val frameDelayMs = org.robolectric.shadows.ShadowChoreographer.getFrameDelay().toMillis()

        // 判据 ①：trampoline 队列非空但 looper 里没有它的消息（健康态下入队与投递原子配对）
        val trampWedged = trampQueue.isNotEmpty() && !looperQueueHasCallback(handler, runnableCallback)
        // 判据 ②：有帧等待者，但 ANIMATION 队列里没有条目，或条目已过期超过两个帧距
        val entry = choreographerAnimEntry(choreographer, frameCallback)
        val frameWedged = frameQueue.isNotEmpty() && (entry == null || entry.second + 2 * frameDelayMs <= nowMs)
        if (!trampWedged && !frameWedged) return

        val lock = field("lock").second!!
        synchronized(lock) {
            trampFlagField.set(d, false)
            frameFlagField.set(d, false)
        }
        choreographer.removeFrameCallback(frameCallback)
        handler.post(runnableCallback)
        if (frameQueue.isNotEmpty()) {
            synchronized(lock) { frameFlagField.set(d, true) }
            // 直接替 vsync 开一帧：帧时间戳用当前假时钟，等待者当场恢复
            frameCallback.doFrame(nowMs * 1_000_000L)
        }
    }
}

/** 主 looper 队列里是否已有以 [callback] 为 callback 的在途消息（只读，不动队列） */
private fun looperQueueHasCallback(handler: android.os.Handler, callback: Runnable): Boolean {
    runCatching {
        val mq = android.os.Looper.myQueue()
        var cur: Any? = mq.javaClass.getDeclaredField("mMessages").apply { isAccessible = true }.get(mq)
        repeat(64) {
            if (cur == null) return@repeat
            val msgCallback = cur.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(cur)
            if (msgCallback === callback) return true
            cur = cur.javaClass.getDeclaredField("next").apply { isAccessible = true }.get(cur)
        }
    }
    return false
}

/** Choreographer ANIMATION 队列里 [callback] 的条目：返回 (是否存在, dueUptimeMs)；不存在返回 null */
private fun choreographerAnimEntry(choreographer: android.view.Choreographer, callback: android.view.Choreographer.FrameCallback): Pair<Boolean, Long>? {
    runCatching {
        val queues = choreographer.javaClass.getDeclaredField("mCallbackQueues").apply { isAccessible = true }.get(choreographer) as Array<*>
        val animationQueue = queues[1]!!
        var cur: Any? = animationQueue.javaClass.getDeclaredField("mHead").apply { isAccessible = true }.get(animationQueue)
        repeat(64) {
            if (cur == null) return@repeat
            val action = cur.javaClass.getDeclaredField("action").apply { isAccessible = true }.get(cur)
            if (action === callback) {
                val due = cur.javaClass.getDeclaredField("dueTime").apply { isAccessible = true }.getLong(cur)
                return true to due
            }
            cur = cur.javaClass.getDeclaredField("next").apply { isAccessible = true }.get(cur)
        }
    }
    return null
}
