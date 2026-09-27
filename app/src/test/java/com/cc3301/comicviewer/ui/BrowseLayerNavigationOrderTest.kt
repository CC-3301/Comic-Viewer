package com.cc3301.comicviewer.ui

import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「预置 → 导航」的**顺序保证**（票 #111 ②，修复轮 r2）：[withPrimedLayer] 的判据。
 *
 * 要钉住的是什么（不是「它调用了锁」，而是**可观察的落地顺序**）：两次点击各起一个协程，每次都要先读一次
 * **本地落盘快照**（挂起读）再导航；读的完成顺序与点击顺序无关 ⇒ 不加顺序防线时导航的落地顺序会反掉
 * （后发起的先落地，先发起的压在上面），与红线「连续快速操作不叠加两层」不自洽。
 *
 * 判别力（本类的用例**能红**）：把 [withPrimedLayer] 里的 `withLock { }` 去掉（直接「预置 + 导航」）——
 * 第一条用例立刻变红（夹具让**先发起的那次读更慢**，于是它后落地）；第二条用例与锁**无关**（去锁后仍绿），
 * 它钉的是另两件事：「先预置、后导航」这条内部次序，与预置的键就是传入的目标层。两条实跑去锁只红第一条
 * （`failures=1`），见证据 `evidence-impl.md`「先红后绿」那条。
 *
 * 本类还钉住入口那一层的一个硬故障面（票 #111 ② 票面第 2 条「写错层」）：
 * [navigateToBrowseLocationPrimed] 真的把**目标层**（`location.containerId`）交给预置，而不是当前栈顶那一层
 *（首例用假来源记录实到的键，换成当前层即红）。
 *
 * 夹具为什么是**真挂起的假来源**而不是 `null`：`source == null` 时预置是空操作、一次都不挂起，临界区里
 * 没有挂起点就验不出任何顺序（那种写法会变成「加个断言了事」的假判据）。这里的假来源在
 * [Source.primeCachedEntries] 里 `delay`，因此两次「预置 → 导航」会真的争锁。
 *
 * Robolectric 只为 [withPrimedLayer] 的默认排序参数（`SortSettingStore.setting.mode`）能读到 prefs；
 * 断言与 Android 无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseLayerNavigationOrderTest {

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        // 浏览历史与启动落盘是**进程级单例**（与 BrowserBackStackSyncTest 同一套清理）：不清理会串进后续用例
        ServiceLocator.browseHistory.clear()
        StartupStore.clearBrowsing()
    }

    @After
    fun tearDown() {
        ServiceLocator.browseHistory.clear()
        StartupStore.clearBrowsing()
    }

    /** 预置会真的挂起（模拟落盘快照读）：[primed] 记下每次预置的层 id，[delayMs] 模拟这一层读得快还是慢 */
    private class SuspendingPrimeSource(
        private val delayMs: Long,
        /** 可选的共用事件表：用来断言「预置」与「导航」两步的先后 */
        private val log: MutableList<String>? = null,
    ) : Source {
        override val type: SourceType = SourceType.LOCAL

        val primed = mutableListOf<String?>()

        /** 本类只关心预置那一步；枚举用不上 */
        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> = emptyList()

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("本用例不打开书")

        override suspend fun readProgress(bookId: String) = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String) = com.cc3301.comicviewer.core.source.Neighbors(null, null)

        override suspend fun primeCachedEntries(containerId: String?, sort: SortMode): Boolean {
            primed += containerId
            delay(delayMs)
            log?.add("prime")
            return true
        }
    }

    /**
     * 顺序 = 发起顺序：**先发起的那次读更慢**（80ms vs 10ms）。
     * 有顺序防线时：两次「预置 → 导航」串行，先发起的先落地 ⇒ `[A, B]`；
     * 去掉防线（按完成顺序落地）时是 `[B, A]` ⇒ 本用例变红。
     */
    @Test
    fun `并发发起的两次 预置到导航 按发起顺序落地`() = runBlocking {
        val slowFirst = SuspendingPrimeSource(delayMs = 80)
        val fastSecond = SuspendingPrimeSource(delayMs = 10)
        val landed = mutableListOf<String>()

        // 同一个事件循环（runBlocking）：两次 launch 的开始执行顺序 = 发起顺序，与生产里两次点击同落在一个调度器同形
        val first = launch { withPrimedLayer(slowFirst, "layer-A") { landed += "A" } }
        val second = launch { withPrimedLayer(fastSecond, "layer-B") { landed += "B" } }
        joinAll(first, second)

        assertEquals("先发起的那一次先落地（不被后一次的读抢先）", listOf("A", "B"), landed)
        assertEquals("两次都垫的是**自己**的目标层", listOf<String?>("layer-A"), slowFirst.primed)
        assertEquals(listOf<String?>("layer-B"), fastSecond.primed)
    }

    /** 同一次调用内部：预置**完成之后**才导航（临界区里两步的先后） */
    @Test
    fun `预置完成在导航之前`() = runBlocking {
        val steps = mutableListOf<String>()
        val source = SuspendingPrimeSource(delayMs = 20, log = steps)

        withPrimedLayer(source, "layer-A") { steps += "navigate" }

        assertEquals("先预置、再导航（先置好会话槽，新屏出生当帧才有内容）", listOf("prime", "navigate"), steps)
        assertEquals("垫的是目标层", listOf<String?>("layer-A"), source.primed)
    }

    /**
     * 入口真的把**目标层**交给预置（票 #111 ② 票面第 2 条「写错层是硬故障」）：
     * [navigateToBrowseLocationPrimed] 取的是 `location.containerId`，**不是**当前栈顶那一层的 container。
     * 换成当前层（或父层）即红 —— 这正是票面要求「能失败的用例」钉住的那一步。
     *
     * 本用例跑的是生产入口本身（不是重抄一遍「传哪个键」）：图只建被测路径需要的两个 destination
     * （与 `NavHostTestSupport` 的口径一致），`Source` 用夹具记录实到的键。
     */
    @Test
    fun `入口预置的是目标层 不是当前层`() = runBlocking {
        val nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER))
        // 当前栈顶：连接 7 的「当前层」
        nav.navigate(Routes.browser(connId = 7, containerId = "current", containerName = "当前层"))
        val source = SuspendingPrimeSource(delayMs = 0)

        navigateToBrowseLocationPrimed(
            nav = nav,
            history = ServiceLocator.browseHistory,
            source = source,
            location = BrowseLocation(connId = 7, containerId = "target", containerName = "目标层"),
        )

        assertEquals(
            "只垫目标层（当前层不在列——垫错层会让新屏显示另一层的内容）",
            listOf<String?>("target"),
            source.primed,
        )
        assertEquals("目标层的名字随路由压进去（票 #143）", "target", browseLocationOfTop(nav)?.containerId)
    }

    /** 栈顶那一项的浏览位置（与生产同一份参数解码：[browseLocationOf] 是 private，这里按同一套参数键读） */
    private fun browseLocationOfTop(nav: androidx.navigation.NavHostController): BrowseLocation? =
        nav.currentBackStack.value.lastOrNull()?.arguments?.let { args ->
            BrowseLocation(
                connId = args.getString("connId")?.toLongOrNull() ?: return null,
                containerId = args.getString("container")?.takeIf { it.isNotEmpty() },
                containerName = args.getString("name")?.takeIf { it.isNotEmpty() },
            )
        }
}
