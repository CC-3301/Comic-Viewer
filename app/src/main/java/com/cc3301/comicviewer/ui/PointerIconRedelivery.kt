package com.cc3301.comicviewer.ui

import android.view.PointerIcon
import android.view.View

/**
 * 隐藏态指针图标的重发（spec §指针自动隐藏）：显隐判据在 `core/input/PointerAutoHide`，
 * 本类只管把透明图标重新按到窗口上，并把重发节流到同一帧至多一次。
 *
 * - **窗口回焦**：系统会把图标换回默认，仍隐藏就立即重发（[onWindowFocusGained]），不等下一次鼠标移动。
 * - **事件处理之后**：隐藏期间的悬停移动与左键拖动也可能让系统换回图标，仍未恢复就排一次重发
 *   （[onPointerEventHandled]），跑在主线程事件分发之外；在途的那次落地之前不再排。
 * - **退出阅读器**：[cancel] 撤掉还没执行的那次。
 *
 * [hidden] 每次现读判据而不是缓存一份：排进队列到落地之间指针可能已经恢复，那时不能再把图标按成透明。
 */
internal class PointerIconRedelivery(
    private val view: View,
    private val icon: PointerIcon,
    private val hidden: () -> Boolean,
) {

    /** 已排进消息队列、还没执行的重发 */
    private var pending = false

    /** 排进队列的那次：先清在途标记再按图标，落地之后同一帧还能再排下一次 */
    private val applyIcon = Runnable {
        pending = false
        if (hidden()) view.setPointerIcon(icon)
    }

    /** 窗口重新获得焦点：仍隐藏就立即重发透明图标 */
    fun onWindowFocusGained() {
        if (!hidden()) return
        // 撤掉在途的那次：否则这次回焦会在同一帧里按第二遍
        cancel()
        view.setPointerIcon(icon)
    }

    /** 隐藏期间的悬停移动或左键拖动处理完：仍未恢复就排一次重发 */
    fun onPointerEventHandled() {
        if (!hidden() || pending) return
        pending = true
        view.post(applyIcon)
    }

    /** 退出阅读器：撤掉还没执行的重发 */
    fun cancel() {
        pending = false
        view.removeCallbacks(applyIcon)
    }
}
