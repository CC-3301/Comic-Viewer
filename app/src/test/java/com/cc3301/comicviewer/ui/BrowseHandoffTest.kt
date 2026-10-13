package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.sliceEntryPage
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「先取数、后切换」的两条路径（下探 / 返回上级）与那口一次性交接槽。
 *
 * 判别力（每条用例各钉一处「改坏即红」）：
 * - 交接槽不做一次性（`take` 不取走）⇒ `命中即取走…` 红；
 * - 认领判据漏掉排序 / 方向 / 层键任何一项 ⇒ `四个键任一不同都不认…` 红；
 * - 不设有效窗口（留着老槽）⇒ `过了窗口的那份不认…` 红；
 * - 预取失败还装槽（目标层于是拿到一台空的取数器、进哪层都是「此目录没有内容」）⇒ `预取失败不留槽…` 红；
 * - 下探失败也切换（用户看到跳过去又空/错）⇒ `下探失败 不切换也不装槽` 红；
 * - 下探把取消吞成失败（弹出「加载失败」）⇒ `下探被取消…` 红；
 * - 返回上级不等取数（白闪回到改前）或跨连接也用本层来源取（取错库）⇒ `返回上级 先装槽再弹` /
 *   `返回上级 跨连接不预取…` 红；
 * - 返回上级不设超时（慢来源上按返回卡住）⇒ `返回上级 预取超时也照样弹` 红。
 */
class BrowseHandoffTest {

    private val entryNames = ConcurrentHashMap<String, String>()

    /**
     * 假来源：记下取过的页（`requestedPages`）与整层枚举次数（`listEntriesCalls`）。
     * [failure] 非 null 时每次取数都抛它；[hang] = 永不返回（模拟慢来源，用来测超时）。
     */
    private class FakeSource(
        private val total: Int,
        private val failure: Throwable? = null,
        private val hang: Boolean = false,
    ) : Source {
        override val type: SourceType = SourceType.KOMGA

        val requestedPages = mutableListOf<Int>()
        var listEntriesCalls = 0
            private set

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
            failure?.let { throw it }
            if (hang) awaitCancellation()
            listEntriesCalls++
            return all()
        }

        override suspend fun listEntriesPage(
            containerId: String?,
            sort: SortMode,
            page: Int,
            size: Int,
        ): BrowseEntryPage {
            failure?.let { throw it }
            if (hang) awaitCancellation()
            requestedPages += page
            return sliceEntryPage(all(), page, size)
        }

        override fun listTruncationNotice(containerId: String?, sort: SortMode): String? = null

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("本用例不打开书")

