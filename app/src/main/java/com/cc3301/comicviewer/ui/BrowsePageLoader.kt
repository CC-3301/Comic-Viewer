package com.cc3301.comicviewer.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 浏览列表每页条数：滚到尾部时每次取这么多；首屏也要按已上屏那一帧的长度与恢复到的滚动索引一页页取够 */
internal const val BROWSE_PAGE_SIZE: Int = 200

/**
 * 浏览取数模块：**这一层怎么取、取到哪**全在这里——首屏、分页状态、尾部续页与两条提示。
 *
 * 对外表达「给我这一层的首屏」的入口只有一个 [loadFirstScreen]：它按顺序编排整条首屏链
 *（恢复索引先于取数 · 清态先于取数 · 截断提示在取数之后 · 放回在取数之后），交回滚动目标与
 *「这一次要不要标记已放回」；界面提供本帧读到的索引与代次，并把交回的目标请求到容器
 *（接线见 `BrowserScreen`）。
 *
 * 为什么合成一个模块：这些约束**全是顺序约束**，逐条散在界面里就没有接缝——单测只能各测一段。
 * 收进来之后整条链可以用假来源一次跑完（`BrowseFirstScreenTest`），分页本身另有一份（`BrowsePageLoaderTest`）。
 *
 * 状态放在 Compose 的 [mutableStateOf] 里，界面直接读 [entries]/[hasMore]；
 * 取数逻辑与 Compose 分开，因此「首屏取几页」「尾部触发才追加」这类行为能用假来源在单测里钉住。
 *
 * **单段式取数**：本类只交出新列表——拿到 [Source.listEntriesPage] 的结果（正向）或 [Source.listEntries]
 * 的整份（反向）才上屏。没有「先拿旧快照上一帧、再原地替换」那一段：进目录时屏幕上不得先出现这一层自己的旧列表
 *（`docs/spec/browsing.md`「进目录的单段式取数」）。
 *
 * @param reverse 反向档：方向要对整份列表翻转，追加页复现不了「从尾到头」⇒ 首屏整份取完（按需加载只在正向档）；
 * 也不放回位置——整份上屏后列表不比离开前短，放回判据（位置确实退到了恢复索引之前）在这里不适用。
 */
