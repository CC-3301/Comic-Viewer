package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * 组合期处理器槽位（票 25 第 2 项）：平台事件分发入口（[MainActivity]）按 [value] 读，
 * 界面在组合期用 [RegisterSlot] 挂上自己那一份、离开组合自动摘下。
 *
 * 槽位是单值的，而界面切换时两个界面的组合期会短暂重叠，因此摘下必须走 [clearSlotIfCurrent] 的
 * 身份守卫，不能无条件写 null——否则先前界面的 onDispose 会把后进入界面刚注册的处理器抹掉
 * （浏览列表滚轮与阅读器滚轮共用同一槽位，正是这条路径）。
 */
class HandlerSlot<T : Any> {
    @Volatile
    var value: T? = null
        internal set
}

/**
 * 槽位清理守卫（票 25 第 2 项，纯函数，由 [SlotReleaseTest] 锁定）：
 * 槽位当前仍是本次注册的那一份（引用身份，不是 equals）时才清空——null 与后继注册者都不清。
 * 返回是否真的清了。
 */
internal fun <T : Any> clearSlotIfCurrent(current: T?, registered: T, clear: () -> Unit): Boolean {
    if (current !== registered) return false
    clear()
    return true
}

/**
 * 组合期槽位注册（票 25 第 2 项）：把 [value] 挂到 [slot]，离开组合时按 [clearSlotIfCurrent] 守卫摘除。
 * 原来五处（浏览列表滚轮、阅读器滚轮、阅读器右键、阅读器音量键、前进侧键）各自手写同一段
 * DisposableEffect，其中两处的守卫还写成了不同形状。
 */
@Composable
internal fun <T : Any> RegisterSlot(slot: HandlerSlot<T>, value: T) {
    DisposableEffect(value) {
        slot.value = value
        onDispose { clearSlotIfCurrent(slot.value, value) { slot.value = null } }
    }
}
