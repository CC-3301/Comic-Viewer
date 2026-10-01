package com.cc3301.comicviewer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * `ui` 包的 Robolectric 组合测量脚手架：把「起 [ComponentActivity] → 挂 [ComposeView]
 * → measure → layout → idle」合成一份。起初只把三处 + PreviewStrip 两处搬了过来；
 * 后来把 `ui` 包**其余 10 处**手写副本（`EntryNameTextTest` / `BrowseRowWidthTest` / `BrowseItemCountTest` /
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