internal class BrowsePageLoader(
    /**
     * 本层的来源：异步解析，解析出来之前为 null——[loadFirstScreen] 那一刻为 null 就整条链都不跑。
     * 每次取数都现读它。
     */
    var source: Source?,
    private val containerId: String?,
    private val sort: SortMode,
    private val reverse: Boolean = false,
    /**
     * 会话状态的条目名缓存（`SessionState.entryNames`）：枚举结果落进它，Komga 的标题靠它。
     */
    val entryNames: MutableMap<String, String>,
    private val pageSize: Int = BROWSE_PAGE_SIZE,
) {
    /** 已加载的条目（来源序，未做展示层方向翻转）；[loaded] 为假时不可信（还没落过帧） */
    var entries: List<BrowseEntry> by mutableStateOf(emptyList())
        private set

    /**
     * 是否已落过帧（首屏取够 / 交接槽交过来的那一帧 / 反向档的整份）：界面据此区分「还没加载」（显示「加载中」）
     * 与「已加载但这一层是空的」（显示空态）——两者都是空列表，只能靠这个标志分开。
     */
    var loaded: Boolean by mutableStateOf(false)
        private set

    /**
     * 后面还有没有下一页：界面据此在尾部挂「取下一页」的触发件。
     *
     * **为什么叫 `hasMore` 而不是 `hasNext`**：它与
     * [com.cc3301.comicviewer.core.source.BrowseEntryPage.hasNext] **不是同一个量**——来源的 `hasNext` 是「服务器
     * 说还有下一页」，本字段还叠了「那一页非空」的终止规则（见 [loadNextPage] 与 [loadFirstPages]）。
     * 页 DTO 一侧已统一成 `hasNext`（[com.cc3301.comicviewer.ui.PathPickerPage] 与 `BrowseEntryPage` 同形），
     * 本字段作为**界面状态**保留这个名字，正是为了不让两个不同的量共用一个名字。
     */
    var hasMore: Boolean by mutableStateOf(false)
        private set

    /** 已加载条目是按哪个排序类别列出来的（滚动复位键用，见 [browseScrollResetKey]）；还没落过帧为 null */
    var mode: SortMode? by mutableStateOf(null)
        private set

    /** 下一页页码（[hasMore] 为假时无意义）：同时是尾部触发件 effect 的键——它一变就再取一页 */
    var nextPage: Int by mutableStateOf(0)
        private set

    /** 首屏取数失败的提示（界面就地重试）；来源未就绪时不动它，成功那一路为 null */
    var error: String? by mutableStateOf(null)
        private set

    /**
     * 取数上限截断提示：Komga 层的条目数撞到服务端分页上限时非 null，列表第 0 行给一行看得见的提示。
     * **取数之后**读一次来源的记账（[Source.listTruncationNotice]：纯内存读、不发起任何请求）。
     */
    var truncationNotice: String? by mutableStateOf(null)
        private set

    /** 同一时刻只允许一次取数在飞（尾部触发件可能连续进入组合） */
    private var loading: Boolean = false

    /**
     * 给我这一层的首屏：取够恢复位置所需（反向档整份取完）、按顺序编排整条首屏链，交回滚动目标。
     *
     * 四条顺序不变量（`BrowseFirstScreenTest` 在接缝上钉住）：
     * 1. **恢复索引先于取数定下来**，且同代只读一次（[RestoredScrollIndex]）：来源异步解析会让本入口重跑，
     *    第二次读到的是上一次取数之后的索引；
     * 2. **清态先于取数**：[error] 与 [truncationNotice] 在来源就绪之后、取数之前清掉；
     * 3. **截断提示在取数之后**（对刚跑完这次取数的记账）；
     * 4. **放回在取数之后**：列表落地才谈得上把位置放回去。
     *
     * 交接槽交过来的那份首屏（返回上级）已经够用时不再取一次（[coversItems]）：屏幕上早就有内容了，
     * 再取一次只是白花一次列目录。交接预取（`prepareLayerHandoff`）走的是同一个入口，但传
     * `restorePosition = false`（只要第 0 页，不做放回）。
     *
     * @param generation 本次取数的代次（下拉更新 / 重试的 `reloadTick`）：换一代就重读当下索引
     * @param restoredIndex 本代恢复索引的持有者（同代只读一次，见 [RestoredScrollIndex]）
     * @param restoredIndexNow 本帧**当下**读到的恢复索引（`Lazy` 项坐标）；代次 0 时调用方已与
     *「离开这一屏那一刻记下的未夹值」取较大者（[unclippedRestoredScrollIndex]）
     * @param currentItemIndex 调用方当下读到的首个可见项索引（放回判据的输入，与恢复索引同一套 `Lazy` 项坐标）
     * @param restorePosition 要不要放回位置：交接预取那一侧为假——目标层首帧自己会走一遍首屏链，
     * 那时才谈得上放回，预取这一侧也不产 `phase=apply` 那一行打点
     * @return null = 来源还没解析出来：什么都没取、也没上屏，调用方据此跳过收尾
     *（不清提示、不复位下拉指示器）
     */
    suspend fun loadFirstScreen(
        generation: Int = 0,
        restoredIndex: RestoredScrollIndex,
        restoredIndexNow: Int = 0,
        currentItemIndex: Int = 0,
        restorePosition: Boolean = true,
    ): BrowseFirstScreenResult? {
        // 不变量 1：恢复索引先定下来，且同代只读一次——来源解析会让本入口重跑，第二次读到的是上一次取数之后的索引。
        val restoredItemIndex = restoredIndex.valueFor(generation, restoredIndexNow)
        // 来源守卫：还没解析出来就什么都没得取（本模块不产能），界面态也不动（清态点在守卫之后）。
        val src = source ?: return null
        // 不变量 2：清态在取数之前。
        error = null
        truncationNotice = null
        return try {
            // 交接槽交过来的那份首屏（返回上级）够用就不再取一次：屏幕上早就有内容了，
            // 再取一次只是白花一次列目录（这一层在切换之前已经真列过一次）。
            if (!coversItems(restoredItemIndex + 1)) fetchFirstScreen(src, restoredItemIndex)
            // 不变量 3：取数落地后才问截断提示——它是对刚跑完这次取数的记账，读一次既交回界面态
            //（列表第 0 行），也给「放回」算上它占的那一行。
            val notice = src.listTruncationNotice(containerId, sort)
            truncationNotice = notice
            // 不变量 4：放回在取数之后；反向档（见构造参数 [reverse]）与不放回那一侧都不放回。
            val target = if (reverse || !restorePosition) {
                null
            } else {
                restoreTarget(restoredItemIndex, notice, currentItemIndex, generation)
            }
            BrowseFirstScreenResult.Loaded(scrollTarget = target, markPlaced = target != null)
        } catch (t: Throwable) {
            // 取消也算「这一次没取到」：界面那一侧照旧写错误、照旧收尾（复位下拉指示器）；
            // 交接预取那一侧另行按取消处理，见 `prepareLayerHandoff`。
            error = t.message ?: "加载失败"
            BrowseFirstScreenResult.Failed
        }
    }

    /**
     * 取这一层的首屏：**交接预取与进屏首屏共用这一处口径**——正向按需（取够恢复位置所需），
     * 反向整份（方向要对整份翻转，追加页复现不了「从尾到头」）。
     */
    private suspend fun fetchFirstScreen(src: Source, restoredItemIndex: Int) {
        if (reverse) {
            val list = withContext(Dispatchers.IO) {
                listEntriesRememberingNames(src, containerId, sort, entryNames)
            }
            showAll(list)
        } else {
            // 取数下限 = 恢复索引 + 1：上屏的第一帧就是新列表，列表不短于恢复位置所需，位置才放得回去
            //（没有旧帧可用当基准，界面侧的滚动状态从 0 起，取够后由 [restoreTarget] 请求回位）。
            loadFirstPages(atLeast = restoredItemIndex + 1)
        }
    }

    /**
     * 取够之后该不该把位置放回去：滚动状态是从 0 起建的，列表落地不会自己回到离开前的位置，
     * 因此这里显式算出要请求回的那一项（下拉更新那一代则回顶部）——**该不该放、放到哪**都在这里。
     *
     * [notice] 是刚问到的截断提示：**`Lazy` 项数**按条目 + 截断提示行（非 null 时它占第 0 行）+ 尾部触发件行算。
     */
    private fun restoreTarget(
        restoredItemIndex: Int,
        notice: String?,
        currentItemIndex: Int,
        generation: Int,
    ): Int? {
        val loadedItems = entries.size +
            (if (notice != null) 1 else 0) +
            (if (hasMore) 1 else 0)
        // 下拉更新 / 重试（代次非 0）完成后固定在顶部：刷新是「重新拿一份列表」，用户此刻就在顶部。
        val target = if (generation != 0) 0 else scrollRestoreTarget(restoredItemIndex, currentItemIndex, loadedItems)
        // 这一行也产在「不想放」的时候（`target=null`）——有它才能把「机制跑到了但决定不放」
        // 与「首屏链根本没跑到这一步」分开（口径见 `BrowseScrollPosition` 里那个拼行函数）。
        // `gen` 与 `phase=read` 同一个键：两行靠它配对（同代重跑时 `restored` 才是真正用的那个值）。
        PerfTiming.log {
            browseRestoreApplyLine(containerId, generation, restoredItemIndex, currentItemIndex, loadedItems, target)
        }
        return target
    }

    /**
     * 已落帧的首屏是否已经覆盖 [atLeast] 条：交接槽交过来的那份够用就不再取一次
     * （`hasMore` 为假 ⇒ 整份已在手，任何下限都算够）。
     */
    private fun coversItems(atLeast: Int): Boolean = loaded && (entries.size >= atLeast || !hasMore)

    /**
     * 第二段：从第 0 页连续取，直到**取够 [atLeast] 条**或来源说后面没有了为止。
     *
     * 为什么按 [atLeast] 取而不是只取一页：恢复的滚动索引可能落在很后面（深目录），
     * 只取第 0 页（[pageSize] 条）的话索引就被夹到已加载末尾——大目录（>200 条）新一屏只剩第 0 页，
     * 要反复「滚到底 → 续页」才回得去。
     * 取够这一段再一次性上屏（列表因此不会中途变长——**除非中途遇到空页**：空页当终止，
     * 此时 [entries] 可能短于 [atLeast]——恢复位置在那一段仍会丢）。
     *
     * 请求数有界：上限 = ⌈[atLeast] / 一次请求条数⌉（一个）。**一次请求条数不再固定是 [pageSize]**：
     * 默认实现下每一页都是一次全量重列，1578 条的层逐页问就是 8 次；
     * 因此按「首屏要多少条」一次要下来，只夹到 [Source.maxPageSize]。
     * [atLeast] 是**下限**——它可以超过来源已给过的列表长度（恢复索引比这一层长时：250 条的层 + 恢复索引 5000
     * ⇒ [atLeast] = 5001），此时由来源说「没有下一页」自然停，不会把页要满。
     * 空页当终止（正常服务端不会空页还说有下一页，见 [loadNextPage]）。
     */
    private suspend fun loadFirstPages(atLeast: Int) {
        val src = source ?: return
        val pagesNeeded = ((atLeast + pageSize - 1) / pageSize).coerceAtLeast(1)
        val collected = mutableListOf<BrowseEntry>()
        // 一次问够：不固定按 [pageSize] 逐页问，而是把首屏要的条数**一次**要下来。
        // 默认实现（`Source.listEntriesPage` = 取全量再切片）下**每一页都是一次全量重列**：1578 条的层
        // 按 200 一页问就是 8 次，返回浏览页时正好盖住整个过渡窗口（8 段 15~98ms）。一次要够 = 一次。
        // `want` 恒是 [pageSize] 的整数倍，且页码也以 `want` 为单位（与 `size` 同一套坐标）——
        // 因此 [nextPage] 仍可以按 `collected.size / pageSize` 算。
        // `coerceAtLeast(pageSize)` 是「夹完不能小于一页」的兜底：它隐含要求
        // `Source.maxPageSize >= pageSize`（见该属性的前置条件）——
        // 小于一页的来源在这里会把 `size` 顶到 [pageSize]（比来源上限大），现网四个来源都不命中。
        val want = minOf(
            pagesNeeded * pageSize,
            (src.maxPageSize / pageSize * pageSize).coerceAtLeast(pageSize),
        )
        // 请求数上限：每个请求覆盖 `want` 条源坐标，取够 [atLeast] 需要 ⌈atLeast/want⌉ 个（不低于 1）。
        val maxRequests = ((atLeast + want - 1) / want).coerceAtLeast(1)
        // 注意：这个局部量**不是**来源的 `hasNext`（那个只表示「服务器说还有下一页」），
        // 它已经叠了「空页当终止」⇒ 名字跟着状态字段 [hasMore] 走，与来源的 `hasNext` 不同名。
        var hasMoreAfterThisPage = false
        var request = 0
        while (request < maxRequests) {
            val result = fetchPage(src, page = request, size = want)
            collected += result.entries
            hasMoreAfterThisPage = result.hasNext && result.entries.isNotEmpty()
            request++
            if (!hasMoreAfterThisPage || collected.size >= atLeast) break
        }
        entries = collected
        hasMore = hasMoreAfterThisPage
        // 页索引以 [pageSize] 为单位（[loadNextPage] 也按 [pageSize] 取）；来源按 `size` 给满页时
        // 它就是已取页数，给不满（仍说 hasNext）时向上取整，不会把已取过的那一页再要一遍。
        nextPage = (collected.size + pageSize - 1) / pageSize
        mode = sort
        loaded = true
    }

    /**
     * 滚到尾部：取下一页并追加；一次取数在飞时忽略（不重复要同一页）。
     * **空页当终止**：正常服务端不会空页还说有下一页，而尾部触发件以页码为键——
     * `hasMore` 恒真时它每追加一页就再要一页（空页 + hasMore 恒真 = 无限取数）。
     */
    suspend fun loadNextPage() {
        val src = source ?: return
        if (!hasMore || loading) return
        loading = true
        try {
            val page = fetchPage(src, nextPage)
            entries = entries + page.entries
            hasMore = page.hasNext && page.entries.isNotEmpty()
            nextPage += 1
        } finally {
            loading = false
        }
    }

    /**
     * 快速定位滑条的分母（二选一取「已加载条数」）：按需加载的层里
     * 滑条表示的是**已加载范围内**的位置——分母 = 已加载条目数（不是该层总数：总数要按需加载才知道，
     * 拖到未加载的位置也没有内容可落）。
     *
     * [extraRows] 是列表里与条目同级的附加行数（截断提示 / 尾部触发件）：带上它，滑条的行索引
     * 与 Lazy 列表的行索引保持同一套坐标（否则拖动定位会偏行）。
     */
    fun sliderItemCount(extraRows: Int): Int = entries.size + extraRows

    /**
     * 落整份枚举：**反向档**专用——方向要对整份列表翻转，追加页复现不了「从尾到头」，
     * 因此反向档仍一次取完（不切首屏、[hasMore] 恒假）。
     */
    private fun showAll(entries: List<BrowseEntry>) {
        this.entries = entries
        hasMore = false
        nextPage = 0
        mode = sort
        loaded = true
    }

    /** 一页 + 条目名回填（Komga 的条目 id 只有 UUID，标题靠列表见过一次记下来） */
    private suspend fun fetchPage(source: Source, page: Int, size: Int = pageSize): BrowseEntryPage =
        withContext(Dispatchers.IO) {
            val result = source.listEntriesPage(containerId, sort, page, size)
            BrowseEntryPage(entries = rememberEntryNames(result.entries, entryNames), hasNext = result.hasNext)
        }
}

