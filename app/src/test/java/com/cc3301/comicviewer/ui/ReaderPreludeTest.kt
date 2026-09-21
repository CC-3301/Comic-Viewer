package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.BookOpening
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.PageData
import com.cc3301.comicviewer.core.source.ReadingProgress
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.commitOpeningProgress
import com.cc3301.comicviewer.core.source.openForReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开前置（票 #108 E1-A）：点击书时先在书柜页把「打开 + 首批解码」做完，阅读页再同步取走。
 *
 * 维护者现象：点开一本书先出现黑底「准备打开」整页。修法是**留在书柜页等**（等首批解码），就绪后一次性切页；
 * 因此这三件事必须成立：① 前置确实按**这本书自己的落点**开始解；② 解的**不止落点那一页**
 * （票面原话「附近几页加载完成后再切过去」，条漫首屏通常不止一页）；③ 前置是**可兑现一次**的槽
 * （否则反复取用会让阅读页拿到过期句柄）。
 *
 * 未覆盖：切页那一帧到底是不是图片（组合期首帧）——本仓库没有 Compose UI 测试基建，
 * 按 SPEC 的 Testing Decisions 走真机验收，残余风险写进 `evidence-impl.md`。
 */
class ReaderPreludeTest {

    private val store = InMemoryProgressStore()

    /** 一本书 3 页（root 下只有图片 → root 本身是一本书） */
    private fun source(): Source = DocumentTreeSource(
        backend = FakeTreeBackend(
            fakeDir("root").add(
                fakeFile("root/1.jpg"),
                fakeFile("root/2.jpg"),
                fakeFile("root/3.jpg"),
            ),
        ),
        progressStore = store,
    )

    @Test
    fun `前置按这本书自己的落点开始解首批`() = runTest {
        val src = source()
        store.write("root", 2, 3) // 读到第 3 页
        val decoded = mutableListOf<Triple<String, Int, Int>>()

        val opening = preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { handle, index, width ->
            decoded += Triple(handle.id, index, width)
        }

        assertEquals("落点是这本书自己的进度", 2, opening.startIndex)
        assertEquals("前置句柄就是这本书的", "root", opening.handle.id)
        assertEquals(
            "从落点起解（它是末页，只剩这一页），且用阅读页的目标宽度",
            listOf(Triple("root", 2, 1080)),
            decoded,
        )
    }

    @Test
    fun `首批是落点加随后页 不是只解落点那一页`() = runTest {
        // 票面原话「等打开、并且附近几页加载完成后再切过去」：只解落点那一页的话，
        // 切过去后条漫首屏的其余页仍要现解（占位一波波补齐）
        val src = source() // 3 页
        store.write("root", 0, 3)
        val decoded = mutableListOf<Int>()

        preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { _, index, _ ->
            decoded += index
        }

        assertEquals("落点在第 1 页 ⇒ 连解 1、2、3 页", listOf(0, 1, 2), decoded)
    }

    @Test
    fun `末页附近只解剩下的那几页`() = runTest {
        val src = source()
        store.write("root", 1, 3) // 落点第 2 页
        val decoded = mutableListOf<Int>()

        preloadReaderOpening(src, "root", alwaysFirstPage = false, targetWidthPx = 1080) { _, index, _ ->
            decoded += index
        }

        assertEquals("第 2 页起只剩 2 页可解，不越界", listOf(1, 2), decoded)
    }

    @Test
    fun `开启始终从第一页时首批从第一页起解`() = runTest {
        val src = source()
        store.write("root", 2, 3)
        val decoded = mutableListOf<Int>()

        preloadReaderOpening(src, "root", alwaysFirstPage = true, targetWidthPx = 1080) { _, index, _ ->
            decoded += index
        }

        assertEquals("开关开启 ⇒ 首批从第 1 页起（这是这本书的全部 3 页）", listOf(0, 1, 2), decoded)
    }

    @Test
    fun `前置解的页序夹在两端`() {
        assertEquals("常规：落点起连续 3 页", listOf(1, 2, 3), preloadPageIndices(1, 10))
        assertEquals("末页附近：只剩剩下的页", listOf(8, 9), preloadPageIndices(8, 10))
        assertEquals("最后一页：只解它一个", listOf(9), preloadPageIndices(9, 10))
        assertEquals("只有 2 页的书全解", listOf(0, 1), preloadPageIndices(0, 2))
        assertEquals("空书不解码", emptyList<Int>(), preloadPageIndices(0, 0))
        assertEquals("落点越界也夹回界内", listOf(1), preloadPageIndices(7, 2))
    }

