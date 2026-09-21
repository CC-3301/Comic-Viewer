package com.cc3301.comicviewer.ui

import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import com.cc3301.comicviewer.core.source.openBookAtLanding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 切进阅读页这一刻的落地（票 #110）：阅读进度与「上次阅读位置」写在**同一时点**。
 *
 * 维护者指示（补记 2）：点开一本书后立刻取消（改点另一本 / 返回 / 切走）时，书没被打开，
 * 但「上次阅读位置」已经被改写成这本没打开的书——启动还原与抽屉「阅读器」入口读的就是它。
 * 口径是：两者一起推迟到**真正切进阅读页那一刻**，只保留**一个**新记录写入点（`applyReaderEntry`）。
 *
 * **用例都经过生产接缝**（不是手工复刻落地序列）：
 * - 点击路径 = [openBookFromBrowser]（`BrowserScreen.openEntry` 的书分支就调它）；
 * - 书柜页的前置体 = [preloadReaderOpening] / [awaitReaderPrelude]（真函数，含真取消与真超时）；
 * - 阅读页的打开 + 落地 = [openAndLandReaderEntry]（`ReaderScreen` 的组合期就调它，前置由 `take` 取走）。
 * 因此「把点击时的写加回来」「删掉落地调用」「去掉票号守卫」三种回归都会让这里的用例变红
 * （证据里记了三次实测的 RED）。
 *
 * 观测点是**落盘的那份记录** `StartupStore.lastRead()`：它正是「继续上次阅读」读的东西，
 * 因此这里同时钉住「不得新建第二份记录」。走 Robolectric 是必要的：`ServiceLocator.lastRead` 的 setter
 * 直落 SharedPreferences（票 20），纯 JVM 下拿不到 Context；好处是断言打在**生产那条写**上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderEntryLandingTest {

    private val store = InMemoryProgressStore()

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        ServiceLocator.lastRead = null
        ServiceLocator.currentConnId = null
        ServiceLocator.currentSource = null
        AppSettings.alwaysOpenFirstPage = false
    }

    @After
    fun tearDown() {
        ServiceLocator.lastRead = null
        ServiceLocator.currentConnId = null
        ServiceLocator.currentSource = null
        AppSettings.alwaysOpenFirstPage = false
    }

    /** 两本书（各 2 页）：用来表达「先点 a、再点 b」与「被顶替的那本不留记录」 */
    private fun source(): Source = DocumentTreeSource(
        backend = FakeTreeBackend(
            fakeDir("root").add(
                fakeDir("root/a").add(fakeFile("root/a/1.jpg"), fakeFile("root/a/2.jpg")),
                fakeDir("root/b").add(fakeFile("root/b/1.jpg"), fakeFile("root/b/2.jpg")),
            ),
        ),
        progressStore = store,
    )

    private fun book(id: String) = BrowseEntry(id = id, name = id, isBook = true, coverUri = null)

    /**
     * 阻塞来源的替身（票 #110 r4）：只记下**进入 `openBook` 时所在线程**，自己不切调度——
     * Komga 的 `openBook` 就是这个形状（非 suspend 的 `execute()`）。
     * 不能拿 `DocumentTreeSource` 当替身：它内部自己会 `withContext(Dispatchers.IO)`（`DocumentTreeSource.kt:849`），
     * 用它会永远绿。
     */
    private class ThreadRecordingSource(private val delegate: Source) : Source by delegate {
        @Volatile
        var openThread: Thread? = null

        override suspend fun openBook(bookId: String): BookHandle {
            openThread = Thread.currentThread()
            return delegate.openBook(bookId)
        }
    }

    @Test
    fun `兜底开书切到 IO 线程 阻塞来源不得跑在主线程`() = runTest {
        // 影响面复验 r3 P1：阅读页的调用点是组合期 LaunchedEffect（主线程），而 Komga 的开书/取进度是
        // 阻塞 OkHttp（callTimeout 90s）⇒ 不在兜底路径切 IO 就会卡主线程（ANR）。
        val callerThread = Thread.currentThread()
        val src = ThreadRecordingSource(source())

        openAndLandReaderEntry(src, connId = 7, bookId = "root/a", prelude = null)

        assertNotNull("开书发生了", src.openThread)
        assertNotSame("阻塞开书不得在调用方（主）线程上跑", callerThread, src.openThread)
    }

    @Test
    fun `点击一本书只登记要开哪本 不写进度也不写上次阅读位置`() = runTest {
        val src = source()
        store.write("root/a", 1, 2) // 已读到第 2 页
        val requested = mutableListOf<String>()

        openBookFromBrowser(connId = 7, source = src, entry = book("root/a")) { requested += it.id }

        assertEquals("点击只登记「要开这本」（切页由前置跑完后的那一个动作做）", listOf("root/a"), requested)
        assertEquals("会话来源对齐到本页连接（阅读器路由只认会话来源）", 7L, ServiceLocator.currentConnId)
        assertNull("点击不得写「上次阅读位置」（启动还原读的就是这条）", StartupStore.lastRead())
        assertEquals("点击不得改进度", 1, store.read("root/a")?.pageIndex)
    }

    @Test
    fun `点开后取消 进度与上次阅读位置都不变`() = runTest {
        val src = source()
        store.write("root/a", 1, 2)
        val decoding = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        openBookFromBrowser(connId = 7, source = src, entry = book("root/a")) { }
        // 真实前置体（书柜页那条）：书已打开，首批解码永远挂着 → 取消（改点另一本 / 返回 / 切走）
        val job = launch {
            preloadReaderOpening(src, "root/a", alwaysFirstPage = true, targetWidthPx = 1080) { _, _, _ ->
                decoding.complete(Unit)
                gate.await()
            }
        }
        decoding.await()
        job.cancelAndJoin()

        assertNull("取消不得写「上次阅读位置」", StartupStore.lastRead())
        assertEquals("取消不得改进度（书柜上的「在读」也不该出现）", 1, store.read("root/a")?.pageIndex)
    }

    @Test
    fun `前置超时没兑现 进度与上次阅读位置都不变`() = runTest {
        val src = source()
        store.write("root/a", 1, 2)
        var readyCalled = false
        var navigated = false

        awaitReaderPrelude(
            workScope = this,
            timeoutMillis = 100,
            preload = {
                // 真实前置体：书已打开，但首批解码迟迟不返回 ⇒ 上限到点放行
                preloadReaderOpening(src, "root/a", alwaysFirstPage = true, targetWidthPx = 1080) { _, _, _ ->
                    delay(10_000)
                }
            },
            onReady = { readyCalled = true },
            isRequestCurrent = { true },
            navigate = { navigated = true },
        )

        assertTrue("超时也必须进阅读页（那里有加载态与失败重试）", navigated)
        assertFalse("超时的那次不交句柄", readyCalled)
        assertNull("前置窗口里什么都没落地：不得写「上次阅读位置」", StartupStore.lastRead())
        assertEquals("也不得改进度", 1, store.read("root/a")?.pageIndex)
    }

    @Test
    fun `切进阅读页那一刻 进度与上次阅读位置一起落地`() = runTest {
        val src = source()
        store.write("root/a", 1, 2)

        openBookFromBrowser(connId = 7, source = src, entry = book("root/a")) { }
        val opening = preloadReaderOpening(src, "root/a", alwaysFirstPage = true, targetWidthPx = 1080) { _, _, _ -> }
        ServiceLocator.readerPrelude.put(7, "root/a", ReaderPreludeEntry(opening, alwaysFirstPage = true))

        assertNull("还没切页：两样都还没写", StartupStore.lastRead())
        assertEquals("前置只开书不写进度", 1, store.read("root/a")?.pageIndex)

        // 阅读页组合期：同步取走前置（`ReaderScreen` 的 remember）→ 一次落地（它的 LaunchedEffect）
        val landed = openAndLandReaderEntry(
            src,
            connId = 7,
            bookId = "root/a",
            prelude = ServiceLocator.readerPrelude.take(7, "root/a"),
        )

        assertSame("取到的句柄原样交给阅读页（不再重开一次）", opening, landed)
        assertEquals("启动还原 / 抽屉「阅读器」读的就是这一条记录", LastRead(7, "root/a"), StartupStore.lastRead())
        assertEquals(
            "同一时点落地的进度覆盖（故事 40：进入马上退出也只算读了 1 页）",
            0,
            store.read("root/a")?.pageIndex,
        )
    }

    @Test
    fun `被后一次点击顶替 只有后者生效`() = runTest {
        val src = source()
        openBookFromBrowser(connId = 7, source = src, entry = book("root/a")) { }
        val a = preloadReaderOpening(src, "root/a", alwaysFirstPage = true, targetWidthPx = 1080) { _, _, _ -> }
        ServiceLocator.readerPrelude.put(7, "root/a", ReaderPreludeEntry(a, alwaysFirstPage = true))
        openBookFromBrowser(connId = 7, source = src, entry = book("root/b")) { }
        val b = preloadReaderOpening(src, "root/b", alwaysFirstPage = true, targetWidthPx = 1080) { _, _, _ -> }
        ServiceLocator.readerPrelude.put(7, "root/b", ReaderPreludeEntry(b, alwaysFirstPage = true))

        openAndLandReaderEntry(src, connId = 7, bookId = "root/b", prelude = ServiceLocator.readerPrelude.take(7, "root/b"))

        assertEquals("只有后者被记成上次阅读位置", LastRead(7, "root/b"), StartupStore.lastRead())
        assertEquals("后者的进度落地", 0, store.read("root/b")?.pageIndex)
        assertNull("被顶替的那本不留任何记录（书柜上的「在读」与进度都不该出现）", store.read("root/a"))
    }

    @Test
    fun `超时兜底进了阅读页 按「确实在看这本书」记录`() = runTest {
        // 编排者 r3 裁定：以「是否真的落到阅读页」为准——超时兜底**成功进入阅读页**算记录（用户确实在看这本书），
        // 取消 / 被后一次点击顶替（没进阅读页）不记录；票面补记 2.1 的「失败」指「没进去」。
        val src = source()
        store.write("root/a", 1, 2)
        AppSettings.alwaysOpenFirstPage = true // 兜底分支的判据来自设置（与落点同一次读）

        val landed = openAndLandReaderEntry(src, connId = 7, bookId = "root/a", prelude = null)

        assertEquals("兜底分支自己开书（前置没兑现）", 2, landed.handle.pageCount)
        assertEquals(LastRead(7, "root/a"), StartupStore.lastRead())
        assertEquals(0, store.read("root/a")?.pageIndex)
    }

    @Test
    fun `兜底路径先领票号 后到的旧落地不得压回旧书`() = runTest {
        // 票 #112 第 8 条（#110 影响面复验报的 P2）：兜底分支先开书、后领票号，
        // 而开书是可能阻塞的来源调用（慢来源上可达秒级）⇒ 先发起的那次落地因开书慢而完成得晚，
        // 反而领到**更大**的票号——「后到的旧写不得覆盖更新的记录」于是失效（守卫只看号的大小）。
        // 判别力：把 issue() 挪回 fallbackPreludeEntry 之后（= 旧写法）本条即红（证据里记了这次 RED）。
        val src = source()
        val openingStarted = CompletableDeferred<Unit>()
        val slowOpen = CompletableDeferred<Unit>()
        val slowA = object : Source by src {
            override suspend fun openBook(bookId: String): BookHandle {
                if (bookId == "root/a") {
                    openingStarted.complete(Unit)
                    slowOpen.await()
                }
                return src.openBook(bookId)
            }
        }

        // 先切进 a（开书挂在 slowOpen 上），再切进 b（开书很快、先落地），最后才放行 a
        val older = launch { openAndLandReaderEntry(slowA, connId = 7, bookId = "root/a", prelude = null) }
        openingStarted.await()
        openAndLandReaderEntry(slowA, connId = 7, bookId = "root/b", prelude = null)
        slowOpen.complete(Unit)
        older.join()

        assertEquals(
            "先发起、后完成的那次落地不得把记录压回旧书",
            LastRead(7, "root/b"),
            StartupStore.lastRead(),
        )
    }

    @Test
    fun `旧落地后到 不得把上次阅读位置压回旧书`() = runTest {
        val src = source()
        val older = ReaderEntryTickets.issue()
        val newer = ReaderEntryTickets.issue()

        // 新 entry 先落地；旧 entry 的落地**后到**（慢来源上仍在飞的那半截，NonCancellable 会把它跑完）
        landReaderEntry(src, 7, "root/b", ReaderPreludeEntry(openBookAtLanding(src, "root/b", true), true), newer)
        landReaderEntry(src, 7, "root/a", ReaderPreludeEntry(openBookAtLanding(src, "root/a", true), true), older)

        assertEquals("后到的旧写不得覆盖更新的记录", LastRead(7, "root/b"), StartupStore.lastRead())
        assertEquals("进度各自落地（每本书自己的键，互不覆盖）", 0, store.read("root/a")?.pageIndex)
        assertEquals(0, store.read("root/b")?.pageIndex)
    }
}
