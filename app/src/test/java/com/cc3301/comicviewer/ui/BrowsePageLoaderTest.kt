package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览列表的按页取数（票 #119 步骤 3）：首屏只取第 0 页，滚到尾部才追加下一页。
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

    private fun loader(source: Source, pageSize: Int = 200) =
        BrowsePageLoader(source, containerId = "container", sort = SortMode.NAME, pageSize = pageSize)

    @After
    fun clearEntryNames() {
        ServiceLocator.entryNames.clear()
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
    fun `首屏先落快照帧 再取第 0 页`() = runBlocking<Unit> {
        // 票 #75 两段式首帧（票 #119 修复轮恢复）：有落盘快照时第一帧来自快照，不是空列表
        val snapshot = (0 until 500).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) }
        val source = RecordingSource(total = 1000, snapshot = snapshot)
        val pager = loader(source)
        val frames = mutableListOf<List<String>>()

        pager.loadFirstScreen { frames += pager.entries.map { it.id } }

        assertEquals(
            "第一帧来自快照（拿掉快照段则这里为空）",
            listOf((0 until 200).map { "old-$it" }),
            frames,
        )
        assertEquals("第二段换成第 0 页", (0 until 200).map { "book-$it" }, pager.entries.map { it.id })
        assertEquals("取数只发生一次（第 0 页）", listOf(0), source.requestedPages)
    }

    @Test
    fun `滑条分母是已加载条数 不是该层总数`() = runBlocking<Unit> {
        // 票 #119 修复轮口径：按需加载的层里滑条只表示已加载范围内的位置
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.loadFirstScreen()
        assertEquals("分母 = 已加载条数（不是 1000）", 200, pager.sliderItemCount(extraRows = 0))
        assertEquals("附加行（截断提示/尾部触发件）计入分母，与行坐标一致", 202, pager.sliderItemCount(extraRows = 2))

        pager.loadNextPage()
        assertEquals("追加一页后分母跟着长", 400, pager.sliderItemCount(extraRows = 0))
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
    fun `快照只落首屏 且不触发取下一页`() = runBlocking<Unit> {
        val source = RecordingSource(total = 1000)
        val pager = loader(source)

        pager.showSnapshot((0 until 500).map { BrowseEntry(id = "old-$it", name = "Old $it", isBook = true, coverUri = null) })
        pager.loadNextPage()

        assertEquals("快照是首帧、不是第二个数据来源：不因此发任何请求", emptyList<Int>(), source.requestedPages)
        assertEquals("快照按页大小切到首屏长度", 200, pager.entries.size)
        assertFalse("第 0 页落地前不挂尾部触发件", pager.hasMore)
    }
}
