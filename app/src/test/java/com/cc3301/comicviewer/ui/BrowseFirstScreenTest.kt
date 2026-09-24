package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import com.cc3301.comicviewer.core.source.sliceEntryPage
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览页首屏链的编排（票 #134）：**会话快照帧 → 首屏取数下限 → 取够后把滚动位置放回去**，
 * 用假来源整体跑一遍。
 *
 * 为什么要有这一份用例：这三件事此前散在 `BrowserScreen` 的一个 effect 里，它们之间的约束**全是顺序约束**
 * （恢复索引先于任何一帧落屏、帧先于来源守卫、快照只当下限不产能），而 `BrowsePageLoaderTest` 与
 * `BrowseScrollRestoreTest` 各自只测得到链上的一段——合起来的时序没有接缝（票 #123 / #124 / #125
 * 连续三张改的就是同一个 effect）。本文件按票面的现状清单逐条钉住整条链。
 *
 * 判别力（每条用例都对应一处「改坏即红」）：
 * - 把落帧挪到来源守卫之后 ⇒ `来源未就绪 只落快照帧 不取数也不收尾` 红；
 * - 把第二段取数改回固定一页（不看下限）⇒ 两条 `下限取…` 与 `代次只读一次…` 红；
 * - 去掉 `RestoredScrollIndex` 的代次记忆（每帧都覆盖）⇒ `代次只读一次…` 红；
 * - 去掉放回位置（或丢掉截断提示行/尾部触发件行）⇒ 两条放回用例红；
 * - 反向档也走按需加载（会丢「从尾到头」的顺序）⇒ `反向档…` 红。
 */
class BrowseFirstScreenTest {

