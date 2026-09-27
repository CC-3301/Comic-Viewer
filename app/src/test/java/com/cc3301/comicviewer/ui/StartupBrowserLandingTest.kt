package com.cc3301.comicviewer.ui

import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.source.BrowseEntry
import com.cc3301.comicviewer.core.source.Neighbors
import com.cc3301.comicviewer.core.source.SortMode
import com.cc3301.comicviewer.core.source.Source
import com.cc3301.comicviewer.core.source.SourceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动落地「落浏览层」那一支（票 #111 ③，票面 B 案）：把**压首页**与**压浏览链**整段圈进同一个预置临界区
 * （[landStartupBrowserLayer]）。
 *
 * 判据是什么（真机「重启闪首页」的根因，证据见票面「三条「闪」的现行台账」）：预置是挂起读（`Dispatchers.IO`），
 * 它一旦夹在「压首页」与「压浏览链」之间，主线程就在这两跳之间让出一次 —— 首页于是被组合出一帧
 * （打点判据：诊断日志里出现 `nav route route=home`）。**第二条用例就钉这一条**：预置还在飞的时候，
 * 栈顶必须仍是中转页（首页还没上栈）；把 [landStartupBrowserLayer] 改回「先压首页、再预置」即红。
 *
 * 第一条用例钉顺序与形状不变（同时是 [pushStartupRootHome] 的判据）：落地后栈底是首页、浏览链逐层压在其上，
 * 浏览历史是这条链的镜像，且预置的键是**目标层**（写错层在本票是硬故障）。
 *
 * 夹具用「真挂起的假来源」（与 `BrowseLayerNavigationOrderTest` 同一手法）：[Source.primeCachedEntries] 里
 * 等一个闸门，因此「预置还在飞」是确定性的，不押墙钟。
 * Robolectric 只为预置那一步的默认排序参数（`SortSettingStore.setting.mode`）能读到 prefs；断言与 Android 无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupBrowserLandingTest {

    private lateinit var nav: NavHostController

    private val history get() = ServiceLocator.browseHistory

    /** 本次落地的层级链：根层 → 子目录（两层是**同一个 destination、不同 container 参数**） */
    private val path = listOf(
        BrowseLocation(connId = 7, containerId = null),
        BrowseLocation(connId = 7, containerId = "dir-sub"),
    )

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        history.clear()
        nav = navHostWith(listOf(Routes.HOME, Routes.STARTUP, Routes.BROWSER))
        // 启动落地开始那一刻的栈：中转页在顶（生产里 STARTUP 就是 startDestination）
        nav.navigate(Routes.STARTUP)
    }

    @After
    fun tearDown() {
        history.clear()
    }

    /** 回退栈上的路由 pattern（栈底那个图节点 `root` 不算一层；与 `BrowserBackStackSyncTest` 的层数取法同口径） */
    private fun stackRoutes(): List<String> =
        nav.currentBackStack.value.map { it.destination.route ?: "" }.filter { it != "root" }

    /** 预置会真的挂起（模拟落盘快照读），直到 [gate] 被开；[primed] 记下实到的层 id */
    private class GatedPrimeSource(private val gate: CompletableDeferred<Unit>) : Source {
        override val type: SourceType = SourceType.LOCAL

        val primed = mutableListOf<String?>()

        /** 本类只关心预置那一步；枚举用不上 */
        override suspend fun listEntries(containerId: String?, sort: SortMode): List<BrowseEntry> = emptyList()

        override suspend fun openBook(bookId: String) = throw UnsupportedOperationException("本用例不打开书")

        override suspend fun readProgress(bookId: String) = null

        override suspend fun writeProgress(bookId: String, pageIndex: Int, totalPages: Int) = Unit

        override suspend fun neighbors(bookId: String): Neighbors = Neighbors(null, null)

        override suspend fun primeCachedEntries(containerId: String?, sort: SortMode): Boolean {
            primed += containerId
            gate.await()
            return true
        }
    }

    /**
     * 预置还在飞的时候，首页**不许**已经在回退栈上 —— 那是「首页被组合出一帧」的唯一入口条件。
     * 把两跳拆开（首页先压、预置后跑）时本用例立刻变红，那一拆就是真机看到的闪。
     */
    @Test
    fun `预置还在飞时 首页不出现 栈顶仍是中转页`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = GatedPrimeSource(gate)

        val job = launch { landStartupBrowserLayer(nav, history, path, source, "dir-sub") }
        yield() // 让这次调用跑到预置那次挂起读

        assertEquals(
            "预置还在飞：首页不能已经压上（它一旦成为当前路由就会被组合成那一帧的「闪」）",
            Routes.STARTUP,
            nav.currentDestination?.route,
        )
        assertEquals("预置的键是目标层", listOf<String?>("dir-sub"), source.primed)

        gate.complete(Unit)
        job.join()

        assertEquals("预置回来后两跳在同一帧里压完", listOf(Routes.HOME, Routes.BROWSER, Routes.BROWSER), stackRoutes())
    }

    /** 顺序与形状不变：栈底是首页、链逐层压在其上；历史是这条链的镜像；预置的键是目标层 */
    @Test
    fun `落地后 首页在最底 浏览链逐层压在其上`() = runBlocking {
        val source = GatedPrimeSource(CompletableDeferred(Unit))

        landStartupBrowserLayer(nav, history, path, source, "dir-sub")

        assertEquals(listOf(Routes.HOME, Routes.BROWSER, Routes.BROWSER), stackRoutes())
        assertEquals(
            "预置的键必须是目标层（写成别的层会让新屏显示另一层的内容）",
            listOf<String?>("dir-sub"),
            source.primed,
        )
        assertEquals("浏览历史是这条链的镜像（返回逐级）", BrowseLocation(7, "dir-sub"), history.current)
    }
}
