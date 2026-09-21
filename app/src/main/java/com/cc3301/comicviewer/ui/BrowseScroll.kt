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
 * 滚动活动（[ScrollProbe.markScrollActivity]）由 `BrowserScreen` 在**可见区变化**时登记，帧回调据它开关窗口——
 * 静止帧与空闲期事件都不进统计，掉帧/秒因此是「滚动期间」的口径（窗口边界见 [ScrollProbe] 的类 KDoc），
 * 量测协议（怎么开 tag、抓哪些行、怎么算指标）见工单 #109。
 */
internal object BrowseScroll {

    /** 进程内唯一一份聚合：帧量测、条目/封面组合计数、封面加载都写它 */
    val probe = ScrollProbe()
}

/**
 * 注册帧量测（[BrowseScroll]）。仅在开关打开时注册，且跟随本页组合的存活期注销——
 * 关着走 `DisposableEffect` 之外的分支，什么都不做。
 */
@Composable
internal fun BrowseScrollFrameMetrics() {
    if (!PerfTiming.isOn) return
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
