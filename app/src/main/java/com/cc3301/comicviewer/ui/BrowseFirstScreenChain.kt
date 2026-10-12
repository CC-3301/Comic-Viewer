package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 浏览页首屏链的编排：**单段取数 → 取够后把滚动位置放回去**。
 *
 * 为什么要有它：这两件事此前散在 `BrowserScreen` 的一个 `LaunchedEffect` 里，而它们之间的约束
 * **全是顺序约束**——单测只能各测一段（`BrowsePageLoaderTest` 测取数、`BrowseScrollRestoreTest` 测两个纯函数），
 * 合起来的时序没有接缝（连续几张改的都是同一个 effect）。链收到这里之后，
 * 界面只提供 [ports] 里那些真正碰 Compose 的事，整条链可以用假来源一次跑完（`BrowseFirstScreenChainTest`）。
 *
 * 两条时序不变量（原先只活在 `BrowserScreen` 的注释里，现在搬成这个类的契约）：
 * 1. **恢复索引先于取数落屏**（[run] 的第一件事）：同代只读一次（来源解析会让本链重跑，第二次读到的是上一次取数之后的索引）；
 * 2. **取数不落中间帧**：上屏的只有取完的那份列表（[BrowsePageLoader.loadFirstScreen] / [BrowsePageLoader.showAll]），
 *    取数下限含恢复索引（[BrowsePageLoader.loadFirstScreen] 的 `atLeast`）⇒ 列表不短于恢复位置所需。
 *
 * @param pager 首屏落进的取数器（界面读它渲染，分页状态也在那里）
 * @param source 本页来源；null = 还没解析出来（[run] 返回 null）
 * @param reverse 反向档：方向要对整份列表翻转，追加页复现不了「从尾到头」⇒ 整份取完（按需加载只在正向档）；
 * 也不放回位置——整份上屏后列表不比离开前短，[scrollRestoreTarget] 的判据（位置确实退到了恢复索引之前）在这里不适用。
 * @param restoredIndex 恢复索引的持有者（同代只读一次，见 [RestoredScrollIndex]）
 */
