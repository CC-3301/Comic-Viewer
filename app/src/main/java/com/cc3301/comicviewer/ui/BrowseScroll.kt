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
 * 浏览页滚动量测的界面侧接线（票 #109 E3-A「先量再改」）。
 *
 * **默认关闭**：开关就是 [PerfTiming] 那份 `log.tag.ComicViewerPerf`，关着时这里**一个监听器都不注册**、
 * 一个计数都不写、不拼任何字符串（条目/封面组合计数与封面加载计时都在调用点先用 [enabled] 挡一道）。
 * 开启后也只写 logcat：不改布局、不改点击、不改任何可见行为。
 *
 * 帧量测用平台的 `Window.OnFrameMetricsAvailableListener`（`adb shell dumpsys gfxinfo framestats` 同源的那份数据），
 * 每帧给出三个时长，正是把「布局/组合贵」与「合成/GPU 贵」分开所需的：
 * - `TOTAL_DURATION` → 一帧总耗时，掉帧判定与 p95/最大值的输入；
 * - `LAYOUT_MEASURE_DURATION` → measure/layout 段（重组后重建布局的成本，条目层级越复杂越大）；
 * - `DRAW_DURATION` → 绘制/同步段（位图上传与合成，封面图越大越多越贵）。
 *
 * 回调跑在主线程 Handler 上：与条目组合计数、封面加载计数同线程，聚合器因此不需要同步；
 * 回调本身只做几次累加与一次（可能落行的）字符串拼接，量级在微秒，不改测量对象。
 *
 * 滚动活动（[ScrollProbe.markScrollActivity]）由 `BrowserScreen` 在**可见区变化**时登记，帧回调据它开关窗口——
 * 静止期的帧不进统计，掉帧/秒因此是「滚动期间」的口径。指标口径、开关与抓取示例见 [ScrollProbe]，
 * 完整比对协议在票 #109 的证据文档 `.implement-pro/109/evidence-impl.md`（本地编排产物，不入库）。
 */
internal object BrowseScroll {

    /** 量测开关（与 [PerfTiming] 同一份 tag）：关着时下面所有探针都不落任何痕迹 */
    val enabled: Boolean get() = PerfTiming.isOn

    /** 进程内唯一一份聚合：帧量测、条目/封面组合计数、封面加载都写它 */
    val probe = ScrollProbe()
}

/**
 * 注册帧量测（[BrowseScroll]）。仅在开关打开时注册，且跟随本页组合的存活期注销——
 * 关着走 `DisposableEffect` 之外的分支，什么都不做。
 */
@Composable
internal fun BrowseScrollFrameMetrics() {
    if (!BrowseScroll.enabled) return
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window) {
        val target: Window = window ?: return@DisposableEffect onDispose { }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val nanos = System.nanoTime()
            val line = BrowseScroll.probe.onFrame(
                totalNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                layoutNanos = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                drawNanos = metrics.getMetric(FrameMetrics.DRAW_DURATION),
                nowNanos = nanos,
            )
            if (line != null) PerfTiming.log { line }
        }
        target.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        onDispose { target.removeOnFrameMetricsAvailableListener(listener) }
    }
}
