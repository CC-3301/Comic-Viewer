package com.cc3301.comicviewer.ui.nav

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.ui.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 导航过渡期帧量测的界面侧接线。挂在**导航壳**（`AppNav`）上，因此所有导航过渡
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
 * 为什么收口是定时而不是「等某个回调」：导航过渡没有可观察的结束事件（`NavHost` 不暴露），而时长是已知的
 * （[windowMillis]，由调用点用 [navTransitionWindowMillis] 算出来——与每屏的动画同一个纯函数，两处不会漂），
 * 按它调度一次收口即可。**收口带着开窗时拿到的代次号回来**（[NavTransitionProbe.beginTransition] 的返回值）：
 * 这一拍期间用户可能已经发起下一次导航（点开一本书马上返回，比这一拍的收口还早），那时窗口已经属于新的一拍
 * ——代次对不上就不落行：既不打假数据，也不会关掉新那一拍。
 *
 * 默认关（[PerfTiming.isOn] 为假）时这里是空调用，不建协程、不写计数。
 */
internal fun beginNavTransitionProbe(probe: NavTransitionProbe, scope: CoroutineScope, windowMillis: Int) {
    if (!PerfTiming.isOn) return
    val generation = probe.beginTransition()
    scope.launch {
        delay(windowMillis.toLong() + PROBE_TAIL_MILLIS)
        probe.endTransition(generation)?.let { line -> PerfTiming.log { line } }
    }
}

/**
 * **一次导航 = 一拍**（闩）：过渡 lambda 在同一次导航里会被求值多次（`AnimatedContent` 对每个内容各求一次），
 * 只看第一次——重复求值会再开一拍：`transitions` 序号与时刻线的 `id` 随之膨胀，且先拍的收口定时器
 * 会提前收掉后一拍的窗口。键 = 前后两条栈项的 id（栈项 id 是不含分隔符的 uuid）。
 */
internal class NavTransitionOnce {
    private var key: String? = null

    /** 这一条栈项对的第一次求值返回 true（开一拍）；同一条栈项对再来返 false */
    fun openIfFirst(from: String, to: String): Boolean {
        val candidate = from + "→" + to
        if (candidate == key) return false
        key = candidate
        return true
    }
}

/** 过渡收口的尾巴（毫秒）：过渡时长之外多收一点，覆盖末帧与重组收尾 */
private const val PROBE_TAIL_MILLIS: Long = 100
