package com.cc3301.comicviewer.ui

import androidx.compose.runtime.mutableStateOf
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.sliceEntryPage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览列表的按页取数（票 #119 步骤 3）：首屏按已上屏那一帧的长度与恢复到的滚动索引取够页（没有帧时就是第 0 页，票 #125 P1-1 + 票 #124），
 * 滚到尾部才追加下一页。
 *
 * 判别力：把首屏改回「整层取完」（或去掉 [BrowsePageLoader.loadNextPage] 的追加），
 * 「首屏只请求第 0 页」「尾部触发才请求第 2 页」的断言即变红——这正是本轮要钉住的行为。
 */
class BrowsePageLoaderTest {

    /** 只实现取数：记录每次按页取数的页码，并按 [size] 切出一份合成条目表；[snapshot] 非 null 时模拟落盘快照 */
    private class RecordingSource(
        private val total: Int,
        private val snapshot: List<BrowseEntry>? = null,
        /** 每次按页取数时回调一次（页码）：用来在断言里比对「落帧」与「取数」的**真实次序** */
        private val onPageRequest: (Int) -> Unit = {},
        /** 模拟服务端一次能给多少条（票 #111 r9：调用方要把一次请求夹到 [Source.maxPageSize] 内） */
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

        override suspend fun snapshotEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? = snapshot

        override suspend fun listEntriesPage(
            containerId: String?,
            sort: SortMode,
            page: Int,
            size: Int,
        ): BrowseEntryPage {
            requestedPages += page
            requestedSizes += size
            onPageRequest(page)
            // 切片走生产的同一份算式（票 #124 C 组：这里原是自己抄一份同形算式）
            return sliceEntryPage(all(), page, size)
        }

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("本用例不打开书")

        override suspend fun readProgress(bookId: String) = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String) = com.cc3301.comicviewer.core.source.Neighbors(null, null)
    }

    /** 服务器永远说「还有下一页」，且从第 [emptyFrom] 页起整页为空（票 #125 P1-2 的形态：空页 + hasMore 恒真） */
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

    private fun loader(source: Source, pageSize: Int = 200, snapshot: List<BrowseEntry>? = null) =
        BrowsePageLoader(
            source,
            containerId = "container",
            sort = SortMode.NAME,
            pageSize = pageSize,
            snapshot = snapshot,
        )

    @After
    fun clearEntryNames() {
        ServiceLocator.entryNames.clear()
    }

    @Test
    fun `快照帧不等来源解析 来源还没就绪也先落帧`() {
        // 票 #124 r2（评审 P1 回归）：`source` 由 `rememberConnectionSource` 在 IO 上异步解析（首帧必为 null），
        // 而会话内快照来自会话槽位（同步可读、不等解析）。落帧若排在来源守卫之后，来源解析的整个窗口里
        // `pager.loaded` 都是 false，界面走 `list == null ->「加载中…」`（与 SPEC「列表枚举性能 ·
        // 同步快照访问器」相左）。
        val snapshot = (0 until 3).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val pager = BrowsePageLoader(null, containerId = "container", sort = SortMode.NAME)

        val notReady = pager.landSnapshotFrame(source = null, preloaded = snapshot)

        assertNull("来源未就绪：只落帧、不取数", notReady)
        assertTrue("快照帧不等 source 解析（拿掉落帧在守卫之前这一点即红）", pager.loaded)
        assertEquals(snapshot.map { it.id }, pager.entries.map { it.id })

        // 来源已就绪时照旧交回来源（调用方据此取数），帧也照旧先落
        val ready = RecordingSource(total = 3)
        val readyPager = BrowsePageLoader(ready, containerId = "container", sort = SortMode.NAME)
        assertEquals(ready, readyPager.landSnapshotFrame(ready, snapshot))
        assertTrue(readyPager.loaded)
    }

    @Test
    fun `首屏只请求第 0 页 不拉全量`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen()

        assertEquals("首屏只问服务器一次", listOf(0), source.requestedPages)
        assertTrue("落过帧（界面据此区分空层与未加载）", pager.loaded)
    }

    @Test
    fun `首屏落帧先于任何取数 不空白等整层`() = runBlocking<Unit> {
        // 票 #123 裁决（名称档仍整层取的那一支）：首屏先用快照/缓存落一帧，不允许空白等整层枚举。
        // 票 #124 修复轮（评审 B 组条目）：旧写法在**落帧回调里读 `source.requestedPages.size`**——回调点就在
        // `showSnapshot` 之后、`loadFirstPages` 之前，那个 0 由实现顺序保证、把行为改坏也不会红（同义反复）。
        // 现在由**来源侧**与回调各记一个事件、断言**真实次序**：落帧若被挪到取数之后，本断言即红。
        val snapshot = (0 until 500).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) }
        val events = mutableListOf<String>()
        val source = RecordingSource(total = 1000, snapshot = snapshot, onPageRequest = { events += "fetch:$it" })
        val pager = loader(source)

        pager.loadFirstScreen { events += "frame:${pager.entries.size}" }

        assertEquals(
            "落帧必须排在第一次取数之前（首帧来自快照的整份 500 条，界面因此不空白）",
            listOf("frame:500", "fetch:0"),
            events,
        )
    }

    @Test
    fun `首屏先落快照帧 再按它的长度取够再替换`() = runBlocking<Unit> {
        // 票 #75 两段式首帧（票 #119 修复轮恢复）：有落盘快照时第一帧来自快照，不是空列表。
        // 票 #125 P1-1：第二段取够快照那一帧的长度才替换（500 条 = 3 页），列表因此不会变短。
        val snapshot = (0 until 500).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1000, snapshot = snapshot)
        val pager = loader(source)
        val frames = mutableListOf<List<String>>()

        pager.loadFirstScreen { frames += pager.entries.map { it.id } }

        assertEquals(
            "第一帧来自快照（整份上屏，不切首屏：拿掉快照段则这里为空）",
            listOf((0 until 500).map { "old-$it" }),
            frames,
        )
        assertEquals(
            "第二段取够快照长度（500 条→按页对齐取 3 页 = 600 条）再替换，列表不会变短",
            (0 until 600).map { "book-$it" },
            pager.entries.map { it.id },
        )
        assertEquals("一次问够：500 条（3 页）只要一次请求，不再逐 200 条要三次（票 #111 r9）", listOf(0), source.requestedPages)
    }

    @Test
    fun `大目录从阅读器返回 快照那一帧的长度决定恢复后列表不少于原位置`() = runBlocking<Unit> {
        // 票 #125 P1-1：会话内快照就是上次上屏的那份列表，恢复的滚动索引必落在它范围内
        //（1500 条的目录里停在第 1400 行）。第 0 页（200 条）替换它就把索引夹到已加载末尾。
        val snapshot = (0 until 1500).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1500, snapshot = snapshot)
        // 与界面同一条路：效果期先落会话快照（BrowserScreen 的 preloaded → landSnapshotFrame），再跑两段式首屏
        val pager = loader(source).apply { showSnapshot(snapshot) }

        pager.loadFirstScreen()

        assertTrue(
            "恢复索引 1400 要有内容可落：项数 ≥ 1401（切成首屏则只有 200）",
            pager.entries.size >= 1401,
        )
        assertEquals("取够 1500 条只要一次请求（原来要 0..7 共八次，每次都是一次全量重列）", listOf(0), source.requestedPages)
    }

    @Test
    fun `直取档恢复的滚动索引决定首屏取够多少条`() = runBlocking<Unit> {
        // 票 #124（评审 E-8）：直取档的会话内列表只含第 0 页（票 #119 约束），滚到第 600 条进阅读器再返回时，
        // 只按快照长度取够（票 #125 在回退档用的那条）就只有 200 条，滚动索引被 Lazy 列表夹到已加载末尾。
        // 首屏取数下限因此还要吃「界面恢复到的索引」：拿掉它即红（200 条 < 601）。
        val pageZero = (0 until 200).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1000, snapshot = pageZero)
        val pager = loader(source)

        pager.loadFirstScreen(restoredItemIndex = 600)

        assertTrue(
            "恢复索引 600 要有内容可落：项数 ≥ 601（不看恢复索引则只有 200）",
            pager.entries.size >= 601,
        )
        assertEquals("一次问够 800 条 = 一次请求（原来按 200 逐页要四次）", listOf(0), source.requestedPages)
    }

    @Test
    fun `下拉更新后按当下位置重算下限 在顶部就回到快照长度`() = runBlocking<Unit> {
        // 票 #124 r2 影响面复验 P1 的后果面：下拉更新后恢复位置重读为「当下」（在顶部 = 0）
        // ⇒ 下限回到快照长度（直取档 = 第 0 页 200 条 ⇒ 只取 1 页；沿用旧索引 600 会取 4 页）。
        // 「重读」那一半由 `BrowseScrollRestoreTest` 的持有者用例钉住，这里钉它落地后的取数形态。
        val pageZero = (0 until 200).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1000, snapshot = pageZero)
        val pager = loader(source)

        pager.loadFirstScreen(restoredItemIndex = 0)

        assertEquals("在顶部刷新：只取第 0 页（不按旧索引取 4 页）", listOf(0), source.requestedPages)
        assertEquals(200, pager.entries.size)
    }

    @Test
    fun `恢复索引超过这一层条数时取到末页即停`() = runBlocking<Unit> {
        // 恢复索引是下限不是请求数：目录变短（或换过服务器数据）时按来源的 hasNext 停，不会一直要页。
        val source = RecordingSource(total = 250)
        val pager = loader(source)

        pager.loadFirstScreen(restoredItemIndex = 5000)

        assertEquals("整层只有 250 条：一次请求就到底（来源说没有下一页就停）", listOf(0), source.requestedPages)
        assertEquals(250, pager.entries.size)
        assertFalse("末页落地后不再挂尾部触发件", pager.hasMore)
    }

    @Test
    fun `首屏整页为空且还说自己有下一页时当终止`() = runBlocking<Unit> {
        // 票 #125 P1-2：正常服务端不会空页还说有下一页。空页当真会让尾部触发件一直发请求。
        val source = EmptyTailSource(firstPage = emptyList(), emptyFrom = 0)
        val pager = loader(source)

        pager.loadFirstScreen()

        assertTrue("落过帧（界面据此显示空态）", pager.loaded)
        assertFalse("空页 + hasMore 真 = 终止（界面据此不挂尾部触发件）", pager.hasMore)
        assertEquals("只问第 0 页", listOf(0), source.requestedPages)
    }

    @Test
    fun `续页取到空页时不再往下要`() = runBlocking<Unit> {
        // 票 #125 P1-2：尾部触发件以页码为键，hasMore 恒真时每追加一页就再要一页（无限取数）
        val source = EmptyTailSource(
            firstPage = listOf(BrowseEntry(id = "book-0", name = "Book 0", isBook = true, coverUri = null)),
            emptyFrom = 1,
        )
        val pager = loader(source)

        pager.loadFirstScreen()
        pager.loadNextPage()
        val afterEmptyPage = source.requestedPages.size
        pager.loadNextPage() // hasMore 已假：不该再发请求

        assertEquals("第 0 页 + 第一次续页（空页）", listOf(0, 1), source.requestedPages)
        assertEquals("空页落地后不再发请求", afterEmptyPage, source.requestedPages.size)
        assertFalse("空页 = 终止", pager.hasMore)
    }

    @Test
    fun `首屏取够只向来源发一次请求 不把 8 页摊成 8 次全量重列`() = runBlocking<Unit> {
        // 票 #111 r9 ⑤（真机数据）：返回浏览页时 `overBudgetTotal` 炸的不是滑动机制，而是首屏这条——
        // 快照 1578 条 ⇒ 下限 1578 ⇒ 原实现按 200 一页要 **8 页**，而 `Source.listEntriesPage` 的默认实现
        // 是「取全量再切片」⇒ **每页一次全量重列**（实测 8 段 15~98ms，正好盖住过渡窗口；进入阅读器那一侧
        // 没有这条路径，3/3 全干净）。现在按「还差多少条」一次要够 = 一次全量重列。
        val snapshot = (0 until 1578).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1578, snapshot = snapshot)
        val pager = loader(source)

        pager.loadFirstScreen()

        assertEquals("1578 条层的首屏只问来源一次（回退成逐页问即红）", listOf(0), source.requestedPages)
        assertEquals("一次请求就覆盖整层（按页长对齐取整：8 × 200）", listOf(1600), source.requestedSizes)
        assertEquals(1578, pager.entries.size)
        assertFalse("末页落地后不挂尾部触发件", pager.hasMore)
    }

    @Test
    fun `一次请求条数夹到来源的一次上限 且页码与请求条数同一套坐标`() = runBlocking<Unit> {
        // 有服务端分页上限的来源（Komga：一次最多 500）不能收 1578 条，调用方因此按
        // `Source.maxPageSize` 夹；夹完的条数仍是每页长的整数倍，页码也以它为步长——
        // 否则页坐标与请求条数对不上，续页会重复或漏条。
        val snapshot = (0 until 1500).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1500, snapshot = snapshot, pageCap = 500)
        val pager = loader(source)

        pager.loadFirstScreen()

        assertEquals("上限 500 ⇒ 一次给 400 条（200 的整数倍），取够 1500 条 = 4 次", listOf(0, 1, 2, 3), source.requestedPages)
        assertEquals(listOf(400, 400, 400, 400), source.requestedSizes)
        assertEquals(1500, pager.entries.size)
    }

    @Test
    fun `构造期就落会话快照 首帧不必等 LaunchedEffect`() = runBlocking<Unit> {
        // 票 #111 r9 ④：从阅读器返回时浏览页是滑入的，会话快照是**同步内存读**——没有理由等一个 effect，
        // 等的话滑入的头一两帧列表还是空的（显示「加载中…」，真机反馈的「返回时会闪一下」包含这一支）。
        // 这里不跑任何 suspend 调用，只构造：拿掉构造期落帧即红。
        val snapshot = (0 until 30).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1000, snapshot = snapshot)

        val pager = loader(source, snapshot = snapshot)

        assertTrue("构造完就是已落帧状态（不用跑任何 suspend 调用）", pager.loaded)
        assertEquals(snapshot.map { it.id }, pager.entries.map { it.id })
        assertFalse("快照只当首帧：不因此挂尾部触发件", pager.hasMore)
    }

    @Test
    fun `滑条分母是已加载条数 不是该层总数`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen()
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

        after.loadFirstScreen()
        current.value = after // 下拉更新换新 loader（同一个 lambda 不重建）

        assertEquals("刷新后分母跟着新 loader 走（按值捕获则仍是 0）", 200, itemCount())
    }

    @Test
    fun `尾部触发才请求第 2 页并追加`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen()
        pager.loadNextPage()

        assertEquals("第 2 页只在尾部触发时取", listOf(0, 1), source.requestedPages)
        assertEquals(400, pager.entries.size)
        assertTrue("后面还有", pager.hasMore)
    }

    @Test
    fun `取到最后一页后不再请求`() = runBlocking<Unit> {
        val source = RecordingSource(total = 250)
        val pager = loader(source)

        pager.loadFirstScreen()
        pager.loadNextPage()
        pager.loadNextPage() // hasMore 已假：不该再发请求

        assertEquals(listOf(0, 1), source.requestedPages)
        assertEquals(250, pager.entries.size)
        assertFalse(pager.hasMore)
    }

    @Test
    fun `整份落地（反向档）不切首屏也不触发取下一页`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)
        val all = (0 until 1000).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }

        pager.showAll(all)
        pager.loadNextPage()

        assertEquals("反向档要整份翻转，不能切成首屏", 1000, pager.entries.size)
        assertFalse(pager.hasMore)
        assertEquals(emptyList<Int>(), source.requestedPages)
    }

    @Test
    fun `快照整份上屏 且不触发取下一页`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.showSnapshot((0 until 500).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) })
        pager.loadNextPage()

        assertEquals("快照是首帧、不是第二个数据来源：不因此发任何请求", emptyList<Int>(), source.requestedPages)
        assertEquals("快照整份上屏（切到页大小会让恢复的滚动索引落空）", 500, pager.entries.size)
        assertFalse("第 0 页落地前不挂尾部触发件", pager.hasMore)
    }
}
