package com.cc3301.comicviewer.ui

import androidx.compose.runtime.mutableStateOf
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
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
    ) : Source {
        override val type: SourceType = SourceType.KOMGA

        val requestedPages = mutableListOf<Int>()

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
            val entries = all()
            val from = (page * size).coerceIn(0, entries.size)
            val to = (from + size).coerceIn(from, entries.size)
            return BrowseEntryPage(entries.subList(from, to).toList(), hasNext = to < entries.size)
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

    private fun loader(source: Source, pageSize: Int = 200) =
        BrowsePageLoader(source, containerId = "container", sort = SortMode.NAME, pageSize = pageSize)

    @After
    fun clearEntryNames() {
        ServiceLocator.entryNames.clear()
    }

    @Test
    fun `快照帧不等来源解析 来源还没就绪也先落帧`() {
        // 票 #124 r2（评审 P1 回归）：`source` 由 `rememberConnectionSource` 在 IO 上异步解析（首帧必为 null），
        // 而会话内快照来自会话槽位（同步可读、不等解析）。落帧若排在来源守卫之后，来源解析的整个窗口里
        // `pager.loaded` 都是 false，界面走 `list == null ->「加载中…」`（SPEC:174 与它相左）。
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
        val source = RecordingSource(
            total = 1000,
            snapshot = (0 until 500).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) },
        )
        val pager = loader(source)
        var frameSize = -1
        var requestsAtFrame = -1

        pager.loadFirstScreen {
            frameSize = pager.entries.size
            requestsAtFrame = source.requestedPages.size
        }

        assertEquals("首帧来自快照（界面因此不空白）", 500, frameSize)
        assertEquals("落帧那一刻还没有发生取数", 0, requestsAtFrame)
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
        assertEquals("取数按页对齐：第 0..2 页", listOf(0, 1, 2), source.requestedPages)
    }

    @Test
    fun `大目录从阅读器返回 快照那一帧的长度决定恢复后列表不少于原位置`() = runBlocking<Unit> {
        // 票 #125 P1-1：会话内快照就是上次上屏的那份列表，恢复的滚动索引必落在它范围内
        //（1500 条的目录里停在第 1400 行）。第 0 页（200 条）替换它就把索引夹到已加载末尾。
        val snapshot = (0 until 1500).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1500, snapshot = snapshot)
        // 与界面同一条路：组合期先落会话快照（BrowserScreen 的 preloaded），再跑两段式首屏
        val pager = loader(source).apply { showSnapshot(snapshot) }

        pager.loadFirstScreen()

        assertTrue(
            "恢复索引 1400 要有内容可落：项数 ≥ 1401（切成首屏则只有 200）",
            pager.entries.size >= 1401,
        )
        assertEquals("取够 1500 条 = 第 0..7 页，页数有界", (0..7).toList(), source.requestedPages)
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
        assertEquals("取够 4 页 = 第 0..3 页（按页取、不拉全量）", listOf(0, 1, 2, 3), source.requestedPages)
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

        assertEquals("整层只有 250 条：取到末页即停", listOf(0, 1), source.requestedPages)
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
