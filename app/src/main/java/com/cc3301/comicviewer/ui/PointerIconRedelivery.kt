package com.cc3301.comicviewer.ui

import android.view.PointerIcon
import android.view.View

/**
 * 窗口指针图标的落地（spec §指针自动隐藏）：显隐判据在 `core/input/PointerAutoHide`，本类只管按图标。
 *
 * 系统只在有事件的时刻重新解析窗口指针图标，所以两件事分开：
 * - **显隐一变当场按一次**（[onHiddenChanged]）：隐藏按透明图标，恢复把窗口图标交还系统（null = 系统自带箭头）。
 * - **隐藏期间每次指针事件处理完当场再按一次**（[onPointerEventHandled]）：事件本身可能已把图标换回箭头。
 *   当场按就够，不必等下一帧：鼠标事件都走 `ViewRootImpl.processPointerEvent`，分发期间
 *   `View.setPointerIcon` 只记下 `mMousePointerIcon`，同一次分发结束后框架按它重新解析图标——正是「处理完」这一刻。
 *
 * [transparent] 是透明图标（系统的 TYPE_NULL），运行期不变，构造时给。
 */
internal class PointerIconRedelivery(
    private val view: View,
    private val transparent: PointerIcon,
) {
    /** 指针当前是否隐藏：隐藏期间才补按，可见时窗口图标就是系统箭头 */
    private var hidden = false

    /** 显隐变了：当场按一次 */
    fun onHiddenChanged(hidden: Boolean) {
        this.hidden = hidden
        view.setPointerIcon(pointerIcon())
    }

    /** 隐藏期间的一次指针事件处理完：当场再按一次透明图标 */
    fun onPointerEventHandled() {
        if (!hidden) return
        view.setPointerIcon(transparent)
    }

    /** 隐藏 = 透明图标；可见 = 交还系统（null 即不再覆盖窗口图标） */
    private fun pointerIcon(): PointerIcon? = if (hidden) transparent else null
}
