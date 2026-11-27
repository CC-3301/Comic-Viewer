package com.cc3301.comicviewer.ui

import android.view.Choreographer
import android.view.PointerIcon
import android.view.View

/**
 * 窗口指针图标的落地（spec §指针自动隐藏）：显隐判据在 `core/input/PointerAutoHide`，本类只管按图标。
 *
 * 系统只在有事件的时刻重新解析窗口指针图标，所以两件事分开：
 * - **显隐一变当场按一次**（[onHiddenChanged]）：隐藏按透明图标，恢复把窗口图标交还系统（null = 系统自带箭头）。
 *   此刻在重组带来的帧回调里，不在指针事件分发中，`View.setPointerIcon` 会立即让系统重新解析。
 * - **隐藏期间每次指针事件处理完再按一次**（[onPointerEventHandled]）：事件本身可能已把图标换回箭头，
 *   这次重发压到下一帧、同一帧至多一次（帧回调跑在事件分发之外）。
 *
 * [transparent] 是透明图标（系统的 TYPE_NULL），运行期不变，构造时给。
 */
internal class PointerIconRedelivery(
    private val view: View,
    private val transparent: PointerIcon,
) {
    /** 帧回调的来源：重发按屏幕帧落地，不挂在消息队列上 */
    private val choreographer = Choreographer.getInstance()

    /** 指针当前是否隐藏：重发只在这个值上分「按透明图标」与「交还系统」 */
    private var hidden = false

    /** 已经排进帧回调、还没执行的重发 */
    private var pending = false

    /** 排进帧回调的那次：先清在途标记再按图标，落地之后下一帧还能再排 */
    private val applyIcon = Choreographer.FrameCallback {
        pending = false
        view.setPointerIcon(pointerIcon())
    }

    /** 显隐变了：当场按一次（先撤掉在途的那次，免得同一帧里按第二遍） */
    fun onHiddenChanged(hidden: Boolean) {
        this.hidden = hidden
        cancel()
        view.setPointerIcon(pointerIcon())
    }

    /** 隐藏期间的一次指针事件处理完：排到下一帧再按一次透明图标 */
    fun onPointerEventHandled() {
        if (!hidden || pending) return
        pending = true
        choreographer.postFrameCallback(applyIcon)
    }

    /** 撤掉还没落地的那次（显隐刚变、退出阅读器） */
    fun cancel() {
        pending = false
        choreographer.removeFrameCallback(applyIcon)
    }

    /** 隐藏 = 透明图标；可见 = 交还系统（null 即不再覆盖窗口图标） */
    private fun pointerIcon(): PointerIcon? = if (hidden) transparent else null
}