    @Test
    fun `首批里一页失败就停 不会把余下的都试一遍`() = runTest {
        val src = source()
        val attempts = mutableListOf<Int>()

        val opening = preloadReaderOpening(src, "root", alwaysFirstPage = true, targetWidthPx = 1080) { _, index, _ ->
            attempts += index
            if (index >= 1) throw IllegalStateException("来源在解码这一步断了")
        }

        assertEquals("第 2 页失败后不再试第 3 页（同一本书后续页多半同样失败）", listOf(0, 1), attempts)
        assertEquals("打开成功即前置有效（解码失败只吞掉，不把人留在书柜页）", 3, opening.handle.pageCount)
    }

    @Test
    fun `前置被取消时不留下覆盖进度的副作用`() = runTest {
        // 票 #110：点开一本书后立刻取消（改点另一本 / 返回 / 切走）时，前置已经在跑的「开书 + 覆盖进度」
        // 不得留下痕迹。取消只可能发生在挂起点（首批解码），此处让解码永远挂着，取消后核对进度存储。
        val src = source()
        store.write("root", 2, 3) // 读到第 3 页
        val decoding = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            preloadReaderOpening(src, "root", alwaysFirstPage = true, targetWidthPx = 1080) { _, _, _ ->
                decoding.complete(Unit)
                gate.await() // 卡在首批解码上：取消从这一刻发生（此时「开书」已完成）
            }
        }
        decoding.await()
        job.cancelAndJoin()

