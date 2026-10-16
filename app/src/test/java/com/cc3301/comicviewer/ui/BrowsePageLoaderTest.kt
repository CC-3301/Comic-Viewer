package com.cc3301.comicviewer.ui

import androidx.compose.runtime.mutableStateOf
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.sliceEntryPage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览列表的按页取数（**单段式**）：首屏一次取够「恢复到的位置 + 1」条才上屏，滚到尾部才追加下一页。
 *
 * 判别力：把首屏改回「整层取完」（或去掉 [BrowsePageLoader.loadNextPage] 的追加）——
 * 「首屏只请求第 0 页」「尾部触发才请求第 2 页」的断言即变红；把「先落一帧旧内容」加回来——
 * 「取数期间一帧都不上屏」的断言即变红。
 */
class BrowsePageLoaderTest {

    /** 会话状态的条目名缓存（生产那份由 `MainActivity` 提供）：枚举时回填进它 */
    private val entryNames = ConcurrentHashMap<String, String>()

    /** 只实现取数：记录每次按页取数的页码与条数，并按 [size] 切出一份合成条目表 */
    private class RecordingSource(
        private val total: Int,
        /** 每次按页取数时回调一次（页码）：用来在断言里读「取数那一刻屏幕上的状态」 */
        private val onPageRequest: (Int) -> Unit = {},
        /** 模拟服务端一次能给多少条（调用方要把一次请求夹到 [Source.maxPageSize] 内） */
        private val pageCap: Int = Int.MAX_VALUE,
    ) : Source {
        override val type: SourceType = SourceType.KOMGA

        override val maxPageSize: Int get() = pageCap

        val requestedPages = mutableListOf<Int>()

        /** 每次请求要了多少条（与 [requestedPages] 一一对应） */
        val requestedSizes = mutableListOf<Int>()

        private fun all(): List<BrowseEntry> =
            (0 until total).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) }

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> = all()

        override suspend fun listEntriesPage(
            containerId: String?,
            sort: SortMode,
            page: Int,
            size: Int,
        ): BrowseEntryPage {
            requestedPages += page
            requestedSizes += size
            onPageRequest(page)
            // 切片走生产的同一份算式（这里原是自己抄一份同形算式）
            return sliceEntryPage(all(), page, size)
        }

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("本用例不打开书")

        override suspend fun readProgress(bookId: String) = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String) = com.cc3301.comicviewer.core.source.Neighbors(null, null)
    }

    /** 服务器永远说「还有下一页」，且从第 [emptyFrom] 页起整页为空（形态：空页 + hasMore 恒真） */
    private class EmptyTailSource(
        private val firstPage: List<BrowseEntry>,
        private val emptyFrom: Int,
    ) : Source {
        override val type: SourceType = SourceType.KOMGA

        val requestedPages = mutableListOf<Int>()

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> = firstPage

        override suspend fun listEntriesPage(
            containerId: String?,
            sort: SortMode,
            page: Int,
            size: Int,
        ): BrowseEntryPage {
            requestedPages += page
            return if (page < emptyFrom) {
                BrowseEntryPage(firstPage, hasNext = true)
            } else {
                // 空页 + hasNext 真：没有上限的实现在这条路上无限取数
                BrowseEntryPage(emptyList(), hasNext = true)
            }
        }

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("本用例不打开书")

        override suspend fun readProgress(bookId: String) = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String) = com.cc3301.comicviewer.core.source.Neighbors(null, null)
    }

    private fun loader(source: Source, pageSize: Int = 200, reverse: Boolean = false) =
        BrowsePageLoader(
            source = source,
            containerId = "container",
            sort = SortMode.NAME,
            reverse = reverse,
            entryNames = entryNames,
            pageSize = pageSize,
        )

    @Test
    fun `首屏只请求第 0 页 不拉全量`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())

        assertEquals("首屏只问服务器一次", listOf(0), source.requestedPages)
        assertTrue("落过帧（界面据此区分空层与未加载）", pager.loaded)
    }

    @Test
    fun `取数期间一帧都不上屏 拿到最新列表才落`() = runBlocking<Unit> {
        // 进目录时目标层自己不得先显示旧列表（`docs/spec/browsing.md`「进目录的单段式取数」）：
        // 请求发出那一刻屏幕上什么都还没有；把「先落一帧旧内容」加回来这条即红。
        val stateAtRequest = mutableListOf<Pair<Boolean, Int>>()
        lateinit var pager: BrowsePageLoader
        val source = RecordingSource(total = 1000, onPageRequest = { stateAtRequest += pager.loaded to pager.entries.size })
        pager = loader(source)

        assertFalse("建出来就是「还没加载」", pager.loaded)
        assertEquals(emptyList<BrowseEntry>(), pager.entries)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())

        assertEquals("取数那一刻屏幕上没有任何一帧（没有先落旧列表这一段）", listOf(false to 0), stateAtRequest)
        assertTrue("拿到结果才落帧", pager.loaded)
        assertEquals(200, pager.entries.size)
    }

    @Test
    fun `首屏取数下限是恢复索引加一 不看旧列表长度`() = runBlocking<Unit> {
        // 恢复的滚动索引可能落在很后面（深目录）：只取第 0 页（200 条）的话索引被夹到已加载末尾。
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex(), restoredIndexNow = 600)

        assertTrue(
            "恢复索引 600 要有内容可落：项数 ≥ 601（不看恢复索引则只有 200）",
            pager.entries.size >= 601,
        )
        assertEquals("一次问够 800 条 = 一次请求（按 200 逐页要四次）", listOf(0), source.requestedPages)
        assertEquals(listOf(800), source.requestedSizes)
    }

    @Test
    fun `恢复索引超过这一层条数时取到末页即停`() = runBlocking<Unit> {
        // 恢复索引是下限不是请求数：目录变短（或换过服务器数据）时按来源的 hasNext 停，不会一直要页。
        val source = RecordingSource(total = 250)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex(), restoredIndexNow = 5000)

        assertEquals("整层只有 250 条：一次请求就到底（来源说没有下一页就停）", listOf(0), source.requestedPages)
        assertEquals(250, pager.entries.size)
        assertFalse("末页落地后不再挂尾部触发件", pager.hasMore)
    }

    @Test
    fun `首屏整页为空且还说自己有下一页时当终止`() = runBlocking<Unit> {
        // 正常服务端不会空页还说有下一页。空页当真会让尾部触发件一直发请求。
        val source = EmptyTailSource(firstPage = emptyList(), emptyFrom = 0)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())

        assertTrue("落过帧（界面据此显示空态）", pager.loaded)
        assertFalse("空页 + hasMore 真 = 终止（界面据此不挂尾部触发件）", pager.hasMore)
        assertEquals("只问第 0 页", listOf(0), source.requestedPages)
    }

    @Test
    fun `续页取到空页时不再往下要`() = runBlocking<Unit> {
        // 尾部触发件以页码为键，hasMore 恒真时每追加一页就再要一页（无限取数）
        val source = EmptyTailSource(
            firstPage = listOf(BrowseEntry(id = "book-0", name = "Book 0", isBook = true, coverUri = null)),
            emptyFrom = 1,
        )
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())
        pager.loadNextPage()
        val afterEmptyPage = source.requestedPages.size
        pager.loadNextPage() // hasMore 已假：不该再发请求

        assertEquals("第 0 页 + 第一次续页（空页）", listOf(0, 1), source.requestedPages)
        assertEquals("空页落地后不再发请求", afterEmptyPage, source.requestedPages.size)
        assertFalse("空页 = 终止", pager.hasMore)
    }

    @Test
    fun `首屏取够只向来源发一次请求 不把 8 页摊成 8 次全量重列`() = runBlocking<Unit> {
        // 返回浏览页时炸的不是滑动机制，而是首屏这条：深目录恢复索引 1577 ⇒ 下限 1578 ⇒
        // 原实现按 200 一页要 **8 页**，而 `Source.listEntriesPage` 的默认实现是「取全量再切片」
        // ⇒ **每页一次全量重列**（8 段 15~98ms，正好盖住过渡窗口）。
        // 现在按「还差多少条」一次要够 = 一次全量重列。
        val source = RecordingSource(total = 1578)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex(), restoredIndexNow = 1577)

        assertEquals("1578 条层的首屏只问来源一次（回退成逐页问即红）", listOf(0), source.requestedPages)
        assertEquals("一次请求就覆盖整层（按页长对齐取整：8 × 200）", listOf(1600), source.requestedSizes)
        assertEquals(1578, pager.entries.size)
        assertFalse("末页落地后不挂尾部触发件", pager.hasMore)
    }

    @Test
    fun `一次请求条数夹到来源的一次上限 且页码与请求条数同一套坐标`() = runBlocking<Unit> {
        // 有服务端分页上限的来源（Komga：一次最多 500）不能一次收下 1500 条，调用方因此按
        // `Source.maxPageSize` 夹；夹完的条数仍是每页长的整数倍，页码也以它为步长——
        // 否则页坐标与请求条数对不上，续页会重复或漏条。
        val source = RecordingSource(total = 1500, pageCap = 500)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex(), restoredIndexNow = 1499)

        assertEquals("上限 500 ⇒ 一次给 400 条（200 的整数倍），取够 1500 条 = 4 次", listOf(0, 1, 2, 3), source.requestedPages)
        assertEquals(listOf(400, 400, 400, 400), source.requestedSizes)
        assertEquals(1500, pager.entries.size)
    }

    /**
     * `Source.maxPageSize` 的**前置条件**：本值不得小于一页长度（[BROWSE_PAGE_SIZE]）。
     *
     * 夹法是「向下取整到页长的整数倍、且**不低于一页**」（`loadFirstPages` 的 `coerceAtLeast(pageSize)`），
     * 所以上限比一页还小时，兜底会把请求的 `size` 顶到一页长度（**比来源上限大**）——分页坐标不错
     *（`nextPage` 仍按 `page * pageSize` 推），但请求会被服务器拒。现网四来源都不命中这条
     *（Komga 500 > 200，其余 `Int.MAX_VALUE`），因此**只立边界、不改算式**。
     *
     * 本用例是**边界记录**，不是「应当如此」：若哪天真要修，改算式的同时必须改这条断言与 `Source` 的 KDoc。
     */
    @Test
    fun `上限小于一页时的取数与前置条件不一致 是记录在案的边界`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000, pageCap = 100)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex(), restoredIndexNow = 300)

        assertEquals("夹法把 size 顶到一页长度（200 > 上限 100）：前置条件被破坏时的实际行为", listOf(200, 200), source.requestedSizes)
        assertEquals("取够 301 条 = 2 次（页码仍以 size 为单位，坐标不自相矛盾）", listOf(0, 1), source.requestedPages)
        assertEquals(400, pager.entries.size)
    }

    @Test
    fun `滑条分母是已加载条数 不是该层总数`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())
        assertEquals("分母 = 已加载条数（不是 1000）", 200, pager.sliderItemCount(extraRows = 0))
        assertEquals("附加行（截断提示/尾部触发件）计入分母，与行坐标一致", 202, pager.sliderItemCount(extraRows = 2))

        pager.loadNextPage()
        assertEquals("追加一页后分母跟着长", 400, pager.sliderItemCount(extraRows = 0))
    }

    @Test
    fun `滑条分母始终读当前 loader 下拉更新换实例后不失准`() = runBlocking<Unit> {
        // 分母 lambda 只在界面的 remember(listState) 那一刻建一次，而**下拉更新会换一个新 loader**
        //（listState 的键不含 pager 实例）⇒ 必须经 State 读当前值；按值捕获即红。
        val before = loader(RecordingSource(total = 1000))
        val after = loader(RecordingSource(total = 1000))
        val current = mutableStateOf(before)
        val itemCount = browseSliderItemCount(current) { 0 }

        assertEquals("刷新前：旧 loader 还没落帧", 0, itemCount())

        after.loadFirstScreen(restoredIndex = RestoredScrollIndex())
        current.value = after // 下拉更新换新 loader（同一个 lambda 不重建）

        assertEquals("刷新后分母跟着新 loader 走（按值捕获则仍是 0）", 200, itemCount())
    }

    @Test
    fun `尾部触发才请求第 2 页并追加`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())
        pager.loadNextPage()

        assertEquals("第 2 页只在尾部触发时取", listOf(0, 1), source.requestedPages)
        assertEquals(400, pager.entries.size)
        assertTrue("后面还有", pager.hasMore)
    }

    @Test
    fun `取到最后一页后不再请求`() = runBlocking<Unit> {
        val source = RecordingSource(total = 250)
        val pager = loader(source)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())
        pager.loadNextPage()
        pager.loadNextPage() // hasMore 已假：不该再发请求

        assertEquals(listOf(0, 1), source.requestedPages)
        assertEquals(250, pager.entries.size)
        assertFalse(pager.hasMore)
    }

    @Test
    fun `整份落地（反向档）不切首屏也不触发取下一页`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source, reverse = true)

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())
        pager.loadNextPage()

        assertEquals("反向档要整份翻转，不能切成首屏", 1000, pager.entries.size)
        assertFalse(pager.hasMore)
        assertEquals("整份走 listEntries，一页都没按页要", emptyList<Int>(), source.requestedPages)
    }
}