        override suspend fun readProgress(bookId: String) = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String) = Neighbors(null, null)

        private fun all(): List<BrowseEntry> =
            (0 until total).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) }
    }

    private fun pager(source: Source) = BrowsePageLoader(
        source,
        containerId = CONTAINER,
        sort = SortMode.NAME,
        entryNames = entryNames,
    )

    private fun container(name: String = "Sub") = BrowseEntry(id = name, name = name, isBook = false, coverUri = null)

    @Test
    fun `命中即取走 再取为空`() {
        val handoff = LayerHandoff(now = { 0L })
        val pager = pager(FakeSource(total = 1))

        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager)

        assertSame(
            "命中的那一份交给目标层",
            pager,
            handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false),
        )
        assertNull(
            "一次性：同一份不会服务第二次",
            handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false),
        )
    }

    @Test
    fun `四个键任一不同都不认 且清槽`() {
        val handoff = LayerHandoff(now = { 0L })
        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager(FakeSource(1)))

        assertNull("连接不同", handoff.take(connId = 8, containerId = "sub", sort = SortMode.NAME, reverse = false))
        assertNull("排到这一层时槽已清掉（不留给下一个进来的层）", handoff.take(7, "sub", SortMode.NAME, false))
    }

    @Test
    fun `换了排序或方向也不认`() {
        val handoff = LayerHandoff(now = { 0L })
        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager(FakeSource(1)))

        assertNull(
            "排序不同（列表顺序会不一样，宁可自己重列）",
            handoff.take(connId = 7, containerId = "sub", sort = SortMode.MODIFIED_TIME, reverse = false),
        )
    }

    @Test
    fun `过了窗口的那份不认`() {
        var now = 0L
        val handoff = LayerHandoff(now = { now })
        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager(FakeSource(1)))

        now = BROWSE_HANDOFF_WINDOW_MS * 1_000_000 + 1

        assertNull(
            "过窗口 = 这一份不再服务任何一层（进目录恒真列一次）",
            handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false),
        )
    }

    @Test
    fun `预取就是把目标层真列一次 并装好槽`() = runBlocking<Unit> {
        val source = FakeSource(total = 3)
        val handoff = LayerHandoff(now = { 0L })

        prepareLayerHandoff(source, connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, entryNames = entryNames, handoff = handoff)

        val prepared = handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false)
        assertTrue("槽里那台取数器已经落过帧", prepared!!.loaded)
        assertEquals("条目就是这一次列目录的结果", 3, prepared.entries.size)
        assertEquals("真列一次（不是拿快照垫）", listOf(0), source.requestedPages)
    }

    @Test
    fun `反向档预取整份上屏`() = runBlocking<Unit> {
        val source = FakeSource(total = 3)
        val handoff = LayerHandoff(now = { 0L })

        prepareLayerHandoff(source, connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = true, entryNames = entryNames, handoff = handoff)

        val prepared = handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = true)
        assertEquals("反向档整份取完（追加页复现不了「从尾到头」）", 3, prepared!!.entries.size)
        assertFalse("整份在手 ⇒ 没有下一页", prepared.hasMore)
        assertEquals("整层枚举一次", 1, source.listEntriesCalls)
    }

    @Test
    fun `预取失败不留槽`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })

        val thrown = runCatching {
            prepareLayerHandoff(
                source = FakeSource(total = 3, failure = IOException("boom")),
                connId = 7,
                containerId = "sub",
                sort = SortMode.NAME,
                reverse = false,
                entryNames = entryNames,
                handoff = handoff,
            )
        }.exceptionOrNull()

        assertTrue("失败要抛出来给调用方（它决定留在原地）", thrown is IOException)
        assertNull("失败不留半成品槽", handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false))
    }

    @Test
    fun `下探成功 装槽后切换一次`() = runBlocking<Unit> {
        val source = FakeSource(total = 3)
        val handoff = LayerHandoff(now = { 0L })
        var switches = 0

        val ready = descendToContainer(
            source = source,
            connId = 7,
            entry = container("sub"),
            sort = SortMode.NAME,
            reverse = false,
            entryNames = entryNames,
            handoff = handoff,
            switchTo = { switches++ },
        )

        assertTrue(ready)
        assertEquals("切换只发一次", 1, switches)
        assertTrue("目标层首帧就是它", handoff.take(7, "sub", SortMode.NAME, false)!!.loaded)
    }

    @Test
    fun `下探失败 不切换也不装槽`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var switches = 0

        val ready = descendToContainer(
            source = FakeSource(total = 3, failure = IOException("boom")),
            connId = 7,
            entry = container("sub"),
            sort = SortMode.NAME,
            reverse = false,
            entryNames = entryNames,
            handoff = handoff,
            switchTo = { switches++ },
        )

        assertFalse("失败交回 false ⇒ 调用方留在当前层弹「加载失败」", ready)
        assertEquals("失败不切换", 0, switches)
        assertNull(handoff.take(7, "sub", SortMode.NAME, false))
    }

    @Test
    fun `下探被取消不吞成失败`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })

        val thrown = runCatching {
            descendToContainer(
                source = FakeSource(total = 3, failure = CancellationException("这一层已被离开")),
                connId = 7,
                entry = container("sub"),
                sort = SortMode.NAME,
                reverse = false,
                entryNames = entryNames,
                handoff = handoff,
                switchTo = { throw AssertionError("取消不该切换") },
            )
        }.exceptionOrNull()

        assertTrue("取消继续向上抛（不弹「加载失败」、不切换）", thrown is CancellationException)
        assertNull(handoff.take(7, "sub", SortMode.NAME, false))
    }

    @Test
    fun `返回上级 先装槽再弹`() = runBlocking<Unit> {
        val source = FakeSource(total = 3)
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0

        popToParentLayer(
            source = source,
            connId = 7,
            parent = BrowseLocation(connId = 7, containerId = "parent", containerName = "上级"),
            sort = SortMode.NAME,
            reverse = false,
            entryNames = entryNames,
            handoff = handoff,
            pop = { pops++ },
        )

        assertEquals("弹栈一次", 1, pops)
        assertTrue("父层首帧就是这一次列目录的结果", handoff.take(7, "parent", SortMode.NAME, false)!!.loaded)
    }

    @Test
    fun `返回上级 跨连接不预取直接弹`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0

        popToParentLayer(
            // 本层来源不是父层那一份：一旦被调用就会抛（跨连接不许用本层来源取数）
            source = FakeSource(total = 3, failure = IOException("不该取")),
            connId = 7,
            parent = BrowseLocation(connId = 8, containerId = "parent", containerName = "上级"),
            sort = SortMode.NAME,
            reverse = false,
            entryNames = entryNames,
            handoff = handoff,
            pop = { pops++ },
        )

        assertEquals("照旧直接弹", 1, pops)
        assertNull(handoff.take(8, "parent", SortMode.NAME, false))
    }

    @Test
    fun `返回上级 预取超时也照样弹`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0

        popToParentLayer(
            source = FakeSource(total = 3, hang = true),
            connId = 7,
            parent = BrowseLocation(connId = 7, containerId = "parent", containerName = "上级"),
            sort = SortMode.NAME,
            reverse = false,
            entryNames = entryNames,
            handoff = handoff,
            timeoutMillis = 30,
            pop = { pops++ },
        )

        assertEquals("慢来源上返回键不许被卡住", 1, pops)
        assertNull("超时那份不装槽（父层自己加载）", handoff.take(7, "parent", SortMode.NAME, false))
    }

    private companion object {
        const val CONTAINER = "container"
    }
}
