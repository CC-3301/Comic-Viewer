package com.cc3301.comicviewer.core.input

/** 鼠标停止移动多久后隐藏指针（毫秒） */
const val POINTER_HIDE_DELAY_MILLIS = 3_000L

/**
 * 界面把指针事件翻译成的活动（core 不认识 Compose 的指针类型）。
 * 只有 [HOVER_MOVE] 与 [TOUCH] 改变显隐状态；[DRAG_MOVE] 与 [WHEEL] 都不改变。
 */
internal enum class PointerActivity {
    /** 鼠标悬停移动（没按键）：可见时重置隐藏计时 */
    HOVER_MOVE,

    /** 按住左键拖动滚动：不恢复也不重置计时 */
    DRAG_MOVE,

    /** 滚轮滚动：不恢复也不重置计时 */
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
 * - **隐藏**：鼠标停止移动满 [POINTER_HIDE_DELAY_MILLIS]，计时从最后一次悬停移动起算（[advance] 收敛到当前时刻）。
 * - **移动不恢复**：隐藏期间的悬停移动、左键拖动与滚轮都不恢复，也不重置计时。
 * - **点击唤醒**：隐藏后的鼠标左键或右键按下立即恢复并重新开始计时（[onClickPress]）；返回 true 表示
 *   这一次按下整段被消费——界面据此丢弃这一次点击（不呼出菜单、不翻页、不双击缩放、不拖动滚动，
 *   也不进双击历史）。可见时的按下不唤醒、也不重置计时，因此抬起后的下一次点击照常处理。
 * - **立即恢复**：双击放大 [onDoubleTapZoom]、面板出现 [setPanelPresent]。恢复后重新开始计时；
 *   面板全部退场后同样重新计时。
 * - **面板在场时不隐藏**：阅读菜单与跨书条都要用鼠标点，指针在这期间藏起来就点不着了；
 *   这期间落在它们上的触摸也不隐藏。
 * - **立即隐藏**：没有面板在场时的触摸（[PointerActivity.TOUCH]），即触摸正文。
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

    /** 喂一次指针活动（位置用不到：显隐判据不按位移算） */
    fun onActivity(activity: PointerActivity) {
        when (activity) {
            // 可见时悬停移动重置计时；隐藏后移动不恢复，也不再计时
            PointerActivity.HOVER_MOVE -> if (!hidden) lastMoveMillis = nowMillis()
            // 拖动与滚轮都不是移动：既不恢复也不重置计时
            PointerActivity.DRAG_MOVE, PointerActivity.WHEEL -> Unit
            // 菜单开着时的触摸落在菜单上：点菜单不隐藏指针（菜单关闭后重新开始计时）
            PointerActivity.TOUCH -> if (panelsPresent.isEmpty()) hide()
        }
    }

    /**
     * 鼠标左键或右键按下：隐藏时立即恢复并重新开始计时，返回 true = 这一次按下整段被消费。
     * 可见时什么也不做（返回 false）——可见时只有悬停移动重置计时。
     */
    fun onClickPress(): Boolean {
        if (!hidden) return false
        show()
        return true
    }

    /** 双击放大：恢复指针并重新开始计时 */
    fun onDoubleTapZoom() = show()

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

    /** 把状态收敛到当前时刻：鼠标停满 3 秒就隐藏 */
    fun advance() {
        if (panelsPresent.isEmpty() && !hidden && nowMillis() - lastMoveMillis >= POINTER_HIDE_DELAY_MILLIS) hide()
    }

    /**
     * 界面一次读到的指针状态：显隐与剩余延时出自同一个时钟，界面据此只等这一次剩余值，
     * 不再另读时钟算绝对时刻。
     */
    fun state(): PointerAutoHideState =
        PointerAutoHideState(hidden = hidden, hideDelayMillis = hideDelayRemaining())

    /** 距离隐藏还剩多少毫秒；已经隐藏或任一面板在场时返回 0（没有待处理的计时）。 */
    private fun hideDelayRemaining(): Long =
        if (hidden || panelsPresent.isNotEmpty()) 0L else (lastMoveMillis + POINTER_HIDE_DELAY_MILLIS - nowMillis()).coerceAtLeast(0L)

    private fun hide() {
        hidden = true
    }

    /** 恢复指针并重新开始计时 */
    private fun show() {
        hidden = false
        lastMoveMillis = nowMillis()
    }
}