    /**
     * 假来源：把**顺序**记进 [events]（`snapshot` = 取快照 / `fetch:N` = 取第 N 页 / `listEntries` = 整层枚举 /
     * `notice` = 问截断提示），因此「帧先于取数」「提示在取数之后」这类时序断言比对的是**真实次序**，
     * 而不是实现顺手满足的同义反复。
     */
    private class FakeSource(
        private val total: Int,
        private val snapshot: List<BrowseEntry>? = null,
        private val truncationNotice: String? = null,
        /** 非 null 时每次取数都抛它（错误分支用） */
        private val failure: Throwable? = null,
    ) : Source {
        override val type: SourceType = SourceType.KOMGA

        val events = mutableListOf<String>()
        val requestedPages = mutableListOf<Int>()
        val requestedSizes = mutableListOf<Int>()

        private fun all(): List<BrowseEntry> =
            (0 until total).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) }

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
            events += "listEntries"
            failure?.let { throw it }
            return all()
        }

        override suspend fun snapshotEntries(containerId: String?, sort: SortMode): List<BrowseEntry>? {
            events += "snapshot"
            return snapshot
        }

        override suspend fun listEntriesPage(
            containerId: String?,
            sort: SortMode,
            page: Int,
            size: Int,
        ): BrowseEntryPage {
            events += "fetch:$page"
            failure?.let { throw it }
            requestedPages += page
            requestedSizes += size
            // 切片走生产的同一份算式（与 `BrowsePageLoaderTest` 同一取舍：不再抄一份同形算式）
            return sliceEntryPage(all(), page, size)
        }

        override fun listTruncationNotice(containerId: String?, sort: SortMode): String? {
            events += "notice"
            return truncationNotice
        }

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("本用例不打开书")

        override suspend fun readProgress(bookId: String) = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String) = Neighbors(null, null)
    }

    /** 界面侧的接线（生产里由 `BrowserScreen` 提供）：记下「请求滚到哪」与「哪一刻清提示」 */
    private class Chain(
        val pager: BrowsePageLoader,
        private val source: Source?,
        private val reverse: Boolean = false,
        private val restoredIndex: RestoredScrollIndex = RestoredScrollIndex(),
        private val preloaded: List<BrowseEntry>? = null,
    ) {
        /** 链请求过的滚动目标（[BrowseFirstScreenPorts.requestScrollTo]） */
        val scrollTargets = mutableListOf<Int>()

        /** 界面当下读到的首个可见项索引（放回判据的输入） */
        var currentItemIndex: Int = 0

        /** 取数前清态被调了几次（来源未就绪时不该被调） */
        var fetchStarts: Int = 0

        suspend fun run(generation: Int = 0, restoredIndexNow: Int = 0): BrowseFirstScreenResult? =
            BrowseFirstScreen(
                pager = pager,
                source = source,
                containerId = CONTAINER,
                sort = SortMode.NAME,
                reverse = reverse,
                restoredIndex = restoredIndex,
                preloaded = preloaded,
                ports = BrowseFirstScreenPorts(
                    currentItemIndex = { currentItemIndex },
                    requestScrollTo = { scrollTargets += it },
                    onFetchStart = { fetchStarts++ },
                ),
            ).run(generation = generation, restoredIndexNow = restoredIndexNow)
    }

    private fun entries(prefix: String, count: Int): List<BrowseEntry> =
        (0 until count).map { BrowseEntry(id = "$prefix-$it", name = "$prefix $it", isBook = true, coverUri = null) }

    /** 与生产同形：会话快照在**构造期**落帧（`BrowserScreen` 的 `preloaded` → `snapshot =` 参数） */
    private fun pager(source: Source?, snapshot: List<BrowseEntry>? = null) =
        BrowsePageLoader(source, containerId = CONTAINER, sort = SortMode.NAME, snapshot = snapshot)

    @After
    fun clearEntryNames() {
        // 反向档走两段式枚举、会回填条目名（进程级缓存），用例自己收尾
        ServiceLocator.entryNames.clear()
    }

    @Test
    fun `来源未就绪 只落快照帧 不取数也不收尾`() = runBlocking<Unit> {
        // 不变量 2：帧先于来源守卫。`source` 由 `rememberConnectionSource` 在 IO 上异步解析（首帧必为 null），
        // 而会话快照是同步内存读；落帧排在守卫之后，来源解析的整个窗口里 `pager.loaded` 都是 false，
        // 界面走 `list == null ->「加载中…」`（与 SPEC「同步快照访问器」相左，票 #124 r1 的 P1 回归）。
        val preloaded = entries("old", 3)
        val source = FakeSource(total = 3)
        val chain = Chain(pager(source, preloaded), source = null, preloaded = preloaded)

        val result = chain.run(restoredIndexNow = 600)

        assertNull("来源未就绪：调用方据此跳过收尾（不清提示、不复位下拉指示器）", result)
        assertTrue("快照帧不等来源解析（拿掉落帧在守卫之前这一点即红）", chain.pager.loaded)
        assertEquals(preloaded.map { it.id }, chain.pager.entries.map { it.id })
        assertEquals("帧来自会话快照，连来源都不问（0 请求）", emptyList<String>(), source.events)
        assertEquals("来源未就绪就不动界面态（清态点在守卫之后）", 0, chain.fetchStarts)
    }

    @Test
    fun `正向档 快照帧先于取数 再按帧长取够替换`() = runBlocking<Unit> {
        // 两段式（票 #75）：第一段落快照帧（首帧因此不必等整层枚举），第二段按它的长度取够再替换（票 #125 P1-1）。
        val snapshot = entries("old", 500)
        val source = FakeSource(total = 1000, snapshot = snapshot)
        val chain = Chain(pager(source, snapshot), source = source, preloaded = snapshot)

        chain.run()

        assertEquals(
            "先落快照帧（第一段）再取数（第二段）再问提示；帧被挪到取数之后即红",
            listOf("snapshot", "fetch:0", "notice"),
            source.events,
        )
        assertEquals("下限 = 快照长度 500 ⇒ 一次要 600 条（按页对齐、只问一次）", listOf(600), source.requestedSizes)
        assertEquals("第二段替换首帧：列表不短于快照", 600, chain.pager.entries.size)
        assertEquals("来源就绪后才清提示（与改动前同一处）", 1, chain.fetchStarts)
    }

    @Test
    fun `下限取快照长度与恢复索引加一的较大者 恢复索引更长时按下限取够`() = runBlocking<Unit> {
        // 票 #124：直取档的会话内列表只含第 0 页（票 #119 约束），滚到第 600 条进阅读器再返回时，
        // 只按快照长度（200）取够就只有一页，滚动索引被 Lazy 列表夹到已加载末尾（位置丢失）。
        val pageZero = entries("book", 200)
        val source = FakeSource(total = 1000, snapshot = pageZero)
        val chain = Chain(pager(source, pageZero), source = source, preloaded = pageZero)

        chain.run(restoredIndexNow = 600)

        assertEquals("恢复索引 600 ⇒ 下限 601 ⇒ 一次要 800 条（只看快照长度则要 200）", listOf(800), source.requestedSizes)
        assertTrue("恢复索引要有内容可落（项数 ≥ 601）", chain.pager.entries.size >= 601)
    }

    @Test
    fun `下限取快照长度与恢复索引加一的较大者 快照更长时按快照长度取够`() = runBlocking<Unit> {
        // 票 #125 P1-1：快照就是上次上屏的那份列表（1500 条的目录里停在第 1400 行），
        // 切到第 0 页（200 条）替换它会让恢复的滚动索引落空。
        val snapshot = entries("old", 1500)
        val source = FakeSource(total = 1500, snapshot = snapshot)
        val chain = Chain(pager(source, snapshot), source = source, preloaded = snapshot)

        chain.run(restoredIndexNow = 0)

        assertEquals("下限 = 快照长度 1500（不是一页）⇒ 一次要到整层（按页对齐 = 1600）", listOf(1600), source.requestedSizes)
        assertEquals(1500, chain.pager.entries.size)
        assertFalse("末页落地后不挂尾部触发件", chain.pager.hasMore)
    }

    @Test
    fun `代次只读一次 来源解析重跑不覆盖第一次读到的索引`() = runBlocking<Unit> {
        // 不变量 1：恢复索引先于任何一帧落屏定下来，且**同代只读一次**：`source` 异步解析会让首屏 effect
        // 重跑，第二次读到的索引已被首帧那份短列表夹过（实测 600 → 184）——覆盖了下限就少取 3 页、位置回不去。
        val pageZero = entries("book", 200)
        val index = RestoredScrollIndex()
        val notReady = Chain(
            pager(source = null, snapshot = pageZero),
            source = null,
            restoredIndex = index,
            preloaded = pageZero,
        )

        assertNull("首帧：来源还没解析出来，只落帧", notReady.run(restoredIndexNow = 600))

        val readySource = FakeSource(total = 1000, snapshot = pageZero)
        val ready = Chain(pager(readySource, pageZero), source = readySource, restoredIndex = index, preloaded = pageZero)

        ready.run(restoredIndexNow = 184)

        assertEquals(
            "同一代重跑：下限仍按第一次读到的 600 算（被 184 覆盖则只有 200）",
            listOf(800),
            readySource.requestedSizes,
        )

        // 下拉更新换代（界面换一个新 pager，持有者按 scrollResetKey 活过换代）：重读当下位置
        val refreshedSource = FakeSource(total = 1000, snapshot = pageZero)
        val refreshed = Chain(pager(refreshedSource, pageZero), source = refreshedSource, restoredIndex = index, preloaded = pageZero)

        refreshed.run(generation = 1, restoredIndexNow = 0)

        assertEquals("换代重读：在顶部刷新只取快照长度那一档", listOf(200), refreshedSource.requestedSizes)
    }

    @Test
    fun `取够之后把位置放回去 短帧夹过的索引回到恢复索引`() = runBlocking<Unit> {
        // 票 #124 r2：短帧测量已把索引夹到已加载末尾（实测 600 → 184），列表涨长不会自己回去，
        // 因此取够之后要显式把容器请求回恢复索引（上限 = 已加载条目 + 尾部触发件那 1 行 = 801 项）。
        val pageZero = entries("book", 200)
        val source = FakeSource(total = 1000, snapshot = pageZero)
        val chain = Chain(pager(source, pageZero), source = source, preloaded = pageZero)
        chain.currentItemIndex = 184

        chain.run(restoredIndexNow = 600)

        assertEquals("恢复到 600：取够后把位置请求回 600", listOf(600), chain.scrollTargets)
        assertEquals("取够 800 条（下限 601 按页对齐）", 800, chain.pager.entries.size)
        assertTrue("后面还有 ⇒ 放回的上限里多算尾部触发件那 1 行", chain.pager.hasMore)
    }

    @Test
    fun `位置还在或这一层没那么长时不动位置`() = runBlocking<Unit> {
        val pageZero = entries("book", 200)
        val source = FakeSource(total = 1000, snapshot = pageZero)
        val chain = Chain(pager(source, pageZero), source = source, preloaded = pageZero)
        chain.currentItemIndex = 600 // 用户自己已经滚到恢复索引之后

        chain.run(restoredIndexNow = 600)

        assertEquals("位置还在（含用户自己滚过去）：不抢用户的滚动", emptyList<Int>(), chain.scrollTargets)
    }

    @Test
    fun `截断提示行计入放回坐标 且提示在取数之后才问`() = runBlocking<Unit> {
        // 截断提示是列表/网格的第 0 行，占一个 Lazy 项：不计进上限，最末一条的放回就被
        // `restoredIndex < loadedItems` 挡掉（`BrowseScrollRestoreTest` 的同名用例钉的是这条算式）。
        // 它同时是对刚跑完这次取数的记账（纯内存读、不发起请求），因此只排在取数之后。
        val pageZero = entries("book", 200)
        val source = FakeSource(total = 200, snapshot = pageZero, truncationNotice = "只显示了前 200 条")
        val chain = Chain(pager(source, pageZero), source = source, preloaded = pageZero)
        chain.currentItemIndex = 184

        val result = chain.run(restoredIndexNow = 200)

        assertEquals("上限 = 200 条 + 提示 1 行 = 201 ⇒ 恢复到最末一条（项 200）成立", listOf(200), chain.scrollTargets)
        assertEquals("提示来自同一次取数、交回界面态", "只显示了前 200 条", result?.truncationNotice)
        assertEquals("取数之后才问提示", listOf("snapshot", "fetch:0", "notice"), source.events)
        assertNull("成功那一路没有错误", result?.error)
    }

    @Test
    fun `反向档先落快照再整份取完 不做按需加载也不放回位置`() = runBlocking<Unit> {
        // 方向要对整份列表翻转，追加页复现不了「从尾到头」⇒ 反向档仍一次取完（票 #119 步骤 3 只在正向档做
        // 按需加载），也不放回位置（整份落屏，索引不会被短帧夹过）。
        val snapshot = entries("old", 500)
        val source = FakeSource(total = 1000, snapshot = snapshot)
        val chain = Chain(pager(source, snapshot), source = source, reverse = true, preloaded = snapshot)
        chain.currentItemIndex = 184

        chain.run(restoredIndexNow = 600)

        assertEquals(
            "第一段快照帧、第二段整层枚举（不按页）、最后问提示",
            listOf("snapshot", "listEntries", "notice"),
            source.events,
        )
        assertEquals("整份上屏、不切首屏", 1000, chain.pager.entries.size)
        assertFalse("反向档不挂尾部触发件", chain.pager.hasMore)
        assertEquals("反向档不放回位置", emptyList<Int>(), chain.scrollTargets)

        chain.pager.loadNextPage()

        assertEquals("整份落地后不再取页（尾部触发件无从触发）", listOf("snapshot", "listEntries", "notice"), source.events)
    }

    @Test
    fun `首屏之后按需加载仍能续页`() = runBlocking<Unit> {
        // 首屏链只管第一段：取够后尾部触发件仍按页码续页（票 #119 步骤 3），界面据此不再受首屏那份列表限制。
        val source = FakeSource(total = 1000)
        val chain = Chain(pager(source, null), source = source)

        chain.run()

        assertTrue("后面还有下一页", chain.pager.hasMore)
        assertEquals("下一页页码（尾部触发件 effect 的键）", 1, chain.pager.nextPage)

        chain.pager.loadNextPage()

        assertEquals("尾部触发才取第 2 页", listOf(0, 1), source.requestedPages)
        assertEquals(400, chain.pager.entries.size)
    }

    @Test
    fun `取数抛错时交回错误信息 且不当截断提示`() = runBlocking<Unit> {
        val source = FakeSource(total = 10, failure = IOException("磁盘掉线"))
        val chain = Chain(pager(source, null), source = source)

        val result = chain.run()

        assertEquals(
            "错误信息原样交回界面（失败那一路没有截断提示可谈）",
            BrowseFirstScreenResult(truncationNotice = null, error = "磁盘掉线"),
            result,
        )
    }
}

/** 用例里的容器 id（来源侧只透传，不关心具体值） */
private const val CONTAINER = "container"
