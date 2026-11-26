package com.cc3301.comicviewer.core.input

/** 鼠标停止移动多久后隐藏指针（毫秒） */
const val POINTER_HIDE_DELAY_MILLIS = 3_000L

/**
 * 界面把指针事件翻译成的活动（core 不认识 Compose 的指针类型）。
 * [TOUCH] 立即隐藏；[HOVER_MOVE] 只在可见时重置隐藏计时；[DRAG_MOVE] 与 [WHEEL] 既不恢复也不重置计时。
 * 改显隐的其余入口都在 [PointerAutoHide] 上：[PointerAutoHide.onWakeClick]、[PointerAutoHide.onDoubleTapZoom]、
 * [PointerAutoHide.setPanelPresent]、[PointerAutoHide.setWindowFocused]。
 */
internal enum class PointerActivity {
    /** 鼠标悬停移动（没按键） */
    HOVER_MOVE,

    /** 按住左键拖动滚动 */
    DRAG_MOVE,

    /** 滚轮滚动 */
    WHEEL,

    /** 触摸 / 手写笔操作 */
    TOUCH,
}

/** 带按钮的面板：在场期间指针不隐藏，触摸它们也不隐藏 */
internal enum class PointerPanel {
    /** 阅读菜单 */
    MENU,

    /** 跨书确认条 */
    CROSS_BOOK,
}

/**
 * 指针自动隐藏在界面侧的全部状态：显隐 + 距离隐藏还剩的毫秒数。
 * 两者由 [PointerAutoHide.state] 在同一时刻读出，界面因此只有一个状态出处。
 */
internal data class PointerAutoHideState(val hidden: Boolean, val hideDelayMillis: Long)

/**
 * 阅读器鼠标指针自动隐藏的判据（纯逻辑，由 [PointerAutoHideTest] 锁定）。
 *
 * 判据进 `core/input/`、`ui/` 只做事件翻译（与 `core/input/` 其余手势同一分工）。本模块的口径
 * （spec §指针自动隐藏）：
 *
 * - **隐藏**：鼠标停止悬停移动满 [POINTER_HIDE_DELAY_MILLIS]，计时从最后一次悬停移动起算（[advance] 收敛到当前时刻）。
 * - **隐藏期间移动不恢复**：隐藏后 [PointerActivity.HOVER_MOVE] 既不恢复指针，也不重置计时。
 * - **点击唤醒**：隐藏时的左键或右键按下调用 [onWakeClick]——恢复指针、重新开始计时，返回 true 表示这一整段被消费
 *   （界面据此不执行任何页面行为）；可见时返回 false，点击照常。可见时的点击同样不重置计时。
 * - **立即恢复**：面板出现 [setPanelPresent]、双击放大 [onDoubleTapZoom]。恢复后重新开始计时；面板全部退场后同样重新计时。
 * - **面板在场时不隐藏**：阅读菜单与跨书条都要用鼠标点，指针在这期间藏起来就点不着了；
 *   这期间落在它们上的触摸也不隐藏。
 * - **窗口失焦按可见处理**：失焦立即恢复指针并重新计时，失焦期间到点也不隐藏；回焦后从回焦时刻重新计时并保持可见。
 * - **立即隐藏**：没有面板在场时的触摸（[PointerActivity.TOUCH]），即触摸正文。
 * - **不恢复也不重置计时**：左键拖动（[PointerActivity.DRAG_MOVE]）与滚轮（[PointerActivity.WHEEL]）。
 *
 * @param nowMillis 时钟（注入以便单测）
 */
internal class PointerAutoHide(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 指针当前是否隐藏（两态：可见 = 系统默认指针，隐藏 = 透明指针） */
    var hidden = false
        private set

    /** 在场的面板：非空时指针不隐藏（菜单与跨书条同一口径） */
    private val panelsPresent = mutableSetOf<PointerPanel>()

    /** 最近一次悬停移动的时刻：可见时是隐藏计时的起点 */
    private var lastMoveMillis = nowMillis()

    /** 窗口是否在前台：失焦按可见处理（失焦期间不隐藏） */
    private var windowFocused = true

    /**
     * 喂一次指针活动。可见时只有 [PointerActivity.HOVER_MOVE] 重置计时；隐藏期间它也不恢复、不重置。
     */
    fun onActivity(activity: PointerActivity) {
        when (activity) {
            // 隐藏期间移动不恢复，也不重置计时
            PointerActivity.HOVER_MOVE -> if (!hidden) lastMoveMillis = nowMillis()
            // 菜单开着时的触摸落在菜单上：点菜单不隐藏指针（菜单关闭后重新开始计时）
            PointerActivity.TOUCH -> if (panelsPresent.isEmpty()) hide()
            // 拖动不是移动、滚轮不移动指针：两者都不恢复也不重置计时
            PointerActivity.DRAG_MOVE, PointerActivity.WHEEL -> Unit
        }
    }

    /**
     * 隐藏时的左键或右键按下：恢复指针并重新开始计时。
     * 返回 true = 这一段被消费（按下、按住移动与抬起都不执行页面行为）；可见时返回 false。
     */
    fun onWakeClick(): Boolean {
        if (!hidden) return false
        show()
        return true
    }

    /** 双击放大：恢复指针并重新开始计时 */
    fun onDoubleTapZoom() = show()

    /** 窗口焦点：失焦立即恢复指针，失焦期间不隐藏；回焦后从回焦时刻重新计时并保持可见 */
    fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
        show()
    }

    /** 面板出现/退场：出现立即恢复指针；全部退场后重新开始计时 */
    fun setPanelPresent(panel: PointerPanel, present: Boolean) {
        if (present) {
            panelsPresent += panel
            show()
        } else {
            panelsPresent -= panel
            if (panelsPresent.isEmpty()) lastMoveMillis = nowMillis()
        }
    }

    /** 把状态收敛到当前时刻：鼠标停满 3 秒就隐藏（窗口不在前台时不隐藏） */
    fun advance() {
        if (windowFocused && panelsPresent.isEmpty() && !hidden &&
            nowMillis() - lastMoveMillis >= POINTER_HIDE_DELAY_MILLIS
        ) {
            hide()
        }
    }

    /**
     * 界面一次读到的指针状态：显隐与剩余延时出自同一个时钟，界面据此只等这一次剩余值，
     * 不再另读时钟算绝对时刻。
     */
    fun state(): PointerAutoHideState =
        PointerAutoHideState(hidden = hidden, hideDelayMillis = hideDelayRemaining())

    /** 距离隐藏还剩多少毫秒；已经隐藏、窗口不在前台或任一面板在场时返回 0（没有待处理的计时）。 */
    private fun hideDelayRemaining(): Long =
        if (hidden || !windowFocused || panelsPresent.isNotEmpty()) {
            0L
        } else {
            (lastMoveMillis + POINTER_HIDE_DELAY_MILLIS - nowMillis()).coerceAtLeast(0L)
        }

    private fun hide() {
        hidden = true
    }

    /** 恢复指针并重新开始计时 */
    private fun show() {
        hidden = false
        lastMoveMillis = nowMillis()
    }
}
