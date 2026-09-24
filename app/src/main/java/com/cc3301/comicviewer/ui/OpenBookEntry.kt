package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavController
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.view.pageDecodeWidthPx
import kotlinx.coroutines.CoroutineScope

/**
 * 「这次开书请求还算不算数」的判据（票 #132 步骤①；词条见 `CONTEXT.md` 的「开书入口」）。
 *
 * 为什么单独给一个类型：四条入口（浏览页点击 / 启动还原 / 抽屉「阅读器」/ 读内换书）共用的是**机制**
 * （登记 → 认主 → 导航 → 前置），而「什么算数」各入口本来就不同（票 #122 的口径，逐条不变）——
 * 浏览页看「这一屏还活着 + 当前要开的就是这一本」，另三条看「栈项身份（route + entryId）」。
 * 把它收成一个具名的单方法类型，入口通道就只认「哪本书 + 这条判据」，判据语义仍留在各自的入口里。
 *
 * 通道只在**导航那一刻问它一次**（票 #122 起导航在点击那一帧发生，前置与落地都在那之后）。
 */
internal fun interface OpenRequestGuard {
    fun isCurrent(): Boolean
}

/**
 * 一次开书请求要开的**那本书**（票 #132 步骤①）：来源（可为 null ⇒ 不做前置工作）+ 连接 id + 书 id。
 *
 * 连接 id 与书 id 是前置槽的键（票 #110：书 id 只在对应连接内有效）。连接 id 为空时通道照旧导航、
 * 只是没有可交前置的地方（`enterReaderThenPreload` 的既有口径）。
 */
internal data class OpenBookTarget(val source: Source?, val connId: Long?, val bookId: String)

/**
 * 「开书入口」通道（票 #132 步骤①，由 `OpenBookEntryTest` 锁定）：四条入口**唯一**的开书动作。
 * 一个入口只交三样东西——**哪本书**（[OpenBookTarget]）、**这次请求算不算数**（[OpenRequestGuard]）、
 * **怎么进阅读器**（`enterReader`）；其余全部在这里：
 *
 * - **领世代号 + 登记前置**（[ReaderPrelude.begin]）：阅读页据此决定要不要有界等这份前置，也只有最新那次
 *   请求的前置才入槽（票 #122 r2 的世代号口径）；
 * - **导航**（点击那一帧就走，滑入立刻开始；票 #122 的改序）——守卫为假时**不导航**（票 #108 的
 *   「取消不导航」在新形状下的对应：判据只在那一次点击当时问一次）；
 * - **会话级前置**：工作跑在 [workScope]（生产 = 会话级作用域，导航会立刻销毁发起那一屏），
 *   首批解码宽度取 [targetWidthPx]（读**当下**那一帧的宽度：启动落地那条在首帧布局之前就开跑）；
 * - **「始终从第一页打开」的判据在这一处读一次**（点击时刻读，随前置槽一起带到落地那一刻，票 #110 r3）。
 *   四条入口原先各读一次，读点与落点是否同源只能靠逐个入口的注释保证。
 *
 * 为什么收成一处：四条入口原先各自逐字写一遍九参调用（每处都要记住 workScope / prelude / targetWidthPx /
 * alwaysFirstPage 该传什么），而 #122 r2（世代号）、#122 r3（退役）、#126（持锁竞态）三轮修出来的 bug
 * 全部长在这层接线里——接线层零自动测试、四处复制时错一处不会有人发现。
 *
 * **不改判据语义**（票面：收机制、不动语义）：算不算数仍由各入口自己的 [OpenRequestGuard] 说，
 * 通道只负责在同一个时点问它。
 */
internal class OpenBookEntry(
    /** 前置工作的作用域（生产 = 会话级：导航会立刻销毁发起那一屏） */
    private val workScope: CoroutineScope,
    /** 四条入口共用的前置槽 */
    private val prelude: ReaderPrelude,
    /** 阅读页目标宽度 px（前置解码宽度必须与阅读页那把解码缓存键一致，见 [pageDecodeWidthPx]） */
    private val targetWidthPx: () -> Int,
) {

    /**
     * 开这本书：先导航（守卫说了算），前置（开书 + 首批解码）在 [workScope] 里跑完入槽。
     * 挂起返回**不等**前置做完——发起那一屏通常已被导航销毁；等待由阅读页侧承担（[ReaderPrelude.await]）。
     */
    suspend fun open(
        target: OpenBookTarget,
        guard: OpenRequestGuard,
        enterReader: () -> Unit,
    ) {
        enterReaderThenPreload(
            workScope = workScope,
            prelude = prelude,
            source = target.source,
            connId = target.connId,
            bookId = target.bookId,
            targetWidthPx = targetWidthPx,
            alwaysFirstPage = AppSettings.alwaysOpenFirstPage,
            isRequestCurrent = guard::isCurrent,
            enterReader = enterReader,
        )
    }
}

/**
 * 生产那份「开书入口」（[OpenBookEntry] 的接线）：本仓唯一的构造点——会话级作用域 + 前置槽 +
 * **留 view 本体**的宽度读法（启动落地那条在首帧布局之前就开跑，到那时再读一次宽度；
 * 先把当前宽度取下的话那一刻就是 0 宽）。
 */
@Composable
internal fun rememberOpenBookEntry(): OpenBookEntry {
    val hostView = LocalView.current
    // 键取那个 view（票 #132 r2 评审）：无键 `remember` 会把第一个 view 闭包捕获到死——
    // CompositionLocal 换实例（重新宿主/挂到另一个 View）时缓存不跟着换，前置解码宽度会长期读旧 view，
    // 而「前置解码宽度 = 阅读页那把缓存键」是紧耦合契约（收拢前两处调用点每帧重读，不存在这个窗口）。
    return remember(hostView) {
        OpenBookEntry(
            workScope = ServiceLocator.appScope,
            prelude = ServiceLocator.readerPrelude,
            targetWidthPx = { pageDecodeWidthPx(hostView.width.toFloat()) },
        )
    }
}

/**
 * 三条 AppNav 入口（启动还原 / 抽屉「阅读器」/ 读内换书）的**守卫登记**（票 #132 步骤①）：
 * 领一个单调 token + 记下发起时栈顶那一项（[ReaderEntryRequest.keyOf]），交出一条「这次请求还算不算数」的判据。
 *
 * 语义与 #111 r3 逐字相同（[ReaderEntryRequest] 的判据没动），收掉的只是三处各写一遍的「begin + lambda」。
 *
 * [alsoAlive] = 该入口额外的存活条件，**先于**栈项判定（短路顺序与收拢前一致）：启动还原那条的等待挂在
 * `LaunchedEffect` 上，因此除栈项外还要求 AppNav 组合仍存活；另两条没有这一道。
 */
internal fun ReaderEntryRequest.beginGuard(
    nav: NavController,
    alsoAlive: () -> Boolean = { true },
): OpenRequestGuard {
    val request = begin(ReaderEntryRequest.keyOf(nav))
    return OpenRequestGuard { alsoAlive() && isCurrent(request, ReaderEntryRequest.keyOf(nav)) }
}
