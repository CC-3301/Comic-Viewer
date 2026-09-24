package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source

/**
 * 浏览页首屏链的编排（票 #134）：**会话快照帧 → 首屏取数下限 → 取够后把滚动位置放回去**。
 *
 * 为什么要有它：这三件事此前散在 `BrowserScreen` 的一个 `LaunchedEffect` 里，而它们之间的约束
 * **全是顺序约束**——单测只能各测一段（`BrowsePageLoaderTest` 测取数、`BrowseScrollRestoreTest` 测两个纯函数），
 * 合起来的时序没有接缝（票 #123 / #124 / #125 连续三张改的都是同一个 effect）。链收到这里之后，
 * 界面只提供 [ports] 那三件真正碰 Compose 的事，整条链可以用假来源一次跑完（`BrowseFirstScreenTest`）。
 *
 * 三条时序不变量（原先只活在 `BrowserScreen` 的注释里，本票把它们搬成这个类的契约）：
 * 1. **恢复索引先于任何一帧落屏**（[run] 的第一件事）：A4 之后浏览页首帧就是那份**短**会话快照，
 *    `Lazy` 列表按它测量一次就把恢复索引夹到已加载末尾，因此索引必须在落帧之前定下来；
 * 2. **快照帧先于来源守卫**（同 [run]）：`source` 由 `rememberConnectionSource` 在 IO 上异步解析（首帧必为 null），
 *    而快照是会话槽位的同步内存读（[BrowsePageLoader.landSnapshotFrame]）——帧排在守卫之后，
 *    来源解析的整个窗口里界面都走「加载中…」（SPEC「同步快照访问器」就不成立）；
 * 3. **快照只当下限、不产能**：取数只有 [Source.listEntriesPage] 一条路（[BrowsePageLoader.loadFirstScreen]），
 *    快照只决定「要取够多少条」，因此第二段落地后列表只会变长（空页终止那一段除外）。
 *
 * @param pager 首屏落进的取数器（界面读它渲染，分页状态也在那里）
 * @param source 本页来源；null = 还没解析出来（只落帧，[run] 返回 null）
 * @param reverse 反向档：方向要对整份列表翻转，追加页复现不了「从尾到头」⇒ 整份取完（按需加载只在正向档，票 #119 步骤 3）、
 * 也不放回位置（整份落屏，恢复索引不会被短帧夹过）
 * @param restoredIndex 恢复索引的持有者（同代只读一次，见 [RestoredScrollIndex]）
 * @param preloaded 界面同步读到的会话快照：与 [BrowsePageLoader] 构造期落的那份同一个值，这里补落
 *「来源解析完成后快照才到」的那一路
 */
internal class BrowseFirstScreen(
    private val pager: BrowsePageLoader,
    private val source: Source?,
    private val containerId: String?,
    private val sort: SortMode,
    private val reverse: Boolean,
    private val restoredIndex: RestoredScrollIndex,
    private val preloaded: List<BrowseEntry>?,
    private val ports: BrowseFirstScreenPorts,
) {
    /**
     * 跑一次首屏链。
     *
     * @param generation 本次取数的代次（下拉更新 / 重试的 `reloadTick`）：换一代就重读当下索引
     *（界面重读、这里只负责「同代不覆盖」，见 [RestoredScrollIndex.valueFor]）
     * @param restoredIndexNow 本帧**当下**读到的恢复索引（`Lazy` 项坐标）；代次 0 时调用方已与
     *「离开这一屏那一刻记下的未夹值」取较大者（[unclippedRestoredScrollIndex]）
     * @return null = 来源还没解析出来：只落了快照帧，调用方据此跳过收尾（不清提示、不复位下拉指示器）
     */
    suspend fun run(generation: Int, restoredIndexNow: Int): BrowseFirstScreenResult? {
        // 不变量 1：恢复索引先定下来，且同代只读一次——来源解析会让本链重跑，第二次读到的是被短帧夹过的索引。
        val restoredItemIndex = restoredIndex.valueFor(generation, restoredIndexNow)
        // 不变量 2：帧先落，来源守卫在后（守卫为 null 时只落帧、连清态都不做）。
        val src = pager.landSnapshotFrame(source, preloaded) ?: return null
        ports.onFetchStart()
        return try {
            if (reverse) {
                // 反向档：第一段快照帧（整份上屏）→ 第二段整层枚举原地替换；方向翻转在展示层（`rememberShownEntries`）
                val list = listEntriesTwoPhaseRememberingNames(src, containerId, sort) { pager.showSnapshot(it) }
                pager.showAll(list)
            } else {
                // 正向档：第二段按「已上屏那一帧的长度」与「恢复索引 + 1」里的较大者取够再替换（不变量 3：快照是下限）
                pager.loadFirstScreen(restoredItemIndex)
            }
            // 取数落地后才问截断提示（票 #119）：纯内存读、不发起任何请求，是对刚跑完这次取数的记账；
            // 读一次既交回界面态（列表第 0 行），也给「放回」算上它占的那一行。
            val notice = src.listTruncationNotice(containerId, sort)
            // 放回位置只发生在正向档（见 [reverse] 的 KDoc：反向档整份落屏，索引没被夹过）
            if (!reverse) restoreScrollPosition(restoredItemIndex, notice)
            BrowseFirstScreenResult(truncationNotice = notice, error = null)
        } catch (t: Throwable) {
            // 失败那一路交回错误信息（没有截断提示可谈）；下拉指示器的复位由调用方统一收尾
            BrowseFirstScreenResult(truncationNotice = null, error = t.message ?: "加载失败")
        }
    }

    /**
     * 取够之后把位置放回去（票 #124 r2）：短帧测量已把索引夹到已加载末尾，列表涨长不会自己回去，
     * 因此这里显式把容器请求回恢复索引——**该不该放、放到哪**都在 [scrollRestoreTarget] 里。
     *
     * [loadedItems] 是 **`Lazy` 项数**：条目 + 截断提示行（[notice] 非 null 时它占第 0 行）+ 尾部触发件行。
     */
    private fun restoreScrollPosition(restoredItemIndex: Int, notice: String?) {
        val loadedItems = pager.entries.size +
            (if (notice != null) 1 else 0) +
            (if (pager.hasMore) 1 else 0)
        val target = scrollRestoreTarget(restoredItemIndex, ports.currentItemIndex(), loadedItems) ?: return
        ports.requestScrollTo(target)
    }
}

/**
 * 首屏链与界面之间的一处接线（票 #134）：链上真正碰 Compose 的只有这三件事。判据（下限取多少、
 * 该不该放回、放到哪、代次只读一次）全在 [BrowseFirstScreen] 里，界面因此只剩接线与 UI 态。
 */
internal class BrowseFirstScreenPorts(
    /** 取数落地后读**当下**的首个可见项索引（两档同一套 `Lazy` 项坐标，见 [restoredScrollItemIndex]） */
    val currentItemIndex: () -> Int,
    /** 把位置请求回某个项索引（两档各走自己的容器，见 [scrollRestoreTarget]） */
    val requestScrollTo: (Int) -> Unit,
    /** 来源就绪、开始取数前：清掉上一代的错误与截断提示（原先那两行赋值的位置：落帧之后、取数之前） */
    val onFetchStart: () -> Unit,
)

/**
 * 一次首屏链的结果：界面要写回去的两条提示。[error] 非 null 时 [truncationNotice] 恒为 null
 *（取数失败那一路没有「这次取了多少条」可谈）。
 */
internal data class BrowseFirstScreenResult(val truncationNotice: String?, val error: String?)
