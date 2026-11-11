package com.cc3301.comicviewer.core.input

/** 窗口重新获得焦点后屏蔽点击的时长（毫秒） */
const val FOREGROUND_CLICK_BLOCK_MILLIS = 300L

/**
 * 回前台点击屏蔽的判据（纯逻辑，由 [ForegroundClickGateTest] 锁定；spec §回前台点击屏蔽）。
 *
 * 判据进 `core/input/`、`ui/` 只做事件翻译（与 `core/input/` 其余手势同一分工）。本模块的口径：
 *
 * - 起点是**窗口重新获得焦点**那一刻（[onWindowFocused]），不是「是否从后台回来」：
 *   点通知、来电、弹窗关闭后回前台同样生效。
 * - 起点起 [FOREGROUND_CLICK_BLOCK_MILLIS] 内的**点击**一律丢弃（翻页、呼出菜单、双击放大都不生效）；到点后照旧。
 * - 只判点击：滚动、滚轮、鼠标移动不经过这里。
 * - 还没收到过焦点事件的窗口（窗口一直没失焦）一律放行：没有重新获得焦点，就没有这段屏蔽。
 *
 * @param nowMillis 时钟（注入以便单测）
 */
internal class ForegroundClickGate(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 最近一次重新获得焦点的时刻；null = 还没收到过 */
    private var focusedAt: Long? = null

    /** 窗口重新获得焦点：屏蔽从这里起算 */
    fun onWindowFocused() {
        focusedAt = nowMillis()
    }

    /** 这次点击是否生效：焦点后 [FOREGROUND_CLICK_BLOCK_MILLIS] 内不生效 */
    fun allowsClick(): Boolean {
        val start = focusedAt ?: return true
        return nowMillis() - start >= FOREGROUND_CLICK_BLOCK_MILLIS
    }
}
