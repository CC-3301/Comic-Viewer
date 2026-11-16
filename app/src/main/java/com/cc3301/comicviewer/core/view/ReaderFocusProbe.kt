package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.source.PerfTiming

/**
 * 回前台位置量测的两类诊断行（量测协议见 `docs/SPEC.md` 的 Testing Decisions「回前台位置量测」）。
 *
 * 开关（[PerfTiming.isOn]）关着时只记两个时间戳与一份锚点，不拼字符串、不碰缓冲：
 *
 * ```
 * readerFocus phase=lost|gained <锚点>
 * readerAnchor phase=afterFocus ms=<距焦点恢复的毫秒> <锚点>
 * ```
 *
 * 锚点写作 `page=<顶部可见页> percent=<该页内已滚过的比例>`，与阅读页既有记账同源
 * （[ReaderScrollAnchor]，换算唯一出处是 [readerScrollAnchor]，本类不另立口径）：
 * `page` 从 0 起，`percent` 是 0..1 的页内比例。
 *
 * `ms` 取 [clock]（单调时钟）在「窗口重新获得焦点」那一刻与「这次锚点变化」那一刻的差，
 * 只报 0..[AFTER_FOCUS_WINDOW_MS] 之内的变化——再往后的变化与回到前台那一下无关。
 *
 * 用途：把「切后台再回前台画面往上跳一下」拆成两段。失焦行与获得焦点行之间的位置差是后台期间被移动；
 * 获得焦点那一行之后、窗口内的 `phase=afterFocus` 行是回到前台之后被移动。
 */
internal class ReaderFocusProbe(
    /** 单调时钟（纳秒）；用例注入假时钟 */
    private val clock: () -> Long = System::nanoTime,
) {
    /** 重新获得焦点的时刻；失焦后归 null（那段时间的变化不算「回前台之后」） */
    private var focusedAtNanos: Long? = null

    /** 上一次记下的锚点：锚点没变不产行 */
    private var lastAnchor: ReaderScrollAnchor? = null

    /**
     * 窗口焦点变化各产一行（[focused] = false 失焦、true 重新获得焦点）。
     *
     * 还没有锚点（列表尚未布局出可见项）时不产行：这类行要对照的正是位置，写不出位置就没有对照。
     */
    fun onWindowFocused(focused: Boolean, anchor: ReaderScrollAnchor?) {
        lastAnchor = anchor
        if (!focused) {
            focusedAtNanos = null
            if (anchor != null) PerfTiming.log { focusLine(phase = "lost", anchor) }
            return
        }
        focusedAtNanos = clock()
        if (anchor != null) PerfTiming.log { focusLine(phase = "gained", anchor) }
    }

    /** 列表锚点变化一次调一次：只有落在重新获得焦点后的 [AFTER_FOCUS_WINDOW_MS] 内且锚点真的变了才产行 */
    fun onAnchorChanged(anchor: ReaderScrollAnchor) {
        if (anchor == lastAnchor) return
        lastAnchor = anchor
        val gainedAt = focusedAtNanos ?: return
        val sinceMs = (clock() - gainedAt) / 1_000_000
        if (sinceMs > AFTER_FOCUS_WINDOW_MS) return
        PerfTiming.log { "readerAnchor phase=afterFocus ms=" + sinceMs + " " + anchorFields(anchor) }
    }

    /** [readerFocus] 行的唯一拼法 */
    private fun focusLine(phase: String, anchor: ReaderScrollAnchor): String =
        "readerFocus phase=" + phase + " " + anchorFields(anchor)

    /** 锚点字段的唯一拼法（两类行共用） */
    private fun anchorFields(anchor: ReaderScrollAnchor): String =
        "page=" + anchor.page + " percent=" + anchor.percent
}

/** 记录焦点恢复后锚点变化的窗口：再往后的变化不是「回到前台那一下」造成的 */
private const val AFTER_FOCUS_WINDOW_MS: Long = 2_000
