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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「先取数、后弹栈」（返回上级）与那口一次性交接槽。
 *
 * 判别力（每条用例各钉一处「改坏即红」）：
 * - 交接槽不做一次性（`take` 不取走）⇒ `命中即取走…` 红；
 * - 认领判据漏掉连接 / 容器 / 排序 / 方向任何一项 ⇒ `连接不同…` / `换了容器…` / `换了排序…` / `换了方向…` 红；
 * - 不设有效窗口（留着老槽）⇒ `过了窗口的那份不认` 红；
 * - 取数失败还装槽（目标层于是拿到一台空的取数器、进哪层都是「此目录没有内容」）⇒ `预取失败不留槽…` 红；
 * - 取消被当成「这一份没取到」（于是照常返回）⇒ `预取被取消 上抛且不留槽` 红；
 * - 返回上级那一段不再受取消约束（照样弹）⇒ `预取被取消 整条路都不弹栈` 红；
 * - 返回上级不等取数（白闪回到改前）⇒ `返回上级 先装槽再弹` 红；
 * - 返回上级跨连接也用本层来源取数（取错库）⇒ `返回上级 跨连接…` 红；
 * - 返回上级取数失败或超时不弹（返回键卡死）⇒ `返回上级 取数失败也照样弹` / `返回上级 预取超时也照样弹` 红。
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

        /** 取数真的开始了：用例据此在取数在飞的时候取消（[hang] 那一档） */
        val entered = CompletableDeferred<Unit>()

        val requestedPages = mutableListOf<Int>()
        var listEntriesCalls = 0
            private set

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
            entered.complete(Unit)
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
            entered.complete(Unit)
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

    /** 取数上下文（生产里由 `BrowserScreen` 按来源 / 排序 / 方向现建一份） */
    private fun listing(
        source: Source,
        handoff: LayerHandoff,
        connId: Long = 7,
        reverse: Boolean = false,
    ) = LayerListing(source, connId, SortMode.NAME, reverse, entryNames, handoff)

    private fun pager(source: Source) = BrowsePageLoader(
        source,
        containerId = CONTAINER,
        sort = SortMode.NAME,
        entryNames = entryNames,
    )

    private fun target(containerId: String = "sub", connId: Long = 7) =
        BrowseLocation(connId = connId, containerId = containerId, containerName = containerId)

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
    fun `连接不同不认 且清槽`() {
        val handoff = LayerHandoff(now = { 0L })
        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager(FakeSource(1)))

        assertNull("连接不同", handoff.take(connId = 8, containerId = "sub", sort = SortMode.NAME, reverse = false))
        assertNull("认领过（不命中）就清槽，不留给下一个进来的层", handoff.take(7, "sub", SortMode.NAME, false))
    }

    @Test
    fun `换了容器不认`() {
        val handoff = LayerHandoff(now = { 0L })
        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager(FakeSource(1)))

        assertNull("排到别的层上（下一层的内容会错）", handoff.take(7, "other", SortMode.NAME, false))
    }

    @Test
    fun `换了排序不认`() {
        val handoff = LayerHandoff(now = { 0L })
        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager(FakeSource(1)))

        assertNull("排序不同（列表顺序会不一样，宁可自己重列）", handoff.take(7, "sub", SortMode.MODIFIED_TIME, false))
    }

    @Test
    fun `换了方向不认`() {
        val handoff = LayerHandoff(now = { 0L })
        handoff.arm(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false, pager = pager(FakeSource(1)))

        assertNull("方向不同（反向档要整份取完再倒过来，形状不是一份）", handoff.take(7, "sub", SortMode.NAME, true))
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

        prepareLayerHandoff(listing(source, handoff), target())

        val prepared = handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = false)
        assertTrue("槽里那台取数器已经落过帧", prepared!!.loaded)
        assertEquals("条目就是这一次列目录的结果", 3, prepared.entries.size)
        assertEquals("真列一次（不是拿快照或旧列表垫）", listOf(0), source.requestedPages)
    }

    @Test
    fun `反向档预取整份上屏`() = runBlocking<Unit> {
        val source = FakeSource(total = 3)
        val handoff = LayerHandoff(now = { 0L })

        prepareLayerHandoff(listing(source, handoff, reverse = true), target())

        val prepared = handoff.take(connId = 7, containerId = "sub", sort = SortMode.NAME, reverse = true)
        assertEquals("反向档整份取完（追加页复现不了「从尾到头」）", 3, prepared!!.entries.size)
        assertFalse("整份在手 ⇒ 没有下一页", prepared.hasMore)
        assertEquals("整层枚举一次", 1, source.listEntriesCalls)
    }

    @Test
    fun `预取失败不留槽 也不上抛`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })

        // 失败记在取数模块里（界面态那份提示）：交接这一路据此不留槽——目标层拿到一台没落过帧的取数器
        // 会显示「此目录没有内容」，宁可让它自己列一次目录。失败不是返回失败，因此不上抛（调用方照旧弹栈）。
        val thrown = runCatching {
            prepareLayerHandoff(listing(FakeSource(total = 3, failure = IOException("boom")), handoff), target())
        }.exceptionOrNull()

        assertNull("失败不上抛：返回上级那一步照旧弹栈", thrown)
        assertNull("失败不留半成品槽", handoff.take(7, "sub", SortMode.NAME, false))
    }

    @Test
    fun `预取被取消 整条路都不弹栈`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0
        val source = FakeSource(total = 3, hang = true)

        // 取消不是「这一份没取到」：这一层已经离开了，再弹一次就是多弹。
        val job = launch {
            popToParentLayer(
                listing(source, handoff),
                parent = BrowseLocation(connId = 7, containerId = "parent", containerName = "上级"),
                timeoutMillis = 60_000,
                pop = { pops++ },
            )
        }
        source.entered.await()
        job.cancelAndJoin()

        assertEquals("被取消：整条返回上级都不弹", 0, pops)
        assertNull("取消那份不装槽", handoff.take(7, "parent", SortMode.NAME, false))
    }

    @Test
    fun `预取被取消 上抛且不留槽`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        val source = FakeSource(total = 3, hang = true)
        var returnedNormally = false

        // 取消不是「这一份没取到」：上抛给调用方按取消处理，槽里也不留半成品。
        val job = launch {
            prepareLayerHandoff(listing(source, handoff), target())
            returnedNormally = true
        }
        source.entered.await()
        job.cancelAndJoin()

        assertFalse("取消要上抛，不能照常返回", returnedNormally)
        assertNull("取消那份不装槽", handoff.take(7, "sub", SortMode.NAME, false))
    }

    @Test
    fun `返回上级 先装槽再弹`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0

        popToParentLayer(
            listing(FakeSource(total = 3), handoff),
            parent = BrowseLocation(connId = 7, containerId = "parent", containerName = "上级"),
            pop = { pops++ },
        )

        assertEquals("弹栈一次", 1, pops)
        assertTrue("父层首帧就是这一次列目录的结果", handoff.take(7, "parent", SortMode.NAME, false)!!.loaded)
    }

    @Test
    fun `返回上级 跨连接不取数直接弹`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0

        popToParentLayer(
            // 本层来源不是父层那一份：一旦被调用就会抛（跨连接不许用本层来源取数）
            listing(FakeSource(total = 3, failure = IOException("不该取")), handoff, connId = 7),
            parent = BrowseLocation(connId = 8, containerId = "parent", containerName = "上级"),
            pop = { pops++ },
        )

        assertEquals("照旧直接弹", 1, pops)
        assertNull(handoff.take(8, "parent", SortMode.NAME, false))
    }

    @Test
    fun `返回上级 取数失败也照样弹`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0

        popToParentLayer(
            listing(FakeSource(total = 3, failure = IOException("离线")), handoff, connId = 7),
            parent = BrowseLocation(connId = 7, containerId = "parent", containerName = "上级"),
            pop = { pops++ },
        )

        assertEquals("断网返回上级立刻返回（父层自己加载）", 1, pops)
        assertNull("失败那份不装槽", handoff.take(7, "parent", SortMode.NAME, false))
    }

    @Test
    fun `返回上级 预取超时也照样弹`() = runBlocking<Unit> {
        val handoff = LayerHandoff(now = { 0L })
        var pops = 0

        popToParentLayer(
            listing(FakeSource(total = 3, hang = true), handoff),
            parent = BrowseLocation(connId = 7, containerId = "parent", containerName = "上级"),
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
