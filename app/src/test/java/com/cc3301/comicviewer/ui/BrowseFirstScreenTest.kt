package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BrowseEntryPage
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.PerfTiming
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览取数模块的首屏链：**单段取数 → 取够后交回滚动目标（把位置放回去）**，用假来源整体跑一遍。
 *
 * 为什么要有这一份用例：这些约束**全是顺序约束**（恢复索引先于取数、清态先于取数、截断提示在取数之后、
 * 放回在取数之后），而 `BrowsePageLoaderTest` 与 `BrowseScrollRestoreTest` 各自只测得到链上的一段
 *——合起来的时序没有接缝。
 *
 * 判别力（每条用例都对应一处「改坏即红」）：
 * - 把清态（`error` / `truncationNotice` 两行的替换）挪到取数之后 ⇒ `清态排在取数之前` 红；
 * - 把清态挪到来源守卫之前（来源未就绪也照清）⇒ `来源未就绪时不动界面态` 红；
 * - 把截断提示挪到取数之前 ⇒ `截断提示在取数之后才问` 红；
 * - 把首屏取数改回固定一页（不看下限）⇒ `下限 = 恢复索引 + 1…` 与 `代次只读一次…` 红；
 * - 去掉 `RestoredScrollIndex` 的代次记忆（每帧都覆盖）⇒ `代次只读一次…` 红；
 * - 去掉放回、或丢掉放回上限里的截断提示行 / 尾部触发件行 ⇒ `取够之后…` / `截断提示行…` / `放回上限含尾部触发件行…` 红；
 * - 没请求放回也标记「位置已放回」⇒ `位置还在或这一层没那么长时不动位置` 红；
 * - 预取那一侧也去算放回目标（于是落一行放回打点）⇒ `预取不放回 也不落放回打点行` 红；
 * - 下拉更新（代次非 0）不把位置固定在顶部 ⇒ `下拉更新落地后回到顶部` 红；
 * - 反向档也走按需加载（会丢「从尾到头」的顺序）⇒ `反向档…` 红；
 * - 取数被取消就上抛（界面那一侧不再收尾）⇒ `取数被取消也记成错误` 红。
 */
class BrowseFirstScreenTest {

    /** 会话状态的条目名缓存（生产那份由 `MainActivity` 提供）：枚举时回填进它 */
    private val entryNames = ConcurrentHashMap<String, String>()

