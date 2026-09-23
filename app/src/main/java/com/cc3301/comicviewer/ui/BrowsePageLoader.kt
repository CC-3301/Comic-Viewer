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

/** 浏览列表每页条数（票 #119 步骤 3）：滚到尾部时每次取这么多；首屏也要按已上屏那一帧的长度与恢复到的滚动索引一页页取够（票 #125 P1-1 + 票 #124） */
internal const val BROWSE_PAGE_SIZE: Int = 200

/**
 * 浏览列表的按页取数（票 #119 步骤 3）：「全部书 / 阅读过 / 系列内」不再一次取完再上屏——
 * 首屏按已上屏那一帧的长度与恢复到的滚动索引取够页（没有帧时就是第 0 页；票 #125 P1-1 + 票 #124），滚到列表尾部时追加下一页，
 * 可一直滚到底（不被 1 万条上限截断）。
 *
 * 状态放在 Compose 的 [mutableStateOf] 里，界面直接读 [entries]/[hasMore]；
 * 取数逻辑与 Compose 分开，因此「首屏取几页」「尾部触发才追加」这类行为能用假来源在单测里钉住
 * （`BrowsePageLoaderTest`）。
 *
 * 与快照的关系（票面第 3 条约束：别把分页做成第二个数据来源）：[loadFirstScreen] 第一段先把
 * 已有快照（[Source.snapshotEntries] 的落盘快照 / 界面效果期落的会话快照，0 请求）当首帧上屏，
 * 第二段再按**它的长度**与**恢复到的滚动索引**里的较大者取够页替换它（票 #125 P1-1 + 票 #124）——
 * 取数只有 [Source.listEntriesPage] 这一条路，快照只决定「要取够多少」，不产能。
 */