internal class BrowseFirstScreenChain(
    private val pager: BrowsePageLoader,
    private val source: Source?,
    private val containerId: String?,
    private val sort: SortMode,
    private val reverse: Boolean,
    private val restoredIndex: RestoredScrollIndex,
    private val ports: BrowseFirstScreenChainPorts,
) {
    /**
     * 跑一次首屏链。
     *
     * @param generation 本次取数的代次（下拉更新 / 重试的 `reloadTick`）：换一代就重读当下索引
     *（界面重读、这里只负责「同代不覆盖」，见 [RestoredScrollIndex.valueFor]）
     * @param restoredIndexNow 本帧**当下**读到的恢复索引（`Lazy` 项坐标）；代次 0 时调用方已与
     *「离开这一屏那一刻记下的未夹值」取较大者（[unclippedRestoredScrollIndex]）
     * @return null = 来源还没解析出来：什么都没取、也没上屏，调用方据此跳过收尾
     *（不清提示、不复位下拉指示器）
     */
    suspend fun run(generation: Int, restoredIndexNow: Int): BrowseFirstScreenChainResult? {
        // 不变量 1：恢复索引先定下来，且同代只读一次——来源解析会让本链重跑，第二次读到的是上一次取数之后的索引。
        val restoredItemIndex = restoredIndex.valueFor(generation, restoredIndexNow)
        // 来源守卫：还没解析出来就什么都没得取（本链不产能）。
        val src = source ?: return null
        ports.onFetchStart()
        return try {
            if (reverse) {
                // 反向档：整层枚举一次后整份上屏；方向翻转在展示层（`rememberShownEntries`）
                val list = withContext(Dispatchers.IO) {
                    listEntriesRememberingNames(src, containerId, sort, pager.entryNames)
                }
                pager.showAll(list)
            } else {
                // 正向档：取够「恢复索引 + 1」条再上屏（[BrowsePageLoader.loadFirstScreen]）
                pager.loadFirstScreen(restoredItemIndex)
            }
            // 取数落地后才问截断提示：纯内存读、不发起任何请求，是对刚跑完这次取数的记账；
            // 读一次既交回界面态（列表第 0 行），也给「放回」算上它占的那一行。
            val notice = src.listTruncationNotice(containerId, sort)
            // 放回位置只发生在正向档（见 [reverse] 的 KDoc：反向档整份上屏，放回判据不适用）
            if (!reverse) restoreScrollPosition(restoredItemIndex, notice, generation)
            BrowseFirstScreenChainResult(truncationNotice = notice, error = null)
        } catch (t: Throwable) {
            // 失败那一路交回错误信息（没有截断提示可谈）；下拉指示器的复位由调用方统一收尾
            BrowseFirstScreenChainResult(truncationNotice = null, error = t.message ?: "加载失败")
        }
    }

    /**
     * 取够之后把位置放回去：滚动状态是从 0 起建的，列表落地不会自己回到离开前的位置，
     * 因此这里显式把容器请求回恢复索引（或下拉更新那一代回顶部）——**该不该放、放到哪**都在这里。
     *
     * [loadedItems] 是 **`Lazy` 项数**：条目 + 截断提示行（[notice] 非 null 时它占第 0 行）+ 尾部触发件行。
     */
    private fun restoreScrollPosition(restoredItemIndex: Int, notice: String?, generation: Int) {
        val loadedItems = pager.entries.size +
            (if (notice != null) 1 else 0) +
            (if (pager.hasMore) 1 else 0)
        val current = ports.currentItemIndex()
        // 下拉更新（代次非 0）完成后固定在顶部：刷新是「重新拿一份列表」，用户此刻就在顶部。
        val target = if (generation != 0) 0 else scrollRestoreTarget(restoredItemIndex, current, loadedItems)
        // 这一行也产在「不想放」的时候（`target=none`）——有它才能把「机制跑到了但决定不放」
        // 与「首屏链根本没跑到这一步」分开（口径见 `BrowseScrollPosition` 里那个拼行函数）。
        // `gen` 与 `phase=read` 同一个键：两行靠它配对（同代重跑时 `restored` 才是真正用的那个值）。
        PerfTiming.log { browseRestoreApplyLine(containerId, generation, restoredItemIndex, current, loadedItems, target) }
        if (target == null) return
        ports.requestScrollTo(target)
        // 位置**被请求放回**就是这一刻 ⇒ 通知界面把该键标成「已请求放回」（拒写窗口从此关闭）。
        // 只在 `target != null` 这一支调：判据没成立（`target=none`）时什么都没请求，标了就等于把
        // 「进屏那一下的残留读数」当成用户的位置（判据与理由见 `BrowseScrollPosition.placed`）。
        // 注意时点是**请求**、不是「真落到屏上」：`requestScrollToItem` 非挂起，真正落地在下一帧测量时
        //（仓库自有口径，见 `core/view/QuickScrollBarGeometry.kt` 的同名注释）。
        ports.onPositionPlaced()
    }
}

/**
 * 首屏链与界面之间的一处接线：链上碰 Compose 的事都在这里。判据（下限取多少、
 * 该不该放回、放到哪、代次只读一次）全在 [BrowseFirstScreenChain] 里，界面因此只剩接线与 UI 态。
 */
internal class BrowseFirstScreenChainPorts(
    /** 取数落地后读**当下**的首个可见项索引（两档同一套 `Lazy` 项坐标，见 [restoredScrollItemIndex]） */
    val currentItemIndex: () -> Int,
    /** 把位置请求回某个项索引（两档各走自己的容器，见 [scrollRestoreTarget]） */
    val requestScrollTo: (Int) -> Unit,
    /**
     * 位置**请求放回那一刻**的通知：只在 [requestScrollTo] 那一支调，界面用它关闭
     * 「这一次离场读数是不是被系统夹小的残留」的拒写窗口（`BrowseScrollPosition.placed`）。
     * 时点是请求、不是落地（`requestScrollToItem` 非挂起，落地在下一帧测量时）。
     */
    val onPositionPlaced: () -> Unit,
    /** 来源就绪、开始取数前：清掉上一代的错误与截断提示（原先那两行赋值的位置：落帧之后、取数之前） */
    val onFetchStart: () -> Unit,
)

/**
 * 一次首屏链的结果：界面要写回去的两条提示。[error] 非 null 时 [truncationNotice] 恒为 null
 *（取数失败那一路没有「这次取了多少条」可谈）。
 */
internal data class BrowseFirstScreenChainResult(val truncationNotice: String?, val error: String?)
