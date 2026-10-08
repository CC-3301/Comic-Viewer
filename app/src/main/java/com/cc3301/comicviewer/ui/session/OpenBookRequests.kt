package com.cc3301.comicviewer.ui.session

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavController
import java.util.concurrent.atomic.AtomicLong

/**
 * 开书请求的**登记**与**落地闸门**：会话级一份，四条开书入口（浏览页点击 / 启动还原 /
 * 抽屉「阅读器」/ 读内换书）与阅读页的落地共用它。
 *
 * 两个时点各一个计数器，不合并：
 *
 * - **导航那一刻**（[begin] / [isCurrent] / [keyOf]）：每次点击领一个单调 token + 记下发起时栈顶那一项。
 *   四条入口走同一套登记——「这次点击算不算数」的判据因此只有一处，各入口只交自己那条**额外的存活条件**。
 *   - token 必须**单调不复用**：值相等会撞 ABA，A → B → A 三连点后旧 A 请求会被重新判为当前，
 *     与新 A 请求各导航一次（同一本书被切两次、第二次取不到前置槽）。
 *   - 栈项取**具体身份**（route pattern + back stack entry id）：浏览层级是同一个 destination、
 *     同一个 pattern、不同参数，只比 pattern 会把「用户已按返回」判成「没离开」。
 * - **落地那一刻**（[issueLanding] / [isLatestLanding]）：阅读页每次落地前领一个票号，写记录前问一次。
 *   落地写（进度覆盖 + 上次阅读位置）是慢 IO，两次落地可能乱序完成——票号把「谁是最新的那次落地」
 *   变成与完成顺序无关的判据。
 *
 * 领号点为什么留在落地那一路（不并进 [begin]）：阅读页兜底分支（前置超时/失败、进程被杀后自己组合）
 * **没有任何入口**可依，是它在开书前领号；合成一个计数器要把「票号按**发起顺序**发」这条论证拆到两处。
 *
 * 无构造依赖：生产那份由组合根持有（`SessionStateHolder`），经 [LocalOpenBookRequests] 下发到界面；
 * 单测自己 `OpenBookRequests()`（两个计数器因此可重置，不必拿进程全局的号比相对顺序）。
 */
internal class OpenBookRequests {

    /**
     * 栈顶那一项的**具体身份**：[route] 是该 destination 的 pattern（同一 destination 的
     * 不同参数下**完全相同**），[entryId] 是 back stack entry 的 id——两者一起才说得上「仍是那一项」。
     */
    data class EntryKey(val route: String?, val entryId: String?)

    /** 一次请求：单调 [token] + 发起时栈顶那一项 [origin]（`null` = 栈顶尚未定，按原样比较） */
    data class Request(val token: Int, val origin: EntryKey?)

    private var issued = 0

    private val issuedLandings = AtomicLong()

    /** 发起一次请求（每次点击领一个**单调递增**的 token，不复用） */
    fun begin(origin: EntryKey?): Request = Request(++issued, origin)

    /**
     * 这次请求还算不算数（[current] = 判定这一刻栈顶那一项）：
     * **也存活 → token 最新 → 栈项仍是发起时那一项**。
     *
     * [alsoAlive] 是该入口额外的存活条件（启动还原那条的等待挂在 `LaunchedEffect` 上，
     * 组合消失即不再算数；浏览页那条是这一屏的组合存活标志），**先于**栈项判定（短路顺序与收拢前一致）。
     */
    fun isCurrent(request: Request, current: EntryKey?, alsoAlive: () -> Boolean = { true }): Boolean =
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
        fun keyOf(nav: NavController): EntryKey? =
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