internal class BrowsePageLoader(
    private val source: Source?,
    private val containerId: String?,
    private val sort: SortMode,
    private val pageSize: Int = BROWSE_PAGE_SIZE,
) {
    /** 已加载的条目（来源序，未做展示层方向翻转）；[loaded] 为假时不可信（还没落过帧） */
    var entries: List<BrowseEntry> by mutableStateOf(emptyList())
        private set

    /**
     * 是否已落过帧（快照 / 第 0 页 / 整份）：界面据此区分「还没加载」（显示「加载中…」）
     * 与「已加载但这一层是空的」（显示空态）——两者都是空列表，只能靠这个标志分开。
     */
    var loaded: Boolean by mutableStateOf(false)
        private set

    /** 后面还有没有下一页：界面据此在尾部挂「取下一页」的触发件 */
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
     * 效果期的**落帧与来源守卫**（票 #124 r2）：先落会话内快照帧，再把来源交回调用方判就绪。
     *
     * **顺序就是契约**：`source` 由 `rememberConnectionSource` 在 IO 上异步解析（首帧必为 null），
     * 而会话内快照来自会话槽位（同步可读、不等解析）。落帧若排在来源守卫（`source ?: return`）之后，
     * 来源解析的整个窗口里 [loaded] 都是 false，界面走 `list == null ->「加载中…」`——
     * SPEC:174「从阅读器返回浏览页时列表**立即可见**、不闪『加载中…』」就不成立（票 #124 r1 的 P1 回归）。
     *
     * 返回 [source]：为 null（解析中）时只落帧、不取数，调用方据此跳过取数后的收尾
     * （截断提示 / 下拉指示器复位）。
     */
    fun landSnapshotFrame(source: Source?, preloaded: List<BrowseEntry>?): Source? {
        preloaded?.let { showSnapshot(it) }
        return source
    }

    /**
     * 首屏（票 #75 两段式 + 票 #119 步骤 3 增量加载，两段并存）：
     *
     * 第一段落已有快照（[Source.snapshotEntries]：文件源是落盘快照，0 次列目录/探测；Komga 是会话内列表——内存、不落盘、不含 mtime，0 请求，票 #123）当首帧，
     * 第二段按这一帧的长度与 [restoredItemIndex] 里的较大者取够页再替换（[loadFirstPages]）。首帧因此不必等一次整层枚举
     * （冷启动/进目录不再先停「加载中…」），后续滚到底再按页追加（[loadNextPage]）。
     *
     * [restoredItemIndex] = 界面这次要恢复到的那一条的索引（条目坐标）；[onSnapshotFrame] 在第一段落屏后
     * 回调（界面据此刷新截断提示等）；没有快照时只有第二段。
     *
     * 取数下限取「快照长度」与「恢复索引 + 1」的较大者（票 #124，见 [loadFirstPages]）：
     * 直取档的会话内列表只含第 0 页（票 #119 约束），只按快照长度取够的话，滚深之后重建的列表短于
     * 恢复位置所需、滚动索引被列表夹到已加载末尾（位置丢失）。
     */
    suspend fun loadFirstScreen(restoredItemIndex: Int = 0, onSnapshotFrame: () -> Unit = {}) {
        val src = source ?: return
        withContext(Dispatchers.IO) { src.snapshotEntries(containerId, sort) }
            ?.let { snapshot ->
                showSnapshot(snapshot)
                onSnapshotFrame()
            }
        // 界面上可能已经先落过快照帧（`BrowserScreen` 效果期落的会话快照）：两处取同一个基准。
        // 下限取「快照长度」与「恢复索引 + 1」的较大者（票 #124）：直取档的会话内列表只含第 0 页
        //（票 #119 约束），只按快照长度取够的话，滚深之后重建的列表短于恢复位置所需。
        loadFirstPages(atLeast = maxOf(if (loaded) entries.size else 0, restoredItemIndex + 1))
    }

    /**
     * 第二段：从第 0 页连续取，直到**取够 [atLeast] 条**或来源说后面没有了为止（票 #125 P1-1）。
     *
     * 为什么按 [atLeast] 取而不是只取一页：已经上屏的那一帧（快照）就是**上次上过屏的列表**，
     * 恢复的滚动索引必落在它范围内。只取第 0 页（[pageSize] 条）替换它，索引就被夹到已加载末尾
     * ——大目录（>200 条）从阅读器返回只剩第 0 页，要反复「滚到底 → 续页」才回得去。
     * 取够这一段再一次性替换（列表因此不会变短——**除非中途遇到空页**：空页当终止，
     * 此时 [entries] 可能短于 [atLeast]，即本票现象在那一段的窄化残留）。
     *
     * 请求数有界：上限 = ⌈[atLeast] / [pageSize]⌉ 页，而 [atLeast] 是**下限**——它可以超过来源已给过的
     * 列表长度（恢复索引比这一层长时：250 条的层 + 恢复索引 5000 ⇒ [atLeast] = 5001），此时由来源说
     * 「没有下一页」自然停（见循环里的 [hasNext]），不会按上限把页要满。
     * 空页当终止（正常服务端不会空页还说有下一页，见 [loadNextPage]）。
     */
    private suspend fun loadFirstPages(atLeast: Int) {
        val src = source ?: return
        val pagesNeeded = ((atLeast + pageSize - 1) / pageSize).coerceAtLeast(1)
        val collected = mutableListOf<BrowseEntry>()
        var page = 0
        var hasNext = false
        while (page < pagesNeeded) {
            val result = fetchPage(src, page)
            collected += result.entries
            hasNext = result.hasNext && result.entries.isNotEmpty()
            page++
            if (!hasNext) break
        }
        entries = collected
        hasMore = hasNext
        nextPage = page
        mode = sort
        loaded = true
    }

    /**
     * 滚到尾部：取下一页并追加；一次取数在飞时忽略（不重复要同一页）。
     * **空页当终止**（票 #125 P1-2）：正常服务端不会空页还说有下一页，而尾部触发件以页码为键——
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
     * 快速定位滑条的分母（票 #119 修复轮口径，二选一取「已加载条数」）：按需加载的层里
     * 滑条表示的是**已加载范围内**的位置——分母 = 已加载条目数（不是该层总数：总数要按需加载才知道，
     * 拖到未加载的位置也没有内容可落）。
     *
     * [extraRows] 是列表里与条目同级的附加行数（截断提示 / 尾部触发件）：带上它，滑条的行索引
     * 与 Lazy 列表的行索引保持同一套坐标（否则拖动定位会偏行）。
     */
    fun sliderItemCount(extraRows: Int): Int = entries.size + extraRows
    /**
     * 落已有快照（票 #75）：**只当首帧**，不参与取数（[hasMore] 置假，第 0 页落地前不触发下一页）。
     * **整份上屏、不切首屏长度**（票 #125 P1-1）：快照就是上次上屏的那份列表，切到 [pageSize] 条会让
     * 恢复的滚动索引落到已加载之外；[loadFirstScreen] 第二段按它的长度（与恢复到的滚动索引里的较大者）
     * 取够页再替换。
     */
    fun showSnapshot(entries: List<BrowseEntry>) {
        this.entries = entries
        hasMore = false
        nextPage = 0
        mode = sort
        loaded = true
    }

    /**
     * 落整份枚举（票 #119 步骤 3）：**反向档**专用——方向要对整份列表翻转，追加页复现不了「从尾到头」，
     * 因此反向档仍一次取完（不切首屏、[hasMore] 恒假）。
     */
    fun showAll(entries: List<BrowseEntry>) {
        this.entries = entries
        hasMore = false
        nextPage = 0
        mode = sort
        loaded = true
    }

    /** 一页 + 条目名回填（票 #13：Komga 的条目 id 只有 UUID，标题靠列表见过一次记下来） */
    private suspend fun fetchPage(source: Source, page: Int): BrowseEntryPage = withContext(Dispatchers.IO) {
        val result = source.listEntriesPage(containerId, sort, page, pageSize)
        BrowseEntryPage(entries = rememberEntryNames(result.entries), hasNext = result.hasNext)
    }
}

/**
 * 快速定位滑条分母的取值 lambda（票 #119 修复轮）：返回的 lambda **每次读当前 pager**。
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
