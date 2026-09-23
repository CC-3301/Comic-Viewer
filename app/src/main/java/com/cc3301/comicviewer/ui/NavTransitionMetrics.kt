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
import com.cc3301.comicviewer.core.view.NavTransitionProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 导航过渡期帧量测的界面侧接线（票 #111 AC-9）。挂在**导航壳**（`AppNav`）上，因此所有导航过渡
 * （进出阅读器 + 层级导航 + 换书）都进统计——`BrowseScrollFrameMetrics` 那条只覆盖浏览页滚动窗口。
 *
 * **默认关闭**：开关就是 [PerfTiming.isOn]（`log.tag.ComicViewerPerf`），关着时不注册任何监听器、不计一个数；
 * 开启后也只写 logcat，不改布局、不改点击、不改任何可见行为。取数：
 *
 * ```
 * adb shell setprop log.tag.ComicViewerPerf DEBUG   # 设完重启 APP（isLoggable 按进程缓存）
 * adb logcat -s ComicViewerPerf | grep navTransition
 * ```
 *
 * 一行 = 一次导航过渡（首尾由 [beginNavTransitionProbe] 的延迟收口界定）；判据、字段与
 * **AC-9「连续 10 次」的读数口径**（按行里的 `transitions` 序号定位，不要硬编码读第几行）见
 * [NavTransitionProbe]。窗口时长取过渡时长 + 一点尾巴（重组成与首帧的收尾）。
 */
@Composable
internal fun NavTransitionFrameMetrics(probe: NavTransitionProbe) {
    if (!PerfTiming.isOn) return
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window) {
        val target = window
        val listener = target?.let {
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                probe.onFrame(
                    totalNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    // 帧自己的时间戳（同一时钟可与 System.nanoTime() 相减），不是回调投递时刻
                    frameNanos = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                )
            }
        }
        if (target != null && listener != null) {
            target.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }
        onDispose {
            if (target != null && listener != null) target.removeOnFrameMetricsAvailableListener(listener)
        }
    }
}

/**
 * 一次导航过渡开始（在 `NavHost` 的四支过渡 lambda 里调用）：开窗，并在过渡时长 + [PROBE_TAIL_MILLIS] 后
 * **主动收口**落一行明细。
 *
 * 为什么收口是定时而不是「等某个回调」：导航过渡没有可观察的结束事件（`NavHost` 不暴露），而时长是已知常量
 * （[NavTransitions.DURATION_MILLIS]）——按它调度一次收口即可，且[NavTransitionProbe] 的窗口在没有开窗时
 * 不产行，重复触发（重组/连续快速操作）最多多收口一次空窗，不产生假数据。
 *
 * 默认关（[PerfTiming.isOn] 为假）时这里是空调用，不建协程、不写计数。
 */
internal fun beginNavTransitionProbe(probe: NavTransitionProbe, scope: CoroutineScope) {
    if (!PerfTiming.isOn) return
    probe.beginTransition()
    scope.launch {
        delay(NavTransitions.DURATION_MILLIS.toLong() + PROBE_TAIL_MILLIS)
        probe.endTransition()?.let { line -> PerfTiming.log { line } }
    }
}

/** 过渡收口的尾巴（毫秒）：过渡时长之外多收一点，覆盖末帧与重组收尾 */
private const val PROBE_TAIL_MILLIS: Long = 100
