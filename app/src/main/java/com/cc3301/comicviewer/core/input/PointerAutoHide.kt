package com.cc3301.comicviewer.core.input

import kotlin.math.hypot

/** 鼠标停止移动多久后隐藏指针（毫秒） */
const val POINTER_HIDE_DELAY_MILLIS = 3_000L

/** 隐藏后累计移动多远恢复指针（dp）：界面按密度换算成像素交给 [PointerAutoHide] */
const val POINTER_RECOVER_DISTANCE_DP = 100f

/** 隐藏后两次移动之间的停顿超过此值，累计距离从零重算（毫秒） */
const val POINTER_RECOVER_PAUSE_MILLIS = 500L

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
 * 阅读器鼠标指针自动隐藏的判据（纯逻辑，由 [PointerAutoHideTest] 锁定）。
 *
 * 判据进 `core/input/`、`ui/` 只做事件翻译（与 `core/input/` 其余手势同一分工）。本模块的口径
 * （spec §指针自动隐藏）：
 *
 * - **隐藏**：鼠标停止移动满 [POINTER_HIDE_DELAY_MILLIS]，计时从最后一次移动起算（[advance] 收敛到当前时刻）。
 * - **恢复只有两条**：累计移动满 [recoverDistancePx]（[PointerActivity.HOVER_MOVE]），或双击放大
 *   （[onDoubleTapZoom]）。隐藏后两次移动之间停顿超过 [POINTER_RECOVER_PAUSE_MILLIS] 则累计从零重算。
 * - **立即恢复**：打开阅读菜单 [onMenuOpened]、双击放大。恢复后重新开始计时；菜单关闭 [onMenuClosed] 同样重新计时。
 * - **菜单开着时不隐藏**：菜单要用鼠标点，指针在这期间藏起来就点不着了。
 * - **立即隐藏**：[PointerActivity.TOUCH]。
 * - **不算移动**：[PointerActivity.DRAG_MOVE] / [PointerActivity.WHEEL] 既不恢复也不重置计时。
 *
 * @param recoverDistancePx 恢复所需的累计移动距离（像素）：界面按密度把 [POINTER_RECOVER_DISTANCE_DP] 换算过来
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

    /** 最近一次移动的时刻：可见时是隐藏计时的起点，隐藏时是「停顿」判定的起点 */
    private var lastMoveMillis = nowMillis()

    private var lastX = 0f
    private var lastY = 0f

    /** 隐藏后累计的移动距离（像素） */
    private var movedPx = 0f

    /**
     * 喂一次指针活动。[x] / [y] 是**节点坐标**下的指针位置，只有 [PointerActivity.HOVER_MOVE] 用得到。
     */
    fun onActivity(activity: PointerActivity, x: Float = 0f, y: Float = 0f) {
        when (activity) {
            PointerActivity.HOVER_MOVE -> mouseMoved(x, y)
            PointerActivity.TOUCH -> hidden = true
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
        movedPx = 0f
    }

    /** 把状态收敛到当前时刻：鼠标停满 3 秒就隐藏 */
    fun advance() {
        if (!menuOpen && !hidden && nowMillis() - lastMoveMillis >= POINTER_HIDE_DELAY_MILLIS) hidden = true
    }

    /**
     * 隐藏截止时刻（毫秒，时钟口径同 [nowMillis]）：界面据此安排一次到点收敛。
     * 已经隐藏或菜单开着时返回 0（没有待处理的截止时刻）。
     */
    fun hideDeadlineMillis(): Long = if (hidden || menuOpen) 0L else lastMoveMillis + POINTER_HIDE_DELAY_MILLIS

    private fun mouseMoved(x: Float, y: Float) {
        val now = nowMillis()
        if (hidden) {
            // 停顿超过 0.5 秒：这一段移动从零重新累计
            if (now - lastMoveMillis > POINTER_RECOVER_PAUSE_MILLIS) movedPx = 0f
            movedPx += hypot(x - lastX, y - lastY)
            if (movedPx >= recoverDistancePx) {
                hidden = false
                // 累计已用掉：下一次隐藏从零重新算，否则再藏起来时一动就恢复
                movedPx = 0f
            }
        }
        lastMoveMillis = now
        lastX = x
        lastY = y
    }

    /** 恢复指针并重新开始计时 */
    private fun show() {
        hidden = false
        lastMoveMillis = nowMillis()
        movedPx = 0f
    }
}
