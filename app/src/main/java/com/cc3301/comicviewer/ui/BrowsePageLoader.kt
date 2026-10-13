package com.cc3301.comicviewer.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 浏览列表每页条数：滚到尾部时每次取这么多；首屏也要按已上屏那一帧的长度与恢复到的滚动索引一页页取够 */
internal const val BROWSE_PAGE_SIZE: Int = 200

/**
 * 浏览列表的按页取数：「全部书 / 阅读过 / 系列内」不再一次取完再上屏——
 * 首屏按已上屏那一帧的长度与恢复到的滚动索引取够页（没有帧时就是第 0 页），滚到列表尾部时追加下一页，
 * 可一直滚到底（不被 1 万条上限截断）。
 *
 * 状态放在 Compose 的 [mutableStateOf] 里，界面直接读 [entries]/[hasMore]；
 * 取数逻辑与 Compose 分开，因此「首屏取几页」「尾部触发才追加」这类行为能用假来源在单测里钉住
 * （`BrowsePageLoaderTest`）。
 *
 * **单段式取数**：本类只交出新列表——拿到 [Source.listEntriesPage] 的结果（正向）或 [Source.listEntries]
 * 的整份（反向）才上屏。没有「先拿旧快照上一帧、再原地替换」那一段：进目录时屏幕上不得先出现这一层自己的旧列表
 * （`docs/spec/browsing.md`「进目录的单段式取数」）。
 */
internal class BrowsePageLoader(
    private val source: Source?,
    private val containerId: String?,
    private val sort: SortMode,
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

    /** 同一时刻只允许一次取数在飞（尾部触发件可能连续进入组合） */
    private var loading: Boolean = false

    /**
     * 首屏：取够恢复位置所需的那一页/几页（[loadFirstPages]），一次到头、中途不落任何中间帧。
     *
     * [restoredItemIndex] = 界面这次要恢复到的那一条的索引（`Lazy` 项坐标：条目 + 截断提示行 + 尾部触发件行）；
     * 取数下限取「恢复索引 + 1」——上屏的第一帧就是新列表，列表不短于恢复位置所需，位置才放得回去
     * （没有旧帧可用当基准，界面侧的滚动状态从 0 起，取够后由 `scrollRestoreTarget` 请求回位）。
     */
    suspend fun loadFirstScreen(restoredItemIndex: Int = 0) {
        loadFirstPages(atLeast = restoredItemIndex + 1)
    }

    /**
     * 已落帧的首屏是否已经覆盖 [atLeast] 条：交接槽交过来的那份够用就不再取一次
     * （`hasMore` 为假 ⇒ 整份已在手，任何下限都算够）。
     *
     * 唯一问它的是首屏链（还要不要再取，见 [BrowseFirstScreenChain.run]）；
     * 交接那一步（[loadFirstScreenFor]）只负责把首屏 / 整份取好，不反问本判据。
     */
    fun coversItems(atLeast: Int): Boolean = loaded && (entries.size >= atLeast || !hasMore)

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
    fun showAll(entries: List<BrowseEntry>) {
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
 * 取目标层的首屏：**下探 / 返回上级的预取与首屏链共用这一处口径**——
 * 正向按需首屏（取够恢复位置所需），反向整份（方向要对整份翻转，追加页复现不了「从尾到头」）。
 */
internal suspend fun BrowsePageLoader.loadFirstScreenFor(
    source: Source,
    containerId: String?,
    sort: SortMode,
    reverse: Boolean,
    restoredItemIndex: Int = 0,
) {
    if (reverse) {
        val list = withContext(Dispatchers.IO) {
            listEntriesRememberingNames(source, containerId, sort, entryNames)
        }
        showAll(list)
    } else {
        loadFirstScreen(restoredItemIndex)
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
