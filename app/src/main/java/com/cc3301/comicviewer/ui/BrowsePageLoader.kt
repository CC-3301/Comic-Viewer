package com.cc3301.comicviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 浏览列表每页条数（票 #119 步骤 3）：首屏只取这么多，滚到尾部再取下一页 */
internal const val BROWSE_PAGE_SIZE: Int = 200

/**
 * 浏览列表的按页取数（票 #119 步骤 3）：「全部书 / 阅读过 / 系列内」不再一次取完再上屏——
 * 首屏只取第 0 页，滚到列表尾部时追加下一页，可一直滚到底（不被 1 万条上限截断）。
 *
 * 状态放在 Compose 的 [mutableStateOf] 里，界面直接读 [entries]/[hasMore]；
 * 取数逻辑与 Compose 分开，因此「首屏只请求一页」这类行为能用假来源在单测里钉住
 * （`BrowsePageLoaderTest`）。
 *
 * 与快照的关系（票面第 3 条约束：别把分页做成第二个数据来源）：快照只落**首屏**——
 * [showSnapshot] 把整份旧枚举切到首屏长度当首帧（0 请求）显示；
 * 真正的取数只有 [loadFirstPage]/[loadNextPage] 这一条路（[Source.listEntriesPage]）。
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

    /** 首屏：取第 0 页 */
    suspend fun loadFirstPage() {
        val src = source ?: return
        val page = fetchPage(src, 0)
        entries = page.entries
        hasMore = page.hasNext
        nextPage = 1
        mode = sort
        loaded = true
    }

    /** 滚到尾部：取下一页并追加；一次取数在飞时忽略（不重复要同一页） */
    suspend fun loadNextPage() {
        val src = source ?: return
        if (!hasMore || loading) return
        loading = true
        try {
            val page = fetchPage(src, nextPage)
            entries = entries + page.entries
            hasMore = page.hasNext
            nextPage += 1
        } finally {
            loading = false
        }
    }

    /**
     * 落已有快照（票 #75）：**只当首帧**，不参与取数（[hasMore] 置假，第 0 页落地前不触发下一页）。
     * 只取前 [pageSize] 条（票面约束：快照只缓存首屏）——整份旧枚举上屏会在第 0 页落地那一帧
     * 把列表变短、滚动位置跟着跳；切到首屏长度就与第 0 页对齐。
     */
    fun showSnapshot(entries: List<BrowseEntry>) {
        this.entries = entries.take(pageSize)
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