/**
 * 一次首屏落地的结果：界面接着要做的只有两件事——把位置请求回 [scrollTarget]（非 null 时），
 * 并按 [markPlaced] 决定要不要标记「位置已放回」。
 */
internal sealed interface BrowseFirstScreenResult {
    /**
     * 这一次要把位置请求回哪一项（`Lazy` 项坐标）。null = 不放回：位置还在 / 这一层没那么长 /
     * 反向档 / 下拉更新那一代已经回顶部 / 预取那一侧 / 取数没成。
     */
    val scrollTarget: Int?

    /**
     * 这一次要不要通知位置模块「位置已放回」（`BrowseScrollPosition.placed`）。判据在模块里
     *（真的请求了放回才标记），界面照着做，不再自己从 [scrollTarget] 是不是 null 反推：
     * 什么都没请求还标了，就等于把进屏那一下的残留读数当成用户的位置。
     */
    val markPlaced: Boolean

    /** 取数落地 */
    data class Loaded(override val scrollTarget: Int?, override val markPlaced: Boolean) : BrowseFirstScreenResult

    /** 取数没成（取消也算没取到）：界面照旧读 `error` 显示，交接预取据此不装槽 */
    data object Failed : BrowseFirstScreenResult {
        override val scrollTarget: Int? = null
        override val markPlaced: Boolean = false
    }
}

/**
 * 快速定位滑条分母的取值 lambda：返回的 lambda **每次读当前 pager**。
 *
 * 为什么必须经 [State] 而不能按值捕获 pager：这个 lambda 只在界面的 `remember(listState)` 求值那一刻
 * 创建一次，而**下拉更新**会换一个新 [BrowsePageLoader] 实例；`listState` 的键（`browseScrollResetKey`）
 * 不含 pager 实例，刷新前后逐字相等 ⇒ `listState` 不换实例、闭包不重建。按值捕获的话分母会永远停在
 * 旧 loader 的 `entries.size`/`hasMore`，而且不自愈。
 *
 * [extraRows] 是列表里与条目同级的附加行数（截断提示 / 尾部触发件），与 Lazy 行坐标同一套。
 */
internal fun browseSliderItemCount(
    pager: State<BrowsePageLoader>,
    extraRows: () -> Int,
): () -> Int = { pager.value.sliderItemCount(extraRows()) }
