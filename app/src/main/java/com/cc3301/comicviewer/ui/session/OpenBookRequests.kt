package com.cc3301.comicviewer.ui.session

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavController
import java.util.concurrent.atomic.AtomicLong

/**
 * 开书请求的登记与落地闸门：会话级一份，四条开书入口（浏览页点击 / 启动还原 / 抽屉「阅读器」/
 * 读内换书）与阅读页的落地共用它。
 *
 * - 导航那一刻（[begin] / [isCurrent] / [keyOf]）：每次点击领一个单调 token，并记下发起时栈顶那一项。
 *   四条入口走同一套登记，各入口只交自己那条额外的存活条件。token 单调不复用；栈项取具体身份
 *   （route pattern + back stack entry id）。
 * - 落地那一刻（[issueLanding] / [isLatestLanding]）：阅读页每次落地前领一个票号，写记录前问一次。
 *   落地写（进度覆盖 + 上次阅读位置）是慢 IO，两次落地可能乱序完成，票号是「谁最新」的判据。
 *
 * 两个计数器不合并：阅读页兜底分支（前置超时/失败、进程被杀后自己组合）没有入口可依，由它在开书前领号。
 *
 * 无构造依赖：生产那份由组合根持有（`SessionStateHolder`），经 [LocalOpenBookRequests] 下发到界面；
 * 单测自己 `OpenBookRequests()`，两个计数器因此可重置。
 *
 * 对外面：`beginGuard`（通道侧的登记交接口）+ 落地闸门两个入口（[issueLanding] / [isLatestLanding]）。
 * 判定原语（[begin] / [isCurrent] / [keyOf]）与两个构造面（[EntryKey] / [Request]）只对同模块开放
 * （`internal`）：前者只被 `beginGuard` 与单测用，后者生产只有 [begin] / [keyOf] 造；两个计数器是 `private` 字段。
 */
internal class OpenBookRequests {

    /**
     * 栈顶那一项的**具体身份**：[route] 是该 destination 的 pattern（同一 destination 的
     * 不同参数下**完全相同**），[entryId] 是 back stack entry 的 id——两者一起才说得上「仍是那一项」。
     */
    data class EntryKey internal constructor(val route: String?, val entryId: String?)

    /** 一次请求：单调 [token] + 发起时栈顶那一项 [origin]（`null` = 栈顶尚未定，按原样比较） */
    data class Request internal constructor(val token: Int, val origin: EntryKey?)

    private var issued = 0

    private val issuedLandings = AtomicLong()

    /** 发起一次请求（每次点击领一个**单调递增**的 token，不复用） */
    internal fun begin(origin: EntryKey?): Request = Request(++issued, origin)

    /**
     * 这次请求还算不算数（[current] = 判定这一刻栈顶那一项）：
     * **也存活 → token 最新 → 栈项仍是发起时那一项**。
     *
     * [alsoAlive] 是该入口额外的存活条件（启动还原那条的等待挂在 `LaunchedEffect` 上，
     * 组合消失即不再算数；浏览页那条是这一屏的组合存活标志），**先于**栈项判定（短路顺序与收拢前一致）。
     */
    internal fun isCurrent(request: Request, current: EntryKey?, alsoAlive: () -> Boolean = { true }): Boolean =
        alsoAlive() && request.token == issued && request.origin == current

    /** 领一个落地票号（阅读页每次落地前领，越晚领越大） */
    fun issueLanding(): Long = issuedLandings.incrementAndGet()

    /** 这个票号还是最新的吗？（写记录之前问一次） */
    fun isLatestLanding(ticket: Long): Boolean = issuedLandings.get() == ticket

    companion object {
        /**
         * 读「当前栈顶那一项」（生产唯一取法）：四条入口都走这里，避免各自去读 pattern 或 id。
         * `currentBackStackEntry` 是栈顶那一项，任何导航（含同 pattern 不同参数的浏览层级）都会换一个。
         */
        internal fun keyOf(nav: NavController): EntryKey? =
            nav.currentBackStackEntry?.let { EntryKey(it.destination.route, it.id) }
    }
}

/**
 * 开书请求模块的**组合期提供点**：`MainActivity` 在 `setContent` 里提供（提供点唯一一处），
 * 界面按 [LocalOpenBookRequests] 取实例。没提供就报错——漏接是接线错，不静默降级
 * （与 `LocalSessionState` 同一口径）。
 */
internal val LocalOpenBookRequests: ProvidableCompositionLocal<OpenBookRequests> = compositionLocalOf {
    error("没有提供开书请求模块（提供点在 MainActivity，见 ui/session/OpenBookRequests.kt）")
}