        assertEquals(
            "点了又取消：进度不得被改写（书柜上的「在读」/进度也不该出现）",
            2,
            store.read("root")?.pageIndex,
        )
    }

    @Test
    fun `切进阅读页那一刻才落地覆盖进度`() = runTest {
        // 票 #110 的另一半：推迟的写不能丢——阅读页取走前置（= 真的切进阅读页）时必须把进度覆盖为第 1 页
        val src = source()
        store.write("root", 2, 3)
        val opening = preloadReaderOpening(src, "root", alwaysFirstPage = true, targetWidthPx = 1080) { _, _, _ -> }

        assertEquals("前置阶段只开书解码，不动进度", 2, store.read("root")?.pageIndex)
        commitOpeningProgress(src, "root", alwaysFirstPage = true, opening)
        assertEquals(
            "阅读页取走前置时才覆盖为第 1 页（进入马上退出也只算读了 1 页）",
            0,
            store.read("root")?.pageIndex,
        )
    }

    @Test
    fun `前置槽带连接身份 换连接后旧前置不得被取走`() = runTest {
        // 票 #110：书 id 只在对应连接内有效（ServiceLocator 的 currentConnId 契约）。只按书 id 认主时，
        // 先在来源 A 点开编号 X 的书（前置还没被取走就取消/超时），再到来源 B 点开编号也是 X 的书，
        // B 的阅读页会取走 A 的句柄（页数/正文来自另一个库）。
        val src = source()
        val prelude = ReaderPrelude()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        prelude.put(connId = 1, bookId = "root", ReaderPreludeEntry(opening, alwaysFirstPage = false))

        assertNull("另一连接的同 id 书不得取走", prelude.take(connId = 2, bookId = "root"))
        assertSame("错配的取用不清槽，本连接仍能取到", opening, prelude.take(connId = 1, bookId = "root")?.opening)
    }

    @Test
    fun `前置只兑现一次`() = runTest {
        val src = source()
        val prelude = ReaderPrelude()
        prelude.put(connId = 1, bookId = "root", ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), false))

        assertNotNull("第一次取到", prelude.take(connId = 1, bookId = "root"))
        assertNull("取走即清槽：同一次打开只兑现一次", prelude.take(connId = 1, bookId = "root"))
    }

    @Test
    fun `别的书的前置不认 也不清槽`() = runTest {
        val src = source()
        val prelude = ReaderPrelude()
        prelude.put(connId = 1, bookId = "root", ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), false))

        assertNull("导航参数与槽位错配时不把 A 的句柄交给 B", prelude.take(connId = 1, bookId = "root/a"))
        assertNotNull("错配的取用不清槽，本主儿仍能取到", prelude.take(connId = 1, bookId = "root"))
    }

    @Test
    fun `连点另一本会覆盖槽位`() = runTest {
        val src = DocumentTreeSource(
            backend = FakeTreeBackend(
                fakeDir("root").add(
                    fakeDir("root/a").add(fakeFile("root/a/1.jpg")),
                    fakeDir("root/b").add(fakeFile("root/b/1.jpg"), fakeFile("root/b/2.jpg")),
                ),
            ),
            progressStore = store,
        )
        val prelude = ReaderPrelude()
        prelude.put(connId = 1, bookId = "root/a", ReaderPreludeEntry(openForReading(src, "root/a", alwaysFirstPage = false), false))
        prelude.put(connId = 1, bookId = "root/b", ReaderPreludeEntry(openForReading(src, "root/b", alwaysFirstPage = false), false))

        assertTrue("后来者的前置生效", prelude.take(connId = 1, bookId = "root/b")?.opening?.handle?.id == "root/b")
        assertNull("被覆盖的那本不再有前置", prelude.take(connId = 1, bookId = "root/a"))
    }

    // ---------- r5/r6：有界等待 + 放行（真机「点了没反应、卡在书柜」的真因） ----------

    @Test
    fun `前置成功先交句柄再放行`() = runTest {
        val src = source()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        var ready: BookOpening? = null
        var navigated = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { opening },
            onReady = { ready = it },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertSame("就绪的句柄交给阅读页（省掉它自己那次打开）", opening, ready)
        assertTrue("就绪后照常放行", navigated)
    }

    @Test
    fun `前置拿不到前置也放行`() = runTest {
        var navigated = false
        var readyCalled = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { null }, // 来源还没解析完
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("来源未就绪不是错误，照样进阅读页", navigated)
        // 守卫写成可观察状态：`onReady` 里直接 error(...) 会被本函数内部的 catch(Throwable) 吞掉，
        // 用例即使实现错误地交了句柄也永远绿（票 #108 r5 评审 standards P2-3）
        assertFalse("拿不到前置时不得交句柄", readyCalled)
    }

    @Test
    fun `前置抛错也放行`() = runTest {
        var readyCalled = false
        var navigated = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { throw IllegalStateException("SMB 断链") },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("前置失败必须进阅读页（那里有失败提示与重试，书柜页没有提示位）", navigated)
        assertFalse("失败的这次不交句柄", readyCalled)
    }

    @Test
    fun `前置超时也放行 且等待被上限截断`() = runTest {
        var readyCalled = false
        var navigated = false
        var preloadFinished = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = 100,
            preload = {
                delay(10_000) // 慢来源：取页长时间不返回
                preloadFinished = true
                null
            },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("超时也必须进阅读页（进去后由阅读器自己显示加载态）", navigated)
        assertFalse("等待被上限截断：没有等完前置那 10s", preloadFinished)
        assertFalse("超时的那次不交句柄", readyCalled)
    }

    @Test
    fun `前置体阻塞不响应取消时也到点放行`() = runTest {
        // 真机来源里 Komga 的打开/取页是**阻塞** OkHttp（调用期间协程在运行、不在挂起），取消要等它自己返回。
        // 上限因此必须包在**等待侧**（`deferred.await()` 是可取消挂起点），不能包在阻塞的前置体上。
        val blocked = CompletableDeferred<Unit>()
        val workScope = CoroutineScope(Dispatchers.Default)
        var navigated = false
        var preloadFinished = false
        var readyCalled = false

        awaitReaderPrelude(
            workScope = workScope,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = {
                withContext(NonCancellable) { blocked.await() } // 阻塞且不响应取消
                preloadFinished = true
                null
            },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("阻塞体拦不住 1.5s 放行（上限包的是等待）", navigated)
        assertFalse("到点时前置体还没返回", preloadFinished)
        assertFalse("阻塞的前置没有句柄可交", readyCalled)
        blocked.complete(Unit) // 收尾：让后台工作结束，不留下悬挂的协程
        workScope.cancel()
    }

    @Test
    fun `被取消时不导航`() = runTest {
        // 仓库口径（`ui/Cancellation.kt` 票 #26 / `docs/SPEC.md:208`）：取消照常传播，**不在取消后导航**。
        // 本函数的取消只可能来自「被后一次点击顶替」与「已离开组合」，两处都不该把人拉进阅读页。
        var navigated = false
        var readyCalled = false
        val job = launch {
            awaitReaderPrelude(
                workScope = this@runTest,
                timeoutMillis = 3_600_000, // 远大于取消：这一条不是超时路径
                preload = {
                    delay(10_000)
                    null
                },
                onReady = { readyCalled = true },
                isRequestCurrent = { true },
                navigate = { navigated = true },
            )
        }

        runCurrent()
        job.cancelAndJoin()

        assertFalse("取消不是「放行」：组合已销毁/已被顶替时不得导航", navigated)
        assertFalse("被取消的那次不交句柄", readyCalled)
    }

    @Test
    fun `守卫为假时不导航`() = runTest {
        // 第二道守卫（组合仍存活 + 仍是当前那次点击）：取消已经挡住绝大多数，这一道防「取消还没送达」的窄窗口
        val src = source()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        var ready: BookOpening? = null
        var navigated = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { opening },
            onReady = { ready = it },
            isRequestCurrent = { false }, // 已离开组合 / 已被后一次点击顶替
            navigate = { navigated = true },
        )

        assertSame("就绪的句柄照旧交出来（阅读页下次进来还能用）", opening, ready)
        assertFalse("守卫为假时不得导航", navigated)
    }

    @Test
    fun `前置等待上限不超过维护者要求的 1_5 秒`() {
        assertTrue(
            "维护者口径：超时上限 ≤1.5s（进去后由阅读器自己显示加载态），当前 $PRELUDE_TIMEOUT_MILLIS",
            PRELUDE_TIMEOUT_MILLIS <= 1_500,
        )
    }

    // ---------- 票 #111：不在浏览页点书的三条入口的切页前置（启动还原 / 抽屉「阅读器」/ 读内换书） ----------

    /**
     * 本组用例的替身来源：**只走测试调度器**。
     *
     * 不能直接用 [source]（`DocumentTreeSource`）：它的 `openBook` 内部自带 `withContext(Dispatchers.IO)`
     * （`DocumentTreeSource.kt` 的 `readWithinDeadline`），前置工作会跑出测试调度器；`runTest` 的虚拟时钟
     * 此时没有任何可跑的任务，`withTimeoutOrNull` 的到点任务就成了下一个事件——**虚拟时间立刻跳到超时**，
     * 于是「就绪 → 先入槽再切页」这条路径永远观测不到（用例会绿错方向，或干脆红）。同一结论见
     * `ReaderEntryLandingTest` 的 `ThreadRecordingSource` 注释。
     *
     * 只把「打开书 / 读进度」两条换成纯实现（前置要的落点就从这两条来），其余照旧委托给真实来源。
     */
    private class SchedulerBoundSource(delegate: Source, private val pageCount: Int = 3) : Source by delegate {
        override suspend fun openBook(bookId: String): BookHandle = SchedulerBoundHandle(bookId, pageCount)

        override suspend fun readProgress(bookId: String): ReadingProgress? = null
    }

    /** 上者的句柄：页数由用例给定，字节取用不在本组用例的范围内（解码走注入的 decodePage 替身） */
    private class SchedulerBoundHandle(override val id: String, override val pageCount: Int) : BookHandle {
        override suspend fun loadPage(index: Int): PageData =
            throw UnsupportedOperationException("本组用例不取页字节（解码由注入的替身完成）")
    }

    private fun schedulerBoundSource(pageCount: Int = 3): Source = SchedulerBoundSource(source(), pageCount)

    @Test
    fun `三条入口就绪的那份先入槽再切阅读页`() = runTest {
        // 顺序是这里的承重点：入槽在导航**之前**，阅读页组合期才能同步 take 到前前置；
        // 反过来的话它取不到、又退回黑底「准备打开」（正是本票要收掉的那一帧）。
        val src = schedulerBoundSource() // 3 页
        val prelude = ReaderPrelude()
        var slotAtEnter: ReaderPreludeEntry? = null

        preloadThenEnterReader(
            workScope = this,
            prelude = prelude,
            source = src,
            connId = 4,
            bookId = "root",
            targetWidthPx = { 1080 },
            alwaysFirstPage = false,
            isRequestCurrent = { true },
            enterReader = { slotAtEnter = prelude.take(4, "root") },
            // 虚拟调度器：前置工作必须跑在虚拟时间里，断言才不受线程竞争影响（生产默认 = Dispatchers.IO）
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            decodePage = { _, _, _ -> },
        )

        assertEquals(
            "切页那一刻槽里已经有这本的前置（阅读页取到就不自己重开书）",
            "root",
            slotAtEnter?.opening?.handle?.id,
        )
    }

    @Test
    fun `三条入口的宽度在开跑这一刻读 不是调用点的组合期快照`() = runTest {
        // 启动落地那条的调用点在首帧布局**之前**（那时 `View.width` 还是 0）：宽度必须是调用时重读的，
        // 否则前置按宽度 1 解一批图、与阅读页取的缓存键不命中，切过去仍要重解（黑底重现）。
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var widthReads = 0
        val widths = mutableListOf<Int>()

        preloadThenEnterReader(
            workScope = this,
            prelude = prelude,
            source = src,
            connId = 4,
            bookId = "root",
            targetWidthPx = {
                widthReads++
                1080
            },
            alwaysFirstPage = false,
            isRequestCurrent = { true },
            enterReader = { },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            decodePage = { _, _, width -> widths += width },
        )

        assertEquals("宽度只在前置开跑时读一次（不是导航前的组合期值）", 1, widthReads)
        assertEquals("首批就按这一刻的宽度解", listOf(1080, 1080, 1080), widths)
    }

    @Test
    fun `三条入口慢来源到点也切页 且不留半份前置`() = runTest {
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var entered = false

        preloadThenEnterReader(
            workScope = this,
            prelude = prelude,
            source = src,
            connId = 4,
            bookId = "root",
            targetWidthPx = { 1080 },
            alwaysFirstPage = false,
            isRequestCurrent = { true },
            enterReader = { entered = true },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            decodePage = { _, _, _ -> delay(10_000) }, // 慢来源：首批解码长时间不返回
            timeoutMillis = 100,
        )

        assertTrue("超时必须放行（进去后由阅读页自己显示加载态）", entered)
        assertNull("超时的那次不交前置（半份也不交）", prelude.take(4, "root"))
    }

    @Test
    fun `三条入口被取消时不导航也不留前置`() = runTest {
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var entered = false
        val decoding = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            preloadThenEnterReader(
                workScope = this@runTest,
                prelude = prelude,
                source = src,
                connId = 4,
                bookId = "root",
                targetWidthPx = { 1080 },
                alwaysFirstPage = false,
                isRequestCurrent = { true },
                enterReader = { entered = true },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                decodePage = { _, _, _ ->
                    decoding.complete(Unit)
                    gate.await() // 卡在首批解码上：取消从这一刻发生
                },
                timeoutMillis = 3_600_000, // 远大于取消：这一条不是超时路径
            )
        }
        decoding.await()
        job.cancelAndJoin()

        assertFalse("取消（离开组合 / 离开这个入口）不是放行：不得导航", entered)
        assertNull("取消的那次不交前置", prelude.take(4, "root"))
        gate.complete(Unit) // 收尾：放开解码，不留下悬挂的工作
    }

    @Test
    fun `拿不到连接 id 时不等也不做前置工作`() = runTest {
        // 前置槽按「连接 id + 书 id」认主（票 #110）：键拿不到就无处可交，白等 1.5s 只是纯延迟
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var decodeCalls = 0
        var entered = false

        preloadThenEnterReader(
            workScope = this,
            prelude = prelude,
            source = src,
            connId = null,
            bookId = "root",
            targetWidthPx = { 1080 },
            alwaysFirstPage = false,
            isRequestCurrent = { true },
            enterReader = { entered = true },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            decodePage = { _, _, _ -> decodeCalls++ },
            timeoutMillis = 3_600_000, // 远大于一切：这一条不是超时路径
        )

        assertTrue("直接放行（交给阅读页自己那一次打开）", entered)
        assertEquals("没有键就不做前置工作", 0, decodeCalls)
    }
}
