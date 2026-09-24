package com.cc3301.comicviewer.ui

import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.source.BookHandle
import com.cc3301.comicviewer.core.source.DocumentTreeSource
import com.cc3301.comicviewer.core.source.FakeTreeBackend
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.fakeDir
import com.cc3301.comicviewer.core.source.fakeFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「开书入口」通道（票 #132 步骤①）：四条入口（浏览页点击 / 启动还原 / 抽屉「阅读器」/ 读内换书）
 * 交给通道的只有**哪本书**（[OpenBookTarget]）、**这次算不算数**（[OpenRequestGuard]）、**怎么进阅读器**，
 * 其余（领世代号 / 登记前置 / 导航时点 / 会话级前置 / 「始终从第一页打开」判据）都在 [OpenBookEntry] 里。
 *
 * 本文件钉的是**收拢过程中不许动的那些既有语义**（票面「表现零变化」），每条都对应一处曾经散在四个入口里、
 * 或曾经修出过 bug 的接线：
 * - 导航在**点击那一帧**发生（票 #122）；守卫为假 ⇒ 不导航，但前置照旧入槽（#108 的「入槽与导不导航是两件事」）；
 * - 前置按**最新那次**打开认主（票 #122 r2 的世代号）：连点两本时旧的那本不入槽——用例让第一份**仍在飞**时
 *   就发起第二次，否则「旧的不入槽」这句话在任何实现下都绿（票 #132 r2 评审 F1）；
 * - 前置失败 ⇒ 不入槽也不报错、照旧导航（阅读页有自己的失败提示与重试）；
 * - 连接 id / 来源缺失 ⇒ 照旧导航、不做前置工作（前置槽的键都没有，无处可交）；
 * - 「始终从第一页打开」在**发起那一刻读一次**，随前置槽带到落地（票 #110 r3）；
 * - 守卫的**登记**（[ReaderEntryRequest.beginGuard]）：单调 token + 发起时栈顶那一项，语义同 #111 r3。
 *
 * 机制那一层（[ReaderPrelude] 的世代号/退役/持锁与 [openAndLandReaderEntry] 的票号落地）仍由
 * `ReaderPreludeTest` / `ReaderEntryLandingTest` 钉，本文件不重复。
 *
 * 前置工作在真实 IO 上跑（生产口径），因此用例用 `runBlocking` + 真实时间：假来源上工作秒完，
 * `open` 返回时前置已在槽里，断言因此是确定性的（虚拟时间会先跳到 1.5s 上限，读到的槽是不确定的）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenBookEntryTest {

    /** 前置槽（真实实现；四条入口共用同一个，生产 = `ServiceLocator.readerPrelude`） */
    private val slot = ReaderPrelude()

    private val store = InMemoryProgressStore()

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        AppSettings.alwaysOpenFirstPage = false
        resetSessionStatics()
    }

    @After
    fun tearDown() {
        AppSettings.alwaysOpenFirstPage = false
        resetSessionStatics()
    }

    /**
     * 同 sandbox 的既有做法（`StartupStoreTest` / `ReaderEntryLandingTest`）：这几个会话级静态与**同一进程**里的
     * 别的测试类共用（`ServiceLocator.init` 只注入 Context，不重置它们），用例给过值就必须两侧还原——
     * `lastRead` 还带落盘副作用，不还原就会串进同 sandbox 里后续执行的用例。
     *
     * 本类只走**前置链**（`OpenBookEntry` → `enterReaderThenPreload` → `preloadReaderOpening`，不落地、不写
     * `lastRead`）；[没有来源时照旧导航 但不做前置工作] 会摆一份会话来源，`currentSource` 因此也一并还原。
     */
    private fun resetSessionStatics() {
        ServiceLocator.lastRead = null
        ServiceLocator.currentSource = null
        ServiceLocator.currentConnId = null
    }

    /** 一本书 3 页（root 下只有图片 ⇒ root 本身是一本书），与 `ReaderPreludeTest` 同一份假树 */
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

    /** 走一次通道：返回「导航发生过吗」+ 这次打开交付的前置（= 已入槽那一份，无则 null） */
    private suspend fun openEntry(
        scope: CoroutineScope,
        source: Source?,
        connId: Long? = 7,
        bookId: String = "root",
        guard: OpenRequestGuard = OpenRequestGuard { true },
    ): Pair<Boolean, ReaderPreludeEntry?> {
        var navigated = false
        OpenBookEntry(workScope = scope, prelude = slot, targetWidthPx = { 1080 }).open(
            target = OpenBookTarget(source = source, connId = connId, bookId = bookId),
            guard = guard,
            enterReader = { navigated = true },
        )
        return navigated to (connId?.let { slot.take(it, bookId) })
    }

    @Test
    fun `点击那一帧就导航 前置随后入槽`() = runBlocking {
        val (navigated, delivered) = openEntry(this, source())

        assertTrue("导航不等前置（票 #122：滑入在点击那一帧开始）", navigated)
        assertEquals("前置按这本书自己的落点开书并交付", "root", delivered?.opening?.handle?.id)
    }

    @Test
    fun `守卫说不算数就不导航 但前置照旧入槽`() = runBlocking {
        // #108 口径：入槽与「导不导航」是两件事——守卫只决定这一次点击还算不算数
        val (navigated, delivered) = openEntry(this, source(), guard = OpenRequestGuard { false })

        assertFalse("不算数的那一次不导航", navigated)
        assertEquals("前置仍照旧开书入槽（由槽的世代号/退役判据决定谁能兑现）", "root", delivered?.opening?.handle?.id)
    }

    @Test
    fun `连点两本 旧的那本不入槽`() = runBlocking {
        // 票 #122 r2 的世代号：只有最新那次请求的前置才能被兑现（旧落点不得覆盖后一次打开的落地）。
        // 判别力在「第一次前置**还在飞**的时候第二次就发起」：旧的那份即使随后到货，也不得入槽。
        // （第一份若在第二次 begin 之前就已入槽、又被 helper 取走，这条断言在任何实现下都绿——本用例的前提就卡在这里。）
        val base = source()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val slow = object : Source by base {
            override suspend fun openBook(bookId: String): BookHandle {
                started.complete(Unit) // 工作开跑 ⇒ 这次请求的世代号必然已经领过
                gate.await() // 卡住它：让它一直处于「在飞」
                return base.openBook(bookId)
            }
        }
        val other = DocumentTreeSource(
            backend = FakeTreeBackend(
                fakeDir("other").add(fakeFile("other/1.jpg"), fakeFile("other/2.jpg")),
            ),
            progressStore = store,
        )

        val firstOpen = async { openEntry(this@runBlocking, slow, bookId = "root") }
        started.await()
        assertTrue("前提：第一次的前置确实还在飞", slot.isInFlight(7, "root"))

        val (navigated, second) = openEntry(this, other, bookId = "other")
        gate.complete(Unit) // 旧的那份姗姗到货
        val (_, deliveredFirst) = firstOpen.await()

        assertTrue("后一次点击自己导航", navigated)
        assertEquals("只有后者被兑现", "other", second?.opening?.handle?.id)
        assertNull("被顶替的那本（迟到的旧世代）不得入槽", deliveredFirst)
        assertFalse("旧的那本也不留在飞", slot.isInFlight(7, "root"))
    }

    @Test
    fun `前置失败不入槽 也不挡导航`() = runBlocking {
        // 前置失败不是错误：阅读页到点自己开书（那里有加载态、失败提示与重试）
        val failing = object : Source by source() {
            override suspend fun openBook(bookId: String): BookHandle = throw IllegalStateException("来源挂了")
        }

        val (navigated, delivered) = openEntry(this, failing)

        assertTrue("失败照旧进阅读页", navigated)
        assertNull("不交半份前置", delivered)
        assertFalse("也不留「这本书还在飞」给阅读页白等", slot.isInFlight(7, "root"))
    }

    @Test
    fun `没有连接 id 时照旧导航 但不做前置工作`() = runBlocking {
        // 前置槽按「连接 id + 书 id」认主：键都拿不到就无处可交（`enterReaderThenPreload` 的既有口径）。
        // 会话里捞着一个连接 id、且指向同一本书：通道若给缺失的连接 id 补一个会话值（错的口径），
        // 下面的槽断言就会红——这是本用例的判别力所在。
        // 断言**直接读槽**（不是读 helper 的返回值）：connId 为 null 时 helper 的取用表达式自己就是 null，
        // 断它等于 null 在任何实现下都绿（恒真断言）。
        ServiceLocator.currentConnId = 7

        val (navigated, _) = openEntry(this, source(), connId = null)

        assertTrue("没有连接 id 也照旧导航", navigated)
        assertFalse("这本书不得留在飞", slot.isInFlight(7, "root"))
        assertNull("也不得入槽", slot.take(7, "root"))
    }

    @Test
    fun `没有来源时照旧导航 但不做前置工作`() = runBlocking {
        // 连接 id 齐备、只有来源缺失：同一个「键不齐就不做前置」口径的另一半。
        // 会话来源里摈着一本好书：通道若在 target 来源缺失时偷偷退回会话来源，下面的断言就会红（判别力在此）。
        // 断在 helper 的**返回值**上（不是再查一次槽）：helper 末尾那次 take 已经会把入槽的那份取走。
        ServiceLocator.currentSource = source()

        val (navigated, delivered) = openEntry(this, source = null, connId = 7)

        assertTrue("没有来源也照旧导航", navigated)
        assertNull("来源缺失 ⇒ 不入槽（不得改用会话来源那份）", delivered)
        assertFalse("也不得留在飞", slot.isInFlight(7, "root"))

        assertEquals("来源齐备时前置照旧交付（对照组）", "root", openEntry(this, source()).second?.opening?.handle?.id)
    }

    @Test
    fun `始终从第一页打开 在入口处读一次 随前置带到落地`() = runBlocking {
        // 票 #110 r3：判据与落点同源（都在发起那一刻读），落地时不再重读设置
        val src = source()
        store.write("root", 2, 3) // 已读到第 3 页
        AppSettings.alwaysOpenFirstPage = true

        val (_, delivered) = openEntry(this, src)

        assertEquals("开关开启 ⇒ 落点是第 1 页", 0, delivered?.opening?.startIndex)
        assertTrue("判据随前置带走（落地那一刻不再问设置）", delivered?.alwaysFirstPage == true)
    }

    @Test
    fun `守卫登记 仍停在发起时那一项算数`() {
        val nav = navHostWith(listOf(Routes.HOME, Routes.SETTINGS))
        val requests = ReaderEntryRequest()

        assertTrue(requests.beginGuard(nav).isCurrent())
    }

    @Test
    fun `守卫登记 等待窗口里换屏或组合已死都不算数`() {
        val nav = navHostWith(listOf(Routes.HOME, Routes.SETTINGS))
        val requests = ReaderEntryRequest()
        var alsoAlive = true
        val guard = requests.beginGuard(nav, alsoAlive = { alsoAlive })

        assertTrue("对照：两项都成立时算数", guard.isCurrent())

        alsoAlive = false
        assertFalse("本入口额外的存活条件（启动还原那条）为假 ⇒ 不算数", guard.isCurrent())

        alsoAlive = true
        nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
        assertFalse("栈顶换了那一项 ⇒ 不算数", guard.isCurrent())
    }

    @Test
    fun `守卫登记 后一次登记顶掉前一次`() {
        val nav = navHostWith(listOf(Routes.HOME, Routes.SETTINGS))
        val requests = ReaderEntryRequest()

        val first = requests.beginGuard(nav)
        val second = requests.beginGuard(nav)

        assertFalse("被后一次点击顶替的那次不导航", first.isCurrent())
        assertTrue("只有最新那次算数", second.isCurrent())
    }
}