    /**
     * 假来源：把**顺序**记进 [events]（`fetch:N` = 取第 N 页 / `listEntries` = 整层枚举 /
     * `notice` = 问截断提示），因此「提示在取数之后」这类时序断言比对的是**真实次序**，
     * 而不是实现顺手满足的同义反复。[onFetch] 给的是**取数那一刻的现场**（界面态读到什么），
     * 「清态先于取数」靠它钉。
     */
    private class FakeSource(
        private val total: Int,
        /** 本次取数要交回的截断提示；用例可以在取数途中改它（钉「提示在取数之后才问」） */
        var truncationNotice: String? = null,
        /** 非 null 时每次取数都抛它（错误分支用） */
        var failure: Throwable? = null,
        /** 真时取数挂住不返回（用例据此在取数在飞的时候取消） */
        private val hang: Boolean = false,
        /** 非 null 时一次只给这么多条、却仍说「后面还有」（模拟少给的来源，见 `放回上限含尾部触发件行…`） */
        private val servedPerRequest: Int? = null,
        /** 每次按页取数时回调一次（页码）：用来读「取数那一刻的现场」 */
        var onFetch: (Int) -> Unit = {},
    ) : Source {
        override val type: SourceType = SourceType.KOMGA

        /** 有序事件表（与取数器共用的那份现场一起判时序） */
        val events = mutableListOf<String>()
        val requestedPages = mutableListOf<Int>()
        val requestedSizes = mutableListOf<Int>()

        private fun all(): List<BrowseEntry> =
            (0 until total).map { BrowseEntry(id = "book-$it", name = "Book $it", isBook = true, coverUri = null) }

        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> {
            events += "listEntries"
            failure?.let { throw it }
            if (hang) awaitCancellation()
            return all()
        }

        override suspend fun listEntriesPage(
            containerId: String?,
            sort: SortMode,
            page: Int,
            size: Int,
        ): BrowseEntryPage {
            events += "fetch:$page"
            requestedPages += page
            requestedSizes += size
            onFetch(page)
            failure?.let { throw it }
            if (hang) awaitCancellation()
            // 切片走生产的同一份算式（与 `BrowsePageLoaderTest` 同一取舍：不再抄一份同形算式）；
            // 来源「少给」那一档一次只交这么多条，但照旧说「后面还有」——`hasNext` 的契约不看本页是否满页
            return servedPerRequest
                ?.let { BrowseEntryPage(entries = all().take(it), hasNext = true) }
                ?: sliceEntryPage(all(), page, size)
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

    /**
     * 界面侧的台架：本层取数器 + 界面真正持有的两样东西（当下读到的滚动索引、本代恢复索引的持有者）。
     * 界面把这两样交给 [BrowsePageLoader.loadFirstScreen]，拿回滚动目标。
     */
    private class Layer(
        val pager: BrowsePageLoader,
        val restoredIndex: RestoredScrollIndex = RestoredScrollIndex(),
    ) {
        /** 界面当下读到的首个可见项索引（放回判据的输入） */
        var currentItemIndex: Int = 0

        suspend fun loadFirstScreen(generation: Int = 0, restoredIndexNow: Int = 0): BrowseFirstScreenResult? =
            pager.loadFirstScreen(
                generation = generation,
                restoredIndex = restoredIndex,
                restoredIndexNow = restoredIndexNow,
                currentItemIndex = currentItemIndex,
            )
    }

    private fun loader(source: Source?, reverse: Boolean = false) =
        BrowsePageLoader(
            source = source,
            containerId = CONTAINER,
            sort = SortMode.NAME,
            reverse = reverse,
            entryNames = entryNames,
        )

    @Test
    fun `来源未就绪 不取数也不收尾`() = runBlocking<Unit> {
        // `source` 由 `rememberConnectionSource` 在 IO 上异步解析（首帧必为 null）：本模块不产能，
        // 来源没解析出来就什么都不做，界面因此走「加载中」（延迟 500ms 才现身）。
        val source = FakeSource(total = 3)
        val layer = Layer(loader(source = null))

        val result = layer.loadFirstScreen(restoredIndexNow = 600)

        assertNull("来源未就绪：调用方据此跳过收尾（不复位下拉指示器）", result)
        assertFalse("一帧都没落（本模块不拿旧列表当首帧）", layer.pager.loaded)
        assertEquals(emptyList<BrowseEntry>(), layer.pager.entries)
        assertEquals("连来源都不问（0 请求）", emptyList<String>(), source.events)
    }

    @Test
    fun `清态排在取数之前`() = runBlocking<Unit> {
        // 清态（error / truncationNotice 两行的替换）必须仍在**来源就绪之后、取数之前**：先用两次落地
        // 把两条提示分别立起来，再让本次取数在请求发出那一刻读它们——那一刻必须已经清掉（挪到取数之后就红）。
        // 来源每次只交 100 条（不足下限 601）⇒ 每次都真的发请求，现场才有得看。
        val source = FakeSource(total = 1000, truncationNotice = "上一次的提示", servedPerRequest = 100)
        val pager = loader(source)
        val atFetch = mutableListOf<Pair<String?, String?>>()
        source.onFetch = { atFetch += pager.error to pager.truncationNotice }
        val holder = RestoredScrollIndex()

        pager.loadFirstScreen(restoredIndex = holder, restoredIndexNow = 600)
        assertEquals("上一次的提示", pager.truncationNotice)

        source.failure = IOException("磁盘掉线")
        pager.loadFirstScreen(restoredIndex = holder, restoredIndexNow = 600)
        assertEquals("磁盘掉线", pager.error)
        assertNull("取数失败那一路没有截断提示可谈", pager.truncationNotice)

        source.failure = null
        pager.loadFirstScreen(restoredIndex = holder, restoredIndexNow = 600)

        assertEquals(
            "三次取数各记一次现场；取数那一刻两条提示都已清掉（清态 → 取数）",
            listOf<Pair<String?, String?>>(null to null, null to null, null to null),
            atFetch,
        )
        assertNull("成功那一路没有错误", pager.error)
    }

    @Test
    fun `截断提示在取数之后才问`() = runBlocking<Unit> {
        // 它是对刚跑完这次取数的记账（纯内存读、不发起请求）：取数途中才改出来的值也要读到
        //（挪到取数之前就红）。
        val source = FakeSource(total = 200)
        val pager = loader(source)
        source.onFetch = { source.truncationNotice = "取数之后才有" }

        pager.loadFirstScreen(restoredIndex = RestoredScrollIndex())

        assertEquals("途中改出来的值也读到", "取数之后才有", pager.truncationNotice)
    }

    @Test
    fun `来源未就绪时不动界面态`() = runBlocking<Unit> {
        // 清态点在**来源守卫之后**：来源异步解析会让首屏 effect 重跑，重跑那一帧可能又读到
        //「还没解析出来」——那一刻上一代立起来的提示必须原样留着（清态挪到守卫之前就把它们抹了），
        // 也不许碰来源（0 请求）。
        val source = FakeSource(total = 3, truncationNotice = "上一次的提示")
        val pager = loader(source)
        val holder = RestoredScrollIndex()

        pager.loadFirstScreen(restoredIndex = holder)
        assertEquals("上一次的提示", pager.truncationNotice)

        pager.source = null
        var requests = source.events.size
        assertNull("来源未就绪：调用方据此跳过收尾（不复位下拉指示器）", pager.loadFirstScreen(restoredIndex = holder))
        assertEquals("提示没被清掉", "上一次的提示", pager.truncationNotice)
        assertEquals("连来源都不问", requests, source.events.size)

        // 失败那一路立起来的错误同理（另起一台取数器：已落过帧的那一台不再取数）
        source.failure = IOException("磁盘掉线")
        val failing = loader(source)
        failing.loadFirstScreen(restoredIndex = holder)
        assertEquals("磁盘掉线", failing.error)

        failing.source = null
        requests = source.events.size
        assertNull(failing.loadFirstScreen(restoredIndex = holder))
        assertEquals("错误没被清掉", "磁盘掉线", failing.error)
        assertEquals("连来源都不问", requests, source.events.size)
    }

    @Test
    fun `取数被取消也记成错误`() = runBlocking<Unit> {
        // 取消不是「这一份没取到」以外的特例：界面那一侧照旧拿到错误、照旧收尾（下拉指示器要复位）。
        // 上抛的话界面那两行都不跑。
        val source = FakeSource(total = 3, hang = true)
        val pager = loader(source)
        val entered = CompletableDeferred<Unit>()
        source.onFetch = { entered.complete(Unit) }

        val job = launch { pager.loadFirstScreen(restoredIndex = RestoredScrollIndex()) }
        entered.await()
        job.cancelAndJoin()

        assertNotNull("取消也当「这一次没取到」：界面照旧有错误可显示", pager.error)
        assertFalse("一帧都没落", pager.loaded)
    }

    @Test
    fun `下限等于恢复索引加一 不看旧列表长度`() = runBlocking<Unit> {
        // 恢复的滚动索引可能落在很后面（深目录）：只取第 0 页的话索引被夹到已加载末尾（位置丢失）。
        val source = FakeSource(total = 1000)
        val layer = Layer(loader(source))

        layer.loadFirstScreen(restoredIndexNow = 600)

        assertEquals("恢复索引 600 ⇒ 下限 601 ⇒ 一次要 800 条", listOf(800), source.requestedSizes)
        assertTrue("恢复索引要有内容可落（项数 ≥ 601）", layer.pager.entries.size >= 601)
    }

    @Test
    fun `代次只读一次 来源解析重跑不覆盖第一次读到的索引`() = runBlocking<Unit> {
        // 不变量 1：恢复索引先于取数定下来，且**同代只读一次**：`source` 异步解析会让首屏 effect
        // 重跑，第二次读到的索引已经是上一次取数之后的读数——覆盖了下限就会少取几页、位置回不去。
        val index = RestoredScrollIndex()
        val notReady = Layer(loader(source = null), restoredIndex = index)

        assertNull("首帧：来源还没解析出来，什么都不做", notReady.loadFirstScreen(restoredIndexNow = 600))

        val readySource = FakeSource(total = 1000)
        val ready = Layer(loader(readySource), restoredIndex = index)

        ready.loadFirstScreen(restoredIndexNow = 184)

        assertEquals(
            "同一代重跑：下限仍按第一次读到的 600 算（被 184 覆盖则只有 200）",
            listOf(800),
            readySource.requestedSizes,
        )

        // 下拉更新换代：重读当下位置（在顶部就是第 0 页）
        val refreshedSource = FakeSource(total = 1000)
        val refreshed = Layer(loader(refreshedSource), restoredIndex = index)

        refreshed.loadFirstScreen(generation = 1, restoredIndexNow = 0)

        assertEquals("换代重读：在顶部刷新只取第 0 页", listOf(200), refreshedSource.requestedSizes)
    }

    @Test
    fun `下拉更新落地后回到顶部`() = runBlocking<Unit> {
        // 下拉更新的语义是「重新拿一份列表」：取数落地后位置固定在 0，不按离场读数放回
        //（用户此刻就在顶部下拉）。
        val source = FakeSource(total = 1000)
        val layer = Layer(loader(source))
        layer.currentItemIndex = 0

        val result = layer.loadFirstScreen(generation = 1, restoredIndexNow = 600)

        assertEquals("下拉更新那一代：落地后请求回顶部", 0, result?.scrollTarget)
        assertTrue("请求了放回 ⇒ 要标记「位置已放回」（关掉拒写窗口）", result!!.markPlaced)
    }

    @Test
    fun `取够之后把位置放回去 恢复索引回到原位`() = runBlocking<Unit> {
        // 滚动状态是从 0 起建的（取数期间没有一帧），列表涨长不会自己回到离开前的位置，
        // 因此取够之后要交回放回目标（上限 = 已加载条目 + 尾部触发件那 1 行 = 801 项）。
        val source = FakeSource(total = 1000)
        val layer = Layer(loader(source))
        layer.currentItemIndex = 0

        val result = layer.loadFirstScreen(restoredIndexNow = 600)

        assertEquals("恢复到 600：取够后把位置请求回 600", 600, result?.scrollTarget)
        assertTrue("请求了放回 ⇒ 要标记「位置已放回」（关掉拒写窗口）", result!!.markPlaced)
        assertEquals("取够 800 条（下限 601 按页对齐）", 800, layer.pager.entries.size)
        assertTrue("后面还有 ⇒ 放回的上限里多算尾部触发件那 1 行", layer.pager.hasMore)
    }

    @Test
    fun `放回上限含尾部触发件行 只差那一行时仍放回`() = runBlocking<Unit> {
        // SPEC「直取档的取数下限」要求两档的放回上限用 `Lazy` 项坐标（条目 + 截断提示行 + 尾部触发件行）。
        // 正向链的下限保证「尾部还有下一页时条目数 ≥ 恢复索引 + 1」，因此那一行只在**来源少给**的边界上成为决定项：
        // 这里让来源一次只交 600 条（请求的是 800）却仍说 hasNext——契约允许（`hasNext` 不看本页是否满页），
        // `loadFirstPages` 的请求数上限也会在这种来源上提前收工。
        // 此时条目 600 + 尾部触发件 1 行 = 601 项，恢复到项 600 才成立；上限里丢掉那一行（600）目标就变 null ⇒ 能红。
        val source = FakeSource(total = 1000, servedPerRequest = 600)
        val layer = Layer(loader(source))

        val result = layer.loadFirstScreen(restoredIndexNow = 600)

        assertEquals("下限 601 ⇒ 一次要 800 条", listOf(800), source.requestedSizes)
        assertEquals("来源只交 600 条（不足下限）就提前收工", 600, layer.pager.entries.size)
        assertTrue("它仍说后面还有 ⇒ 列表尾部真的挂着触发件那一行", layer.pager.hasMore)
        assertEquals("恢复到项 600：上限靠尾部触发件那一行才够", 600, result?.scrollTarget)
    }

    @Test
    fun `位置还在或这一层没那么长时不动位置`() = runBlocking<Unit> {
        val source = FakeSource(total = 1000)
        val layer = Layer(loader(source))
        layer.currentItemIndex = 600 // 用户自己已经滚到恢复索引之后

        val result = layer.loadFirstScreen(restoredIndexNow = 600)

        assertNull("位置还在（含用户自己滚过去）：不抢用户的滚动", result?.scrollTarget)
        assertFalse(
            "什么都没落地（没有放回目标）：不得把进屏那一下的残留读数标成「位置已放回」",
            result!!.markPlaced,
        )
    }

    @Test
    fun `截断提示行计入放回坐标 且提示在取数之后才问`() = runBlocking<Unit> {
        // 截断提示是列表/网格的第 0 行，占一个 Lazy 项：不计进上限，最末一条的放回就被
        // `restoredIndex < loadedItems` 挡掉（`BrowseScrollRestoreTest` 的同名用例钉的是这条算式）。
        // 它同时是对刚跑完这次取数的记账（纯内存读、不发起请求），因此只排在取数之后。
        val source = FakeSource(total = 200, truncationNotice = "只显示了前 200 条")
        val layer = Layer(loader(source))

        val result = layer.loadFirstScreen(restoredIndexNow = 200)

        assertEquals("上限 = 200 条 + 提示 1 行 = 201 ⇒ 恢复到最末一条（项 200）成立", 200, result?.scrollTarget)
        assertEquals("提示来自同一次取数、交回界面态", "只显示了前 200 条", layer.pager.truncationNotice)
        assertEquals("取数之后才问提示", listOf("fetch:0", "notice"), source.events)
        assertNull("成功那一路没有错误", layer.pager.error)
    }

    /**
     * 交接槽交过来的首屏够用（返回上级在弹栈之前已经真列过一次，结果装槽交给本层）时，
     * 本入口不再列一次：数据已在手上、屏幕上早就有内容，再取一次只是白花一次列目录。
     */
    @Test
    fun `交接槽预取过来的首屏够用 不再取一次`() = runBlocking<Unit> {
        val source = FakeSource(total = 3)
        val prepared = loader(source)
        // 预取那一步（`prepareLayerHandoff` 走的是同一个入口、`restorePosition = false`）：先落一帧，再交给本层
        prepared.loadFirstScreen(restoredIndex = RestoredScrollIndex(), restorePosition = false)
        val layer = Layer(prepared)

        layer.loadFirstScreen(generation = 0, restoredIndexNow = 0)

        assertEquals("只有预取那一次列目录", 1, source.events.count { it.startsWith("fetch:") })
    }

    @Test
    fun `预取不放回 也不落放回打点行`() = runBlocking<Unit> {
        // 预取要的是「只要第 0 页、不放回」：目标层首帧自己会走一遍首屏链，那时才谈得上放回，
        // 也只有那时才产 `phase=apply` 那一行打点。
        val lines = PerfTiming.newRecordedLinesForTest()
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
        var result: BrowseFirstScreenResult? = null
        try {
            val source = FakeSource(total = 1000)
            val pager = loader(source)

            result = pager.loadFirstScreen(restoredIndex = RestoredScrollIndex(), restorePosition = false)
        } finally {
            PerfTiming.forcedForTest = null
            PerfTiming.recordedLinesForTest = null
        }

        assertEquals("预取不放回", null, result?.scrollTarget)
        assertFalse("没有放回目标就不标记「位置已放回」", result!!.markPlaced)
        assertEquals("不产放回那一行打点", emptyList<String>(), lines.filter { it.startsWith("browseRestore") })
    }

    @Test
    fun `反向档整份取完 不做按需加载也不放回位置`() = runBlocking<Unit> {
        // 方向要对整份列表翻转，追加页复现不了「从尾到头」⇒ 反向档仍一次取完（按需加载只在正向档做）；
        // 也不放回位置——整份上屏后列表不比离开前短，放回判据不适用。
        val source = FakeSource(total = 1000)
        val layer = Layer(loader(source, reverse = true))
        layer.currentItemIndex = 184

        val result = layer.loadFirstScreen(restoredIndexNow = 600)

        assertEquals("整层枚举一次，最后问提示", listOf("listEntries", "notice"), source.events)
        assertEquals("整份上屏、不切首屏", 1000, layer.pager.entries.size)
        assertFalse("反向档不挂尾部触发件", layer.pager.hasMore)
        assertNull("反向档不放回位置", result?.scrollTarget)

        layer.pager.loadNextPage()

        assertEquals("整份落地后不再取页（尾部触发件无从触发）", listOf("listEntries", "notice"), source.events)
    }

    @Test
    fun `首屏之后按需加载仍能续页`() = runBlocking<Unit> {
        // 首屏只管第一段：取够后尾部触发件仍按页码续页，界面据此不再受首屏那份列表限制。
        val source = FakeSource(total = 1000)
        val layer = Layer(loader(source))

        layer.loadFirstScreen()

        assertTrue("后面还有下一页", layer.pager.hasMore)
        assertEquals("下一页页码（尾部触发件 effect 的键）", 1, layer.pager.nextPage)

        layer.pager.loadNextPage()

        assertEquals("尾部触发才取第 2 页", listOf(0, 1), source.requestedPages)
        assertEquals(400, layer.pager.entries.size)
    }

    @Test
    fun `滚动恢复的打点行真的被记下来`() = runBlocking<Unit> {
        // 两处打点是设备取数的唯一依据，接线不能只是「写了代码、没人跑过」。
        // 这里钉「放回」那一行（它在首屏链里，能用本文件的假来源台架整条跑）：字段齐全、
        // 代次与恢复索引都取当时的值（设备上就是靠这两项与 `phase=read` 配对）。
        val lines = PerfTiming.newRecordedLinesForTest()
        PerfTiming.forcedForTest = true
        PerfTiming.recordedLinesForTest = lines
        try {
            val source = FakeSource(total = 1000)
            val layer = Layer(loader(source))
            layer.currentItemIndex = 0

            layer.loadFirstScreen(generation = 0, restoredIndexNow = 600)
        } finally {
            PerfTiming.forcedForTest = null
            PerfTiming.recordedLinesForTest = null
        }

        val apply = lines.filter { it.startsWith("browseRestore") }
        assertEquals(1, apply.size)
        assertEquals(
            "browseRestore phase=apply container=container gen=0 restored=600 now=0 loaded=801 target=600",
            apply.single(),
        )
    }

    @Test
    fun `取数抛错时交回错误信息 且不当截断提示`() = runBlocking<Unit> {
        val source = FakeSource(total = 10, failure = IOException("磁盘掉线"))
        val layer = Layer(loader(source))

        val result = layer.loadFirstScreen()

        assertEquals("交回失败（调用方据此不再轮询 `error` 判成败）", BrowseFirstScreenResult.Failed, result)
        assertEquals("错误信息原样交回界面（失败那一路没有截断提示可谈）", "磁盘掉线", layer.pager.error)
        assertNull("失败那一路没有截断提示", layer.pager.truncationNotice)
        assertNull("失败那一路没有放回目标", result?.scrollTarget)
    }
}

/** 用例里的容器 id（来源侧只透传，不关心具体值） */
private const val CONTAINER = "container"
