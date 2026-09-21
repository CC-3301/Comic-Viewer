package com.cc3301.comicviewer.ui

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.view.ScrollProbe

/**
 * 一次滚动活动的键（票 #109 r5）：**可见区变化** 或 **滚动偏移变化** 都算一次活动。
 *
 * 量测窗口靠「活动」开着（[ScrollProbe.markScrollActivity]），而 `snapshotFlow` 只在键变化时发一次：
 * 键里只有可见区时，慢拖期间可见区几乎不变 ⇒ 窗口在滚动中途被静止判据切开，真机上表现为一段连续滚动
 * 落成多行（`windowMs` 只有几百毫秒）且滚动期间的条目/封面重组落在窗口外计不到（`itemsComposed` 恒为 0）。
 * 滚动偏移每帧都在变，因此把 `firstVisibleItemScrollOffset` 一起进键。
 */
internal data class BrowseScrollActivity(val visible: List<Int>, val scrollOffset: Int)

/** 滚动活动键的唯一取法（两档滚动状态各取一次同样的两样值；`snapshotFlow` 靠它去重） */
internal fun browseScrollActivityKey(visible: List<Int>, scrollOffset: Int): BrowseScrollActivity =
    BrowseScrollActivity(visible = visible, scrollOffset = scrollOffset)

/**
 * 浏览页滚动量测的界面侧接线（票 #109 E3-A「先量再改」）。
 *
 * **默认关闭**：开关就是 [PerfTiming.isOn]（那份 `log.tag.ComicViewerPerf`），关着时这里**一个监听器都不注册**、
 * 一个计数都不写、不拼任何字符串（条目/封面组合计数与封面加载计时都在调用点先用开关挡一道）。
 * 开启后也只写 logcat：不改布局、不改点击、不改任何可见行为。
 *
 * 帧量测用平台的 `Window.OnFrameMetricsAvailableListener`（`adb shell dumpsys gfxinfo framestats` 同源的那份数据），
 * 每帧给出三个时长，正是把「布局/组合贵」与「合成/GPU 贵」分开所需的：
 * - `TOTAL_DURATION` → 一帧总耗时，掉帧判定与 p95/最大值的输入；
 * - `LAYOUT_MEASURE_DURATION` → measure/layout 段（重组后重建布局的成本，条目层级越复杂越大）；
 * - `DRAW_DURATION` → 绘制/同步段（位图上传与合成，封面图越大越多越贵）。
 *
 * 回调跑在主线程 Handler 上（帧只在这一个线程进聚合器），但**聚合器并不因此是单线程的**：封面加载计数来自
 * `Dispatchers.IO` 工作线程（多格可并发，见 `ui/CoverThumb`），因此 `ScrollProbe` 自己用一把锁护住全部字段。
 * 回调本身只做几次累加与一次（可能落行的）字符串拼接，量级在微秒，不改测量对象。
 *
 * 进聚合器的时刻取**帧自己的时间戳**（`INTENDED_VSYNC_TIMESTAMP`，与 `System.nanoTime()` 同一时钟），
 * **不是回调被投递的时刻**：主线程忙时 FrameMetrics 回调会被突发投递，用投递时刻算窗口会把 `windowMs`
 * 压小、把 `jankPerSec`/`jankPct` 的分母弄成不可信（#109 r4 真机：推出 200+ fps，平台侧同期约 105 fps）。
 *
 * 滚动活动（[ScrollProbe.markScrollActivity]）由 `BrowserScreen` 在**可见区变化或滚动偏移变化**时登记，
 * 帧回调据它开关窗口——静止帧与空闲期事件都不进统计；掉帧/秒因此是「滚动期间」的口径，
 * **窗口边界与末尾静止尾巴**见 [ScrollProbe] 的类 KDoc。
 * 离开浏览页时在本件的 `onDispose` 里主动收口（落行 + 清零），因此换层/离开浏览页不会把两段并进同一行。
 * 量测协议（怎么开 tag、抓哪些行、怎么算指标）见工单 #109。
 */
internal object BrowseScroll {

    /** 进程内唯一一份聚合：帧量测、条目/封面组合计数、封面加载都写它 */
    val probe = ScrollProbe()
}

/**
 * 注册帧量测（[BrowseScroll]）。仅在开关打开时注册，且跟随本页组合的存活期注销——
 * 关着走 `DisposableEffect` 之外的分支，什么都不做。
 *
 * 离开浏览页时除了注销监听器，还要让 `ScrollProbe` **收口当前滚动段**（[closeScrollSession]）：
 * 自动落行要等「静止 ≥ 500ms 的那一帧」，而滚动后不到 500ms 就换层不会有那一帧——不收口窗口就跨屏存活，
 * 下一屏的条目/封面事件会并进同一行。
 */
@Composable
internal fun BrowseScrollFrameMetrics() {
    if (!PerfTiming.isOn) return
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window) {
        val target = window
        val listener = target?.let {
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                val line = BrowseScroll.probe.onFrame(
                    totalNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    layoutNanos = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    drawNanos = metrics.getMetric(FrameMetrics.DRAW_DURATION),
                    // 帧自己的时间戳（同一时钟可与 System.nanoTime() 相减），不是回调投递时刻
                    frameNanos = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                )
                if (line != null) PerfTiming.log { line }
            }
        }
        if (target != null && listener != null) {
            target.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }
        onDispose {
            if (target != null && listener != null) target.removeOnFrameMetricsAvailableListener(listener)
            closeScrollSession()
        }
    }
}

/** 收口当前滚动段并落行（离开浏览页时调用）；没有开着的窗口时不产行（[ScrollProbe.onScrollSessionEnd]） */
private fun closeScrollSession() {
    val line = BrowseScroll.probe.onScrollSessionEnd()
    if (line != null) PerfTiming.log { line }
}
