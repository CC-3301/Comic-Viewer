package com.cc3301.comicviewer.ui

import androidx.navigation.NavController

/**
 * 根路由（首页）返回键的第二次确认窗口（票 #128；维护者 2026-09-23）。
 *
 * 两次返回的**时间窗**：窗内第二次才真的退出 APP。超时、或离开根路由（见 [atRootRoute]）即重新计数。
 */
internal const val ROOT_BACK_EXIT_WINDOW_MILLIS: Long = 2_000L

/** 根路由上一次返回对 APP 的要求（[RootBackExitState.onBack] 的返回值） */
internal enum class RootBackAction {
    /** 第一次（或超时后的又一次）：弹提示、**不退出** */
    PROMPT,

    /** 窗口内的第二次：退出 APP */
    EXIT,
}

/**
 * 根路由「再按一次退出」的状态机（票 #128，纯状态机，由 [RootBackExitStateTest] 锁定）。
 *
 * 无 UI、不是 `@Composable`：把「第一次提示、窗内第二次退出、超时/复位回到第一次」收在一处，
 * 因此时间窗判定可以在单测里逐条走完（真机只能看现象，钉不住边界）。
 *
 * **时钟由调用方给**（[onBack] 的 `nowMillis`）：本类不做 `SystemClock` 调用，用例因此能**精确落在窗口边界上**
 * （默认实现里读时钟的话，边界那一毫秒永远测不到）。
 *
 * 复位有两条路：显式 [reset]、以及调用方按「接管条件」重建实例（`AppNav` 用 `remember(key)`——离开根路由或
 * 抽屉开合都会换一个新实例，见那里的接线）。
 */
internal class RootBackExitState {

    /** 上一次「第一次返回」的时刻；null = 当前不在确认窗口内 */
    private var promptedAtMillis: Long? = null

    /**
     * 一次返回按下。返回 [RootBackAction.PROMPT] 时本状态机进入确认窗口（下一次按在窗内即退出）；
     * 返回 [RootBackAction.EXIT] 时窗口关闭——退出之后若进程还在（真机不会，也兜住误用），再按又是「第一次」。
     */
    fun onBack(nowMillis: Long): RootBackAction {
        val promptedAt = promptedAtMillis
        val withinWindow = promptedAt != null && nowMillis - promptedAt <= ROOT_BACK_EXIT_WINDOW_MILLIS
        promptedAtMillis = if (withinWindow) null else nowMillis
        return if (withinWindow) RootBackAction.EXIT else RootBackAction.PROMPT
    }

    /** 退出确认窗口作废：下一次返回重新算「第一次」 */
    fun reset() {
        promptedAtMillis = null
    }
}

/**
 * 此刻是否**真正停在根路由**（首页，且它下面没有别的层）——只有这种时候返回键才归「再按一次退出」管
 * （票 #128，由 [RootBackExitStateTest] 用真实 `NavController` 锁定）。
 *
 * 为什么要「下面没有别的层」这一条：抽屉的「首页」入口会把首页**压在浏览层之上**（票 #70 r2 AC9），
 * 那时的返回语义是「回到进入前的界面」（`navigateTopLevel` 的口径），不是退出 APP——
 * 只看 `currentDestination?.route == HOME` 会把这条路吞掉。
 *
 * 判据是 `previousBackStackEntry == null`：它只在当前 destination 之下没有别的 destination 时为 null
 * （路由图那个 NavGraph 层不算，见导航库的 `NavController.previousBackStackEntry`）。
 * **读它的调用点必须同时读一个组合态**（`AppNav` 读 `currentBackStackEntryAsState()`）：
 * 本属性不是组合态，换栈后靠那次重组重新取帧。
 */
internal fun atRootRoute(nav: NavController): Boolean =
    nav.currentDestination?.route == Routes.HOME && nav.previousBackStackEntry == null
