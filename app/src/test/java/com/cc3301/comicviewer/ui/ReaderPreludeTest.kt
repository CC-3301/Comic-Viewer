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
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.source.commitOpeningProgress
import com.cc3301.comicviewer.core.source.openBookAtLanding
import com.cc3301.comicviewer.core.source.openForReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 打开前置（票 #108 E1-A；票 #122 起**导航先发生**）：点击书时开始「打开 + 首批解码」，导航在点击那一帧就走，
 * 前置在会话级作用域里继续跑，由阅读页侧有界等待（取到就用、到点自己开书）。
 *
 * 维护者现象：点开一本书先出现黑底「准备打开」整页。票 #122 起不再有「留在书柜页等」那一段
 * （滑入立刻开始，等页期间是主题背景色纯色）；因此这三件事必须成立：① 前置确实按**这本书自己的落点**开始解；② 解的**不止落点那一页**
 * （票面原话「附近几页加载完成后再切过去」，条漫首屏通常不止一页）；③ 前置是**可兑现一次**的槽
 * （否则反复取用会让阅读页拿到过期句柄）。
 *
 * 票 #132 起另加一组：**落地入口** [openReaderForLanding] 的组合与顺序（有前置就用那一份 / 没有就自己
 * 开书并领号 / 领号在开书之前）。那几条走的是兜底分支，会读到 `AppSettings`（设置项，非磁盘/网络），
 * 因此本类挂 Robolectric（与 `ReaderEntryLandingTest` / `OpenBookEntryTest` 同一做法）。
 *
 * 未覆盖：切页那一帧到底是不是图片（组合期首帧）——本仓库没有 Compose UI 测试基建，
 * 按 SPEC 的 Testing Decisions 走真机验收，残余风险写进 `evidence-impl.md`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPreludeTest {

    private val store = InMemoryProgressStore()

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        AppSettings.alwaysOpenFirstPage = false
        ServiceLocator.lastRead = null
    }

    @After
    fun tearDown() {
        AppSettings.alwaysOpenFirstPage = false
        // lastRead 是带落盘副作用的会话级静态：同 sandbox 的用例顺序执行，不留脏值
        ServiceLocator.lastRead = null
    }

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

    /**
     * 一次完整的「点书 → 前置到货」入槽（票 #122 r2）：[ReaderPrelude.put] 现在要**这次请求的世代号**，
     * 而世代号由 [ReaderPrelude.begin] 发——用例里这么写才与实际链路同形（点书领号、前置到货入槽）。
     */
    private fun ReaderPrelude.deliver(connId: Long, bookId: String, entry: ReaderPreludeEntry) {
        put(connId, bookId, begin(connId, bookId), entry)
    }

    @Test
    fun `前置槽带连接身份 换连接后旧前置不得被取走`() = runTest {
        // 票 #110：书 id 只在对应连接内有效（ServiceLocator 的 currentConnId 契约）。只按书 id 认主时，
        // 先在来源 A 点开编号 X 的书（前置还没被取走就取消/超时），再到来源 B 点开编号也是 X 的书，
        // B 的阅读页会取走 A 的句柄（页数/正文来自另一个库）。
        val src = source()
        val prelude = ReaderPrelude()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        prelude.deliver(connId = 1, bookId = "root", ReaderPreludeEntry(opening, alwaysFirstPage = false))

        assertNull("另一连接的同 id 书不得取走", prelude.take(connId = 2, bookId = "root"))
        assertSame("错配的取用不清槽，本连接仍能取到", opening, prelude.take(connId = 1, bookId = "root")?.opening)
    }

    @Test
    fun `前置只兑现一次`() = runTest {
        val src = source()
        val prelude = ReaderPrelude()
        prelude.deliver(connId = 1, bookId = "root", ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), false))

        assertNotNull("第一次取到", prelude.take(connId = 1, bookId = "root"))
        assertNull("取走即清槽：同一次打开只兑现一次", prelude.take(connId = 1, bookId = "root"))
    }

    @Test
    fun `别的书的前置不认 也不清槽`() = runTest {
        val src = source()
        val prelude = ReaderPrelude()
        prelude.deliver(connId = 1, bookId = "root", ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), false))

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
        prelude.deliver(connId = 1, bookId = "root/a", ReaderPreludeEntry(openForReading(src, "root/a", alwaysFirstPage = false), false))
        prelude.deliver(connId = 1, bookId = "root/b", ReaderPreludeEntry(openForReading(src, "root/b", alwaysFirstPage = false), false))

        assertTrue("后来者的前置生效", prelude.take(connId = 1, bookId = "root/b")?.opening?.handle?.id == "root/b")
        assertNull("被覆盖的那本不再有前置", prelude.take(connId = 1, bookId = "root/a"))
    }

    // ---------- r5/r6：有界等待 + 放行（真机「点了没反应、卡在书柜」的真因） ----------

    @Test
    fun `导航先发生 前置到货后交句柄`() = runTest {
        // 票 #122 的顺序：**先导航**（点击那一帧，滑入立刻开始），前置工作随后跑完才交句柄——
        // 次序本身是这条用例的承重点（旧序是「先交句柄再放行」，改序后事件次序反转）。
        val src = source()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        val events = mutableListOf<String>()
        var ready: BookOpening? = null

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { opening },
            onReady = {
                ready = it
                events += "ready"
            },
            isRequestCurrent = { true },
            navigate = { events += "navigate" },
        )

        assertEquals("先导航、后交句柄（票 #122 改序）", listOf("navigate", "ready"), events)
        assertSame("就绪的句柄交给阅读页（省掉它自己那次打开）", opening, ready)
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
    fun `导航先于等待发生 取消不再拦得住它`() = runTest {
        // 票 #122 改序：导航在点击那一刻发生（在第一个挂起点之前），因此「取消不导航」不再是本闸门的性质。
        // 取消在新形状下的对应是**落地侧**：阅读页自己的组合消失就不会落地
        // （阅读页侧的有界等待 `ReaderPrelude.await` 随它一起取消，`ReaderScreen` 因此不落地）。
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

        assertTrue("导航发生在取消之前（点了立刻滑）", navigated)
        assertFalse("被取消的那次不交句柄（前置还没到货）", readyCalled)
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
    fun `点开书立刻导航 不等前置完成`() = runTest {
        // 票 #122 的验收用例：导航在点击那一帧发起，前置仍挂着时导航已经发生。
        // 去掉改动（= 回到「先等前置就绪/超时、再导航」的旧序）即红：那时 enteredAtMillis 记到的是超时那一刻。
        val release = CompletableDeferred<Unit>()
        var enteredAtMillis = -1L
        var entered = false
        var readyCalled = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = PRELUDE_TIMEOUT_MILLIS,
            preload = { release.await(); null }, // 前置一直挂着
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = {
                entered = true
                enteredAtMillis = testScheduler.currentTime
            },
        )

        assertTrue("前置还挂着，导航已经发生", entered)
        assertEquals("导航发生在点击那一帧（虚拟时钟没有被推到 1.5s 上限）", 0L, enteredAtMillis)
        assertFalse("前置没到货就不交句柄", readyCalled)
        release.complete(Unit) // 收尾：让后台工作结束，不留下悬挂的协程
    }

    @Test
    fun `点下去就切页 前置到货后入槽`() = runTest {
        // 入槽时机（票 #122）：导航那一刻槽里什么都还没有（前置还在飞），到货后才入槽——
        // 阅读页侧用 `ReaderPrelude.await` 有界等它，取到就不自己重开书。
        val src = schedulerBoundSource() // 3 页
        val prelude = ReaderPrelude()
        val release = CompletableDeferred<Unit>()
        var slotAtEnter: ReaderPreludeEntry? = null

        enterReaderThenPreload(
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
            decodePage = { _, _, _ -> release.await() },
        )

        assertNull("导航那一刻前置还没到货（槽里空着）", slotAtEnter)
        release.complete(Unit)
        runCurrent()
        assertEquals(
            "前置到货后入槽：阅读页侧的有界等待取到它",
            "root",
            prelude.take(4, "root")?.opening?.handle?.id,
        )
    }

    @Test
    fun `四条入口的宽度在开跑这一刻读 不是调用点的组合期快照`() = runTest {
        // 启动落地那条的调用点在首帧布局**之前**（那时 `View.width` 还是 0）：宽度必须是调用时重读的，
        // 否则前置按宽度 1 解一批图、与阅读页取的缓存键不命中，切过去仍要重解（黑底重现）。
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var widthReads = 0
        val widths = mutableListOf<Int>()

        enterReaderThenPreload(
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
    fun `慢来源也点下去就切页 上限只截断等待`() = runTest {
        // 票 #122：慢来源（SMB/Komga 那种秒级取页）不再把人钉在书柜页——导航立刻发生；
        // 上限截断的是调用方的等待，工作仍在后台跑（#108 r6 的「到点后工作不取消」）。
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var entered = false

        enterReaderThenPreload(
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

        assertTrue("慢来源也点下去就切页（不等前置）", entered)
        assertTrue("调用方的等待被 100ms 上限截断", testScheduler.currentTime <= 100)
        assertNull("到点那一刻还没有前置可交（半份也不交）", prelude.take(4, "root"))
    }

    @Test
    fun `调用方被取消不再拦导航 前置工作随会话作用域跑完`() = runTest {
        // 票 #122 的形状：导航在点击那一刻已经发生，取消拦不住它；「被取消」在新形状下由**落地侧**承担——
        // 阅读页自己的组合消失就不会落地（见 `ReaderPrelude.await` 与 `ReaderScreen`）。
        // 前置工作跑在 [workScope]（生产 = 会话级）上，因此发起那一屏被销毁不会让阅读页等到一份空前置。
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var entered = false
        val decoding = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            enterReaderThenPreload(
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

        assertTrue("导航在取消之前已经发生（点下去就滑）", entered)
        assertNull("取消那一刻前置还没到货（没有半份）", prelude.take(4, "root"))
        gate.complete(Unit) // 让前置工作跑完
        runCurrent()
        assertEquals(
            "前置工作随 [workScope] 走完并入槽（这次请求自己的那份可以兑现）",
            "root",
            prelude.take(4, "root")?.opening?.handle?.id,
        )
        // 票 #122 r2 的新口径：那份前置只属于被取消的**那一次**请求——再点开同一本书（新的世代）不得取用它
        prelude.begin(4, "root")
        assertNull(
            "被取消那次留下的前置不被后一次打开取用（世代判据）",
            prelude.take(4, "root"),
        )
    }

    // ---------- 票 #122 r2：前置按「哪一次打开」认主（世代）+ 阅读页侧等待的分支覆盖 ----------

    @Test
    fun `同键的过期前置不被后一次打开取用`() = runTest {
        // 评审 spec P1（本轮的验收用例）：慢来源上前置姗姗来迟——阅读页早就兜底自己开书、用户读到后面页，
        // 这份前置（落点按**上一次**点击时刻算）留在槽里；再点开同一本书时不得被取用，
        // 否则落点回退到旧页、随后 savePage 把旧页写回进度。拿掉世代判据即红（那时 take 命中的正是它）。
        val src = source()
        val prelude = ReaderPrelude()
        val stale = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)

        val firstOpen = prelude.begin(1, "root") // 第一次点开这本书
        prelude.put(1, "root", firstOpen, stale) // 前置姗姗来迟：阅读页 1.5s 后已兜底自己开了书

        prelude.begin(1, "root") // 再点开同一本书：这是新的一次打开
        assertNull("同键的过期前置不得被这一次打开取用", prelude.take(1, "root"))
        assertNull("阅读页侧的等待也不得交出它", prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS))
    }

    @Test
    fun `自己开书落地后 迟到的前置不被界面重建取走`() = runTest {
        // 评审 spec r2 P1（本轮的验收用例）：慢来源上前置 > 1.5s —— 阅读页等到点、兜底自己开书并落地，
        // 用户继续读到后面页；前置此时才姗姗到货，而**没有新的 `begin`**（用户没再点书）。
        // 旋转屏幕（默认「跟随系统」）会让 `ReaderScreen` 重新组合、组合期的 `take` 重跑：
        // 那一取不得命中这份「点击时刻落点」的旧条目（否则跳回点击那一页、退出时把低页写回进度）。
        // 拿掉退役判据（`takeReaderPreludeForOpen` 到点时的 `retire`）即红。
        val src = source()
        val prelude = ReaderPrelude()
        val entry = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)
        val generation = prelude.begin(1, "root") // 点书 A：前置开始跑（慢来源）

        assertNull(
            "前置没到货：阅读页等到点后走兜底（自己开书），并退役这次打开",
            takeReaderPreludeForOpen(prelude, 1, "root", timeoutMillis = 50),
        )

        prelude.put(1, "root", generation, entry) // 前置姗姗来迟（工作在会话级作用域里跑完）

        assertNull("界面重建后的组合期 take 不得取走它", prelude.take(1, "root"))
        assertNull("等待侧也不得交出它", prelude.await(1, "root", timeoutMillis = 50))
    }

    @Test
    fun `过期世代的前置不入槽`() = runTest {
        // 同一件事的另一半：迟到的 `put` 自己就被丢掉（不必等 `take` 去挡）——
        // 用户又点了一次（同键新请求）之后，旧那次的前置到货不得覆盖当前槽位。
        val src = source()
        val prelude = ReaderPrelude()
        val entry = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)

        val stale = prelude.begin(1, "root") // 第一次点开
        prelude.begin(1, "root") // 用户又点了一次（新请求）
        prelude.put(1, "root", stale, entry) // 旧那次的前置姗姗来迟
        assertNull("过期世代的前置不得入槽", prelude.take(1, "root"))
    }

    @Test
    fun `阅读页侧的等待到货就交出前置`() = runTest {
        // 承重路径：导航已发生、前置还在飞，阅读页在 `await` 上等着，工作侧 `put` 把它叫醒。
        val src = source()
        val prelude = ReaderPrelude()
        val entry = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)
        val generation = prelude.begin(1, "root")
        val awaiting = async { prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS) }
        runCurrent() // 让它挂到 await 上（前置此刻还没到货）

        prelude.put(1, "root", generation, entry)

        assertSame("到货即交出", entry, awaiting.await())
        assertNull("只兑现一次：交出去即清槽", prelude.take(1, "root"))
    }

    @Test
    fun `没有在飞的前置时阅读页不白等`() = runTest {
        // 启动还原 / 进程重建这类入口没有点击前置（没有 begin）：await 必须**立即**返回 null——
        // 否则这些入口每次进阅读页都白等 1.5s（这是「没有在飞的前置就不等」这条保证的唯一守护）。
        val prelude = ReaderPrelude()
        assertNull("没有在飞的前置 → 不等，返回 null", prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS))
        assertEquals("一路上没消耗虚拟时间（不是等上限到点才放行）", 0L, testScheduler.currentTime)
    }

    @Test
    fun `前置结束时阅读页立即放行`() = runTest {
        // 前置失败/被取消（`end`）之后不会再有那份了：等待必须立即结束，而不是耗到上限——
        // 否则失败路径上每次进阅读页都要空等 1.5s 才开书。
        val prelude = ReaderPrelude()
        val generation = prelude.begin(1, "root")
        val awaiting = async { prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS) }
        runCurrent()

        prelude.end(1, "root", generation)

        assertNull("结束在飞状态后立即返回 null", awaiting.await())
        assertEquals("没有等上限", 0L, testScheduler.currentTime)
    }

    @Test
    fun `被别的书的请求顶替后阅读页不再等旧的那份`() = runTest {
        // 单槽 + 单阅读页：用户又点了别的书（新的 begin）时，旧的那次打开不再算数——
        // 它的等待立即结束（不会耗尽上限），也不会取到属于别人的前置。
        val prelude = ReaderPrelude()
        prelude.begin(1, "a")
        val awaiting = async { prelude.await(1, "a", timeoutMillis = PRELUDE_TIMEOUT_MILLIS) }
        runCurrent()

        prelude.begin(1, "b") // 用户又点了别的书

        assertNull("A 的等待立即结束（不再等一份不会被交出的前置）", awaiting.await())
        assertEquals("没有等上限", 0L, testScheduler.currentTime)
    }

    @Test
    fun `等待与入槽并发发生时也不丢唤醒`() = runTest {
        // 评审 F1（并发交错）：`await` 跑在阅读页组合的 Main 上、`put` 跑在会话级作用域的 IO 上，两段可指令级交错。
        // 「取槽 / 判在飞 / 记下要等的信号」若不是同一把锁里的一步，等待就会挂在**没人会完成**的信号实例上：
        // 表现是等满上限返回 null，而槽里其实已有前置 = 重复开书 + 最长 1.5s 空屏。
        val src = source()
        val prelude = ReaderPrelude()
        val entry = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)

        repeat(40) {
            val generation = prelude.begin(1, "root")
            val putter = launch(Dispatchers.IO) { prelude.put(1, "root", generation, entry) }
            val taken = withContext(Dispatchers.IO) { prelude.await(1, "root", timeoutMillis = 2_000) }
            putter.join()
            assertSame("put 与 await 同时发生也要交付（不丢唤醒、不白等）", entry, taken)
        }
    }

    @Test
    fun `拿不到连接 id 时不做前置工作 也不等超时到点`() = runTest {
        // 前置槽按「连接 id + 书 id」认主（票 #110）：键拿不到就无处可交，前置工作因此整段不做
        val src = schedulerBoundSource()
        val prelude = ReaderPrelude()
        var decodeCalls = 0
        var entered = false

        enterReaderThenPreload(
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
        // r2 修复 P2：「不等」得可断言——本例的超时上限远大于一切，虚拟时钟一旦被推到到点就说明它在等超时
        assertEquals("一路上没消耗虚拟时间（不是等超时到点才放行）", 0L, testScheduler.currentTime)
    }

    // ---------- 票 #133：动刀前把现状钉成用例（世代作废 / 信号轮换 / 快速路径时序 / retire 语义） ----------
    // 本票把四字段（latest / inFlight / pending + 信号）收成单一状态机，对外时序**零变化**——
    // 下面几条因此改前改后都必须绿（现状钉）；它们同时是「结构性保证」的证明面：把「取槽 / 判在飞」
    // 拆成两次各持锁的读数，或复用已完成过的信号（= 忙等），这几条就会红或挂死。

    /**
     * 驱动一次「停在等待上的阅读页」：先 [ReaderPrelude.begin]（前置在飞）、让等待挂到信号上，
     * 再跑 [drive]（**一次状态转移**），返回等待醒来的结果。
     *
     * 断言「虚拟时钟没走」是承重点：走了就说明等待挂在了没人会完成的信号上（`await` 只能由上限收尾）。
     */
    private suspend fun TestScope.readPreludeAfterTransition(
        drive: (ReaderPrelude, PreludeGeneration) -> Unit,
    ): ReaderPreludeEntry? {
        val prelude = ReaderPrelude()
        val generation = prelude.begin(1, "root")
        val awaiting = async { prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS) }
        runCurrent() // 让等待挂到信号上（此刻状态 = 前置在飞）

        drive(prelude, generation)

        val entry = awaiting.await()
        assertEquals("被状态转移唤醒（不是耗尽上限才放行）", 0L, testScheduler.currentTime)
        return entry
    }

    @Test
    fun `停在等待上的阅读页按状态转移逐个醒来 每一步都不耗尽上限`() = runTest {
        val src = source()
        val entry = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)

        assertSame("到货（put）：交出这一份", entry, readPreludeAfterTransition { prelude, generation ->
            prelude.put(1, "root", generation, entry)
        })
        assertNull("前置结束（end）：立即放行，不再等一份不会交出的前置", readPreludeAfterTransition { prelude, generation ->
            prelude.end(1, "root", generation)
        })
        assertNull("退役（retire）：阅读页已决定自己开书，立即放行", readPreludeAfterTransition { prelude, _ ->
            prelude.retire(1, "root")
        })
        assertNull("被后一次打开顶替（begin 别的书）：旧等待立即结束", readPreludeAfterTransition { prelude, _ ->
            prelude.begin(1, "a")
        })
    }

    @Test
    fun `同键再点一次时 停在等待上的阅读页改挂新信号 到货仍能交付`() = runTest {
        // 信号随状态转移轮换（票 #133 的结构性质）：旧世代被顶替时等待者醒来**重新读状态**、挂到新信号上。
        // 若复用那个已完成过的信号，`await()` 会立刻返回 ⇒ 等待循环变成忙等，这份到货永远交不出来。
        val src = source()
        val prelude = ReaderPrelude()
        val opening = openForReading(src, "root", alwaysFirstPage = false)
        val staleEntry = ReaderPreludeEntry(opening, alwaysFirstPage = true)
        val currentEntry = ReaderPreludeEntry(opening, alwaysFirstPage = false)
        val stale = prelude.begin(1, "root")
        val awaiting = async { prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS) }
        runCurrent() // 停在信号上
        val current = prelude.begin(1, "root") // 用户又点了一次同一本书：新世代

        prelude.put(1, "root", stale, staleEntry) // 旧那次姗姗到货：不入槽
        prelude.put(1, "root", current, currentEntry) // 这一次的到货

        assertSame("交出的是这一次的到货，不是被顶替那份", currentEntry, awaiting.await())
        assertEquals("没有耗尽上限（是被转移唤醒的）", 0L, testScheduler.currentTime)
        assertNull("只兑现一次", prelude.take(1, "root"))
    }

    @Test
    fun `快速路径命中槽位即交付 不进入等待也不白等第二轮`() = runTest {
        // 「取槽 / 判在飞」由**同一个状态值**一次答出（票 #126 的根因：两句各持锁的读数之间能落进一次 put）。
        // 命中时一步都不等；已被取走的那份（阅读页重建后再取）也立即返回 null，不耗满 1.5s。
        val src = source()
        val prelude = ReaderPrelude()
        val entry = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)
        prelude.deliver(connId = 1, bookId = "root", entry)

        assertSame("槽里有最新前置 ⇒ 立即交出", entry, prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS))
        assertNull("取走即清槽：再等一次立即返回 null", prelude.await(1, "root", timeoutMillis = PRELUDE_TIMEOUT_MILLIS))
        assertEquals("两次都没等（虚拟时钟没走）", 0L, testScheduler.currentTime)
    }

    @Test
    fun `结束在飞不等于退役 迟到的到货只有退役之后才不入槽`() = runTest {
        // 票 #133 钉住的现状（收状态机之前是 latest / inFlight / pending 三个字段的组合）：`end` 只结束
        // 「在飞」，这次打开本身没被作废 ⇒ 工作侧若仍带着这个世代号到货，照旧入槽；
        // `retire`（阅读页已决定自己开书）才是把这次打开整个作废 ⇒ 之后的到货不得入槽。
        val src = source()
        val entry = ReaderPreludeEntry(openForReading(src, "root", alwaysFirstPage = false), alwaysFirstPage = false)

        val ended = ReaderPrelude()
        val endedGeneration = ended.begin(1, "root")
        ended.end(1, "root", endedGeneration)
        ended.put(1, "root", endedGeneration, entry)
        assertSame("end 只结束在飞：同世代的迟到到货照旧入槽", entry, ended.take(1, "root"))

        val retired = ReaderPrelude()
        val retiredGeneration = retired.begin(1, "root")
        retired.retire(1, "root")
        retired.put(1, "root", retiredGeneration, entry)
        assertNull("retire 作废这次打开：之后的到货不入槽", retired.take(1, "root"))
    }

    // ---------- 落地入口的组合与顺序（票 #132 步骤②：openReaderForLanding） ----------
    // 本票的动因是「接线层可单测」：这个入口把阅读页那一次调用收成「取前置 → 领票号 → 开书 → 落地」，
    // 下面四条钉住它的组合与顺序——组合被拆错、次序被调换都会红。

    @Test
    fun `落地入口 有前置就用那一份 不再自己开书`() = runTest {
        val base = source()
        var openCalls = 0
        val counting = object : Source by base {
            override suspend fun openBook(bookId: String): BookHandle {
                openCalls++
                return base.openBook(bookId)
            }
        }
        val carried = openBookAtLanding(base, "root", alwaysFirstPage = false)

        val landed = openReaderForLanding(
            source = counting,
            preludeSlot = ReaderPrelude(),
            connId = null,
            bookId = "root",
            taken = ReaderPreludeEntry(carried, alwaysFirstPage = false),
        )

        assertSame("交回来的就是组合期那一份（首帧命中解码缓存的来源）", carried, landed)
        assertEquals("有前置就不再开一次书", 0, openCalls)
    }

    @Test
    fun `落地入口 槽里有最新前置就取走用上`() = runTest {
        val src = source()
        val slot = ReaderPrelude()
        val parked = openBookAtLanding(src, "root", alwaysFirstPage = false)
        slot.put(7, "root", slot.begin(7, "root"), ReaderPreludeEntry(parked, alwaysFirstPage = true))

        val landed = openReaderForLanding(source = src, preludeSlot = slot, connId = 7, bookId = "root")

        assertSame("没有 taken 时从槽里取走那一份并用上（不再自己开书）", parked, landed)
        assertNull("取到即清槽（只兑现一次）", slot.take(7, "root"))
    }

    @Test
    fun `落地入口 没有前置就自己开书并领一个新票号`() = runTest {
        val src = source() // 3 页
        store.write("root", 2, 3) // 已读到第 3 页
        AppSettings.alwaysOpenFirstPage = true // 兜底那条的判据在落地那一刻就地读一次
        val before = ReaderEntryTickets.issue()

        val landed = openReaderForLanding(
            source = src,
            preludeSlot = ReaderPrelude(),
            connId = null,
            bookId = "root",
        )

        assertEquals("兜底自己开书，落点按当下的判据（第 1 页）", 0, landed.startIndex)
        assertEquals("落地写跟着走（进入马上退出也只算读了 1 页）", 0, store.read("root")?.pageIndex)
        assertFalse("本入口在开书前领了一个更新的票号（旧票号不再是最新）", ReaderEntryTickets.isLatest(before))
    }

    @Test
    fun `落地入口 领票号在兜底开书之前`() = runTest {
        // 票 #112 第 8 条：兜底的开书是可能阻塞的来源调用（慢来源上可达秒级），若「先开书、后领号」，
        // 先发起、后完成的那次落地会领到**更大**的号，后到的旧写于是能盖掉更新的记录。
        // 判据取「开书那一刻，调用前领到的那个票号还是不是最新」：新号已领 ⇒ 不是。
        val base = source()
        val before = ReaderEntryTickets.issue()
        var latestDuringOpen: Boolean? = null
        val probe = object : Source by base {
            override suspend fun openBook(bookId: String): BookHandle {
                latestDuringOpen = ReaderEntryTickets.isLatest(before)
                return base.openBook(bookId)
            }
        }

        openReaderForLanding(
            source = probe,
            preludeSlot = ReaderPrelude(),
            connId = null,
            bookId = "root",
        )

        assertEquals("开书那一刻已经领过更新的票号（领号在开书之前）", false, latestDuringOpen)
    }
}
