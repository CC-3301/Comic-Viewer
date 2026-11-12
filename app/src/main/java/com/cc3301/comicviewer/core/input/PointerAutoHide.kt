package com.cc3301.comicviewer.core.input

import kotlin.math.hypot

/** 鼠标停止移动多久后隐藏指针（毫秒） */
const val POINTER_HIDE_DELAY_MILLIS = 3_000L

/** 隐藏后指针离锚点多远恢复（dp）：界面按密度换算成像素交给 [PointerAutoHide] */
const val POINTER_RECOVER_DISTANCE_DP = 100f

/**
 * 界面把指针事件翻译成的活动（core 不认识 Compose 的指针类型）。
 * 只有 [HOVER_MOVE] 与 [TOUCH] 改变状态，另两条在此显式落成「不恢复也不重置计时」。
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
 * - **隐藏**：鼠标停止移动满 [POINTER_HIDE_DELAY_MILLIS]，计时从最后一次移动起算（[advance] 收敛到当前时刻）。
 *   隐藏那一刻的指针位置记成锚点。
 * - **恢复只有两条**：指针离锚点的直线距离满 [recoverDistancePx]（[PointerActivity.HOVER_MOVE]），或双击放大
 *   （[onDoubleTapZoom]）。只看净位移，来回摆动互相抵消，不按走过的路程累加。
 * - **立即恢复**：打开阅读菜单 [onMenuOpened]、双击放大。恢复后重新开始计时；菜单关闭 [onMenuClosed] 同样重新计时。
 * - **菜单开着时不隐藏**：菜单要用鼠标点，指针在这期间藏起来就点不着了；这期间落在菜单上的触摸也不隐藏。
 * - **立即隐藏**：菜单关着时的触摸（[PointerActivity.TOUCH]），即触摸正文。
 * - **不算移动**：[PointerActivity.DRAG_MOVE] / [PointerActivity.WHEEL] 既不恢复也不重置计时。
 *
 * @param recoverDistancePx 恢复所需的直线距离（像素）：界面按密度把 [POINTER_RECOVER_DISTANCE_DP] 换算过来
 * @param nowMillis 时钟（注入以便单测）
 */
internal class PointerAutoHide(
    private val recoverDistancePx: Float,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 指针当前是否隐藏（两态：可见 = 系统默认指针，隐藏 = 透明指针） */
    var hidden = false
        private set

    /** 菜单是否开着：开着时指针不隐藏 */
    private var menuOpen = false

    /** 最近一次移动的时刻：可见时是隐藏计时的起点 */
    private var lastMoveMillis = nowMillis()

    /** 指针最近一次出现的位置（节点坐标）；隐藏那一刻的样子就是锚点 */
    private var lastX = 0f
    private var lastY = 0f

    /** 隐藏时记下的锚点（节点坐标）：隐藏期间只判它与当前位置的直线距离 */
    private var anchorX = 0f
    private var anchorY = 0f

    /**
     * 喂一次指针活动。[x] / [y] 是**节点坐标**下的指针位置，只有 [PointerActivity.HOVER_MOVE] 用得到。
     */
    fun onActivity(activity: PointerActivity, x: Float = 0f, y: Float = 0f) {
        when (activity) {
            PointerActivity.HOVER_MOVE -> mouseMoved(x, y)
            // 菜单开着时的触摸落在菜单上：点菜单不隐藏指针（菜单关闭后重新开始计时）
            PointerActivity.TOUCH -> if (!menuOpen) hide()
            // 滚轮滚动、按住左键拖动：都不算移动
            PointerActivity.DRAG_MOVE, PointerActivity.WHEEL -> Unit
        }
    }

    /** 双击放大：恢复指针并重新开始计时 */
    fun onDoubleTapZoom() = show()

    /** 打开阅读菜单：立即恢复指针并重新开始计时 */
    fun onMenuOpened() {
        menuOpen = true
        show()
    }

    /** 菜单关闭：重新开始计时 */
    fun onMenuClosed() {
        menuOpen = false
        lastMoveMillis = nowMillis()
    }

    /** 把状态收敛到当前时刻：鼠标停满 3 秒就隐藏 */
    fun advance() {
        if (!menuOpen && !hidden && nowMillis() - lastMoveMillis >= POINTER_HIDE_DELAY_MILLIS) hide()
    }

    /**
     * 界面一次读到的指针状态：显隐与剩余延时出自同一个时钟，界面据此只等这一次剩余值，
     * 不再另读时钟算绝对时刻。
     */
    fun state(): PointerAutoHideState =
        PointerAutoHideState(hidden = hidden, hideDelayMillis = hideDelayRemaining())

    /** 距离隐藏还剩多少毫秒；已经隐藏或菜单开着时返回 0（没有待处理的计时）。 */
    private fun hideDelayRemaining(): Long =
        if (hidden || menuOpen) 0L else (lastMoveMillis + POINTER_HIDE_DELAY_MILLIS - nowMillis()).coerceAtLeast(0L)

    private fun mouseMoved(x: Float, y: Float) {
        if (hidden) {
            // 净位移：抖动来回摆动时离锚点的距离不变，累不出 100dp
            if (hypot(x - anchorX, y - anchorY) >= recoverDistancePx) hidden = false
        }
        lastMoveMillis = nowMillis()
        lastX = x
        lastY = y
    }

    /** 记下锚点并隐藏 */
    private fun hide() {
        if (hidden) return
        hidden = true
        anchorX = lastX
        anchorY = lastY
    }

    /** 恢复指针并重新开始计时 */
    private fun show() {
        hidden = false
        lastMoveMillis = nowMillis()
    }
}
