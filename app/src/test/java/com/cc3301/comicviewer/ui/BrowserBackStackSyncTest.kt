package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastRead
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
 * 浏览历史与回退栈的同步、返回逐级（票 #70，spec 故事 37/38）。
 *
 * 本票的根因：`ServiceLocator.browseHistory` 是**进程级单例**，而 NavController 的回退栈随 Activity 一并销毁；
 * 退出 APP 时 `closeSession()` 不清历史，重启后启动路径又把恢复到的位置 **record 到上一会话的旧历史栈上**，
 * 于是浏览页的返回处理器（`enabled = canGoBack`）落到一个**不在回退栈上**的层级——
 * 用户看到「从二级界面按返回直接被扔回首页」，再按一次真的退出 APP。
 *
 * 这里不组合 UI，只跑真实的 `NavController`（Robolectric，与 [ReaderSwapNavTest] 同一手法）：
 * ① `closeSession()` 必须把浏览**路径**落盘并清历史；② 启动落地按落盘路径**重建整条层级链**
 * （退出时停在第几层，返回路径上就有第几层及其上级）；③ 返回必须真的到达历史里的那一层；
 * ④ 抽屉四个入口（首页/书柜/设置/阅读器）**压在当前界面之上**，返回回到进入前的界面（票 #70 r2 追加口径）；
 * ⑤ 进程被杀后系统还原出来的回退栈要能把历史补齐。
 *
 * **本图是 `AppNav` 路由表的复刻**：只建被测路径需要的 destination，route 串取自同一份 `Routes` 常量；
 * 改生产的接线必须同步本图。浏览器返回处理器那两行（`BackHandler`）无法在单测里跑到 compose，
 * 故 [browserBack] 复刻它的语义（`canGoBack` 时 `goBack()+popBackStack()`，否则交回 NavController 默认弹栈）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserBackStackSyncTest {

    private lateinit var nav: NavHostController

    private val history get() = ServiceLocator.browseHistory

    private val root = BrowseLocation(connId = 7, containerId = null)
    private val subdir = BrowseLocation(connId = 7, containerId = "dir-sub")
    private val deep = BrowseLocation(connId = 7, containerId = "dir-deep")

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        history.clear()
        StartupStore.clearBrowsing()
        nav = newNav() // 起手 = 冷启动落在首页（与 AppNav 落地后的回退栈同形：[HOME]）
    }

    @After
    fun tearDown() {
        history.clear()
        StartupStore.clearBrowsing()
        ServiceLocator.currentSource = null
        ServiceLocator.currentConnId = null
    }

    /** 路由图 = 生产的子集（start destination = HOME，与 AppNav 落地后的栈底同形） */
    private fun newNav(): NavHostController {
        val context: Context = ApplicationProvider.getApplicationContext()
        val controller = NavHostController(context)
        controller.navigatorProvider.addNavigator(ComposeNavigator())
        val graphNavigator = controller.navigatorProvider.getNavigator(NavGraphNavigator::class.java)
        val graph: NavGraph = graphNavigator.createDestination().apply {
            route = "root"
            setStartDestination(Routes.HOME)
        }
        graph.addDestination(destination(controller, Routes.HOME))
        graph.addDestination(destination(controller, Routes.BOOKSHELF))
        graph.addDestination(destination(controller, Routes.SETTINGS))
        graph.addDestination(destination(controller, Routes.BROWSER))
        // READER 不声明参数：与生产的 `composable(Routes.READER)` 一致（bookId 由路由模板解析）
        graph.addDestination(destination(controller, Routes.READER))
        controller.setGraph(graph, null)
        return controller
    }

    private fun destination(controller: NavHostController, route: String): ComposeNavigator.Destination =
        ComposeNavigator.Destination(
            controller.navigatorProvider.getNavigator(ComposeNavigator::class.java),
        ) { }.apply { this.route = route }

    /** 与 `BrowserScreen.openEntry` 的容器分支同一手法：记历史 + 压栈 */
    private fun openBrowser(location: BrowseLocation) {
        history.record(location)
        nav.navigate(Routes.browser(location.connId, location.containerId))
    }

    /**
     * 与 `BrowserScreen` 显示某层时同形：位置入历史 + 压栈 + 把「停留位置 + 整条路径」一次落盘（[recordBrowsePosition]）。
     * 后一步是本票 r2 复审的写侧主路径——只有 [openBrowser] 的话，落盘路径要等 Activity finish 才写得上。
     */
    private fun showBrowser(location: BrowseLocation) {
        openBrowser(location)
        recordBrowsePosition(history, location)
    }

    /** `BrowserScreen` 的返回处理器语义：历史能后退时消费并弹一层，否则交回 NavController */
    private fun browserBack(): Boolean {
        if (!history.canGoBack) return false
        history.goBack()
        nav.popBackStack()
        return true
    }

    /** 当前浏览页读到的位置（与 `BrowserScreen` 从参数取 `containerId` 同源：空串即根层） */
    private fun browserLocation(): Pair<Long?, String?> =
        nav.currentBackStackEntry?.arguments
            ?.let {
                it.getString("connId")?.toLongOrNull() to it.getString("container")?.takeIf { c -> c.isNotEmpty() }
            }
            ?: (null to null)

    private fun layers(route: String): Int = nav.currentBackStack.value.count { it.destination.route == route }

    /** 回退栈里的浏览层数量 */
    private fun browserLayers(): Int = layers(Routes.BROWSER)

    /** 回退栈里三个抽屉顶层入口层的总数（票 #70 r2 评审 P1 的上界：≤ 3） */
    private fun topLevelLayers(): Int =
        layers(Routes.HOME) + layers(Routes.BOOKSHELF) + layers(Routes.SETTINGS)

    // ---------- 常规返回（AC7：返回真的到达历史里的那一层）----------

    @Test
    fun `返回上一层真的到达历史里的那一层`() {
        openBrowser(root)
        openBrowser(subdir)

        assertTrue(browserBack())
        assertEquals("历史回退一格，界面必须到那一格（根层），不弹回首页", root, history.current)
        assertEquals("界面真的落在历史里的那一层", 7L to null, browserLocation())

        assertFalse("到了最早位置：返回处理器不再消费", browserBack())
        nav.popBackStack()
        assertEquals(Routes.HOME, nav.currentDestination?.route)
    }

    // ---------- 抽屉入口（追加口径 AC9/AC10：返回回到进入前的界面）----------

    @Test
    fun `抽屉设置入口压在当前界面之上 返回回到进入前的子文件夹`() {
        openBrowser(root)
        openBrowser(subdir)

        navigateTopLevel(nav, Routes.SETTINGS)

        assertEquals(Routes.SETTINGS, nav.currentDestination?.route)
        assertEquals("浏览层留在其下：返回要回到进入前的界面", 2, browserLayers())
        assertEquals("抽屉入口从不弹浏览层，历史因此无需清", subdir, history.current)

        nav.popBackStack() // 系统返回：设置层被弹掉

        assertEquals("回到进入前的那个子文件夹，不是首页", 7L to "dir-sub", browserLocation())
        assertTrue("再返回逐级到根层", browserBack())
        assertEquals(7L to null, browserLocation())
        assertFalse(browserBack())
        nav.popBackStack()
        assertEquals("只有首页再返回才退出 APP", Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `抽屉首页与书柜入口同样压在当前界面之上 返回回到子文件夹`() {
        openBrowser(root)
        openBrowser(subdir)

        navigateTopLevel(nav, Routes.HOME)
        assertEquals(Routes.HOME, nav.currentDestination?.route)
        assertEquals("根首页 + 抽屉压上的首页，重复点不再叠第三层", 2, layers(Routes.HOME))
        nav.popBackStack()
        assertEquals(7L to "dir-sub", browserLocation())

        navigateTopLevel(nav, Routes.BOOKSHELF)
        assertEquals(Routes.BOOKSHELF, nav.currentDestination?.route)
        assertEquals("书柜只一层", 1, layers(Routes.BOOKSHELF))
        nav.popBackStack()
        assertEquals(7L to "dir-sub", browserLocation())

        // 重复点同一入口不叠加（launchSingleTop）
        navigateTopLevel(nav, Routes.BOOKSHELF)
        navigateTopLevel(nav, Routes.BOOKSHELF)
        assertEquals(1, layers(Routes.BOOKSHELF))
        nav.popBackStack()
        assertEquals(7L to "dir-sub", browserLocation())
    }

    @Test
    fun `不同抽屉入口交替进入不叠层 返回仍逐级`() {
        openBrowser(root)
        openBrowser(subdir)

        navigateTopLevel(nav, Routes.SETTINGS)
        navigateTopLevel(nav, Routes.BOOKSHELF)

        assertEquals("顶层入口层不叠加：设置被书柜换掉", 0, layers(Routes.SETTINGS))
        assertEquals(1, layers(Routes.BOOKSHELF))
        assertEquals("浏览层始终是那两层，没有被复制", 2, browserLayers())

        nav.popBackStack()
        assertEquals("回到进入前的子文件夹", 7L to "dir-sub", browserLocation())
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
    }

    @Test
    fun `纯顶层入口链交替进入层数有界 返回逆序逐层回`() {
        // 无浏览层起手：[HOME]。评审 P1 的复现路径就是这一串（旧实现每次交替都新增一层）
        navigateTopLevel(nav, Routes.BOOKSHELF) // 首次访问：书柜
        navigateTopLevel(nav, Routes.SETTINGS) // 首次访问：设置
        assertEquals("上界 = 浏览层(0) + 3 个顶层入口", 3, topLevelLayers())

        // 交替再进入已在区域内的入口：回到那一层（丢掉它之上的中间层），不叠第二层
        navigateTopLevel(nav, Routes.BOOKSHELF)
        assertEquals("书柜只一层", 1, layers(Routes.BOOKSHELF))
        assertEquals("它之上的设置被丢掉", 0, layers(Routes.SETTINGS))
        assertEquals(2, topLevelLayers())

        navigateTopLevel(nav, Routes.SETTINGS)
        navigateTopLevel(nav, Routes.BOOKSHELF)
        navigateTopLevel(nav, Routes.SETTINGS)
        assertEquals("反复交替不再增长：首页 + 书柜 + 设置", 3, topLevelLayers())
        assertEquals(1, layers(Routes.BOOKSHELF))
        assertEquals(1, layers(Routes.SETTINGS))

        // 返回按首次访问顺序（首页→书柜→设置）的逆序逐层回
        assertEquals(Routes.SETTINGS, nav.currentDestination?.route)
        nav.popBackStack()
        assertEquals(Routes.BOOKSHELF, nav.currentDestination?.route)
        nav.popBackStack()
        assertEquals("回到栈底那个根首页（不是被压的第二层首页）", Routes.HOME, nav.currentDestination?.route)
        assertEquals(1, layers(Routes.HOME))
    }

    @Test
    fun `浏览层之上交替进入不叠层 返回最终回到浏览层而不是退出`() {
        openBrowser(root)
        openBrowser(subdir)

        navigateTopLevel(nav, Routes.BOOKSHELF)
        navigateTopLevel(nav, Routes.SETTINGS)
        navigateTopLevel(nav, Routes.BOOKSHELF)
        navigateTopLevel(nav, Routes.SETTINGS)

        assertEquals("浏览层始终是那两层，没有被复制", 2, browserLayers())
        assertEquals("浏览层之上的顶层入口层有界：最多一个入口层", 1, layers(Routes.BOOKSHELF) + layers(Routes.SETTINGS))
        assertEquals("根首页仍在栈底（没被弹掉）", 1, layers(Routes.HOME))
        assertEquals(Routes.SETTINGS, nav.currentDestination?.route)

        nav.popBackStack()
        assertEquals("返回落到进入前的子文件夹，而不是退出 APP", 7L to "dir-sub", browserLocation())
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
        assertFalse(browserBack())
        nav.popBackStack()
        assertEquals(Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `抽屉阅读器入口返回落到进入前的子文件夹`() {
        openBrowser(root)
        openBrowser(subdir)
        navigateTopLevel(nav, Routes.SETTINGS)

        openReaderFromDrawer(nav, history, LastRead(connId = 7, bookId = "book-1"))

        assertEquals("设置层被收掉，浏览层留在阅读器之下", 0, layers(Routes.SETTINGS))
        assertEquals("浏览层不重复", 2, browserLayers())
        assertEquals(Routes.READER, nav.currentDestination?.route)
        assertEquals("栈里只有一条阅读器 entry（票 #68 换 entry 语义不变）", 1, layers(Routes.READER))

        nav.popBackStack() // 返回 = 退出阅读器
        assertEquals("回到进入前的子文件夹", 7L to "dir-sub", browserLocation())
    }

    // ---------- 会话结束与启动恢复（AC6/AC11/AC12）----------

    @Test
    fun `退出 APP 落盘浏览路径 重启按整条路径重建 返回逐级到首页再退出`() {
        // 会话 1：根层 → 子目录 → 深目录
        openBrowser(root)
        openBrowser(subdir)
        openBrowser(deep)

        // 首页返回 → Activity finish → MainActivity.onDestroy(isFinishing) → closeSession()
        ServiceLocator.closeSession()

        assertFalse("会话结束清空历史（回退栈随 Activity 一并销毁）", history.canGoBack)
        assertNull(history.current)
        assertEquals("退出时的路径落盘：根 → 子 → 深", listOf(root, subdir, deep), StartupStore.browsingPath())

        // 重启：新的 NavController（回退栈全新），启动按落盘路径重建整条层级链
        nav = newNav()
        val path = startupBrowsePath(StartupStore.browsingPath(), deep)
        resetBrowseHistoryForStartup(history, path)
        pushBrowserPath(nav, path)

        assertEquals("回到退出时那一层", 7L to "dir-deep", browserLocation())
        assertEquals("该层及其上级层级都在返回路径上", 3, browserLayers())
        assertTrue(browserBack())
        assertEquals("逐级回到上一级子文件夹", 7L to "dir-sub", browserLocation())
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
        assertFalse("到了最早位置：返回处理器不再消费", browserBack())
        nav.popBackStack()
        assertEquals("只有首页再返回才退出 APP", Routes.HOME, nav.currentDestination?.route)
        assertEquals(1, layers(Routes.HOME))
    }

    @Test
    fun `没走 finish 的退出（任务被划掉）重启后仍按整条路径逐级返回`() {
        // 会话 1：根 → 子 → 深。任务被划掉 / 进程被杀 = 没有 Activity finish：closeSession() 不跑、历史不清，
        // 落盘路径只能靠浏览页每次显示时写（[recordBrowsePosition]，生产调用点 = BrowserScreen 的 LaunchedEffect）。
        showBrowser(root)
        showBrowser(subdir)
        showBrowser(deep)

        assertEquals(
            "退出时停在深层，落盘路径就是整条层级链（不依赖 finish）",
            listOf(root, subdir, deep),
            StartupStore.browsingPath(),
        )

        // 冷启动（任务已被划掉 → 无 savedInstanceState、无系统还原的回退栈）：只按落盘值重建
        val browsing = StartupStore.lastBrowsing()!!
        val path = startupBrowsePath(
            StartupStore.browsingPath(),
            BrowseLocation(browsing.connId, browsing.containerId),
        )
        nav = newNav()
        resetBrowseHistoryForStartup(history, path)
        pushBrowserPath(nav, path)

        assertEquals("回到退出时那一层", 7L to "dir-deep", browserLocation())
        assertEquals("该层及其上级都在返回路径上", 3, browserLayers())
        assertTrue(browserBack())
        assertEquals("逐级回到上一级子文件夹，而不是直接回首页", 7L to "dir-sub", browserLocation())
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
        assertFalse("到了最早位置：返回处理器不再消费", browserBack())
        nav.popBackStack()
        assertEquals("只有首页再返回才退出 APP", Routes.HOME, nav.currentDestination?.route)
        assertEquals(1, layers(Routes.HOME))
    }

    @Test
    fun `落盘路径只有一层时 canGoBack 不为真 首页返回即退出`() {
        openBrowser(root)
        ServiceLocator.closeSession()

        nav = newNav()
        val path = startupBrowsePath(StartupStore.browsingPath(), root)
        resetBrowseHistoryForStartup(history, path)
        pushBrowserPath(nav, path)

        assertFalse("回退栈只有一层浏览页：canGoBack 不得为真", history.canGoBack)
        assertEquals(root, history.current)
        assertFalse("返回处理器不消费，交回系统", browserBack())
        nav.popBackStack()
        assertEquals("回到上一层（首页），不是退出 APP", Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `落盘路径与恢复位置不一致时只恢复该层`() {
        assertEquals("陈旧路径不采用", listOf(deep), startupBrowsePath(listOf(root, subdir), deep))
        assertEquals("连接不同不采用", listOf(deep), startupBrowsePath(listOf(BrowseLocation(9, null), deep), deep))
        assertEquals("与恢复位置一致时整条采用", listOf(root, subdir), startupBrowsePath(listOf(root, subdir), subdir))
        assertEquals("没有落盘路径时只有这一层", listOf(subdir), startupBrowsePath(emptyList(), subdir))
    }

    @Test
    fun `启动重置把上一会话的残留历史换成恢复路径`() {
        // 上一会话残留：历史里还有多个位置，前进栈也非空
        history.record(root)
        history.record(subdir)
        history.record(deep)
        history.goBack()
        assertTrue(history.canGoBack)
        assertTrue(history.canGoForward)

        resetBrowseHistoryForStartup(history, listOf(root, subdir))

        assertEquals(subdir, history.current)
        assertEquals("历史 = 恢复路径（与重建出来的回退栈逐层对应）", listOf(root, subdir), history.path())
        assertTrue("路径有两层，返回可用", history.canGoBack)
        assertFalse("前进栈一并作废", history.canGoForward)
    }

    @Test
    fun `启动落首页或书柜这类无浏览层的目标时清空历史`() {
        history.record(root)
        history.record(subdir)

        resetBrowseHistoryForStartup(history, emptyList())

        assertNull(history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
    }

    @Test
    fun `进程被杀后重建 按系统还原的回退栈补齐历史`() {
        // 系统还原出来的回退栈；浏览历史是进程级的，随进程消失（为空）
        nav = newNav()
        nav.navigate(Routes.browser(root.connId, root.containerId))
        nav.navigate(Routes.browser(subdir.connId, subdir.containerId))
        assertNull(history.current)

        seedBrowseHistoryFromBackStack(history, nav)

        assertEquals("历史补齐到与回退栈里的浏览层逐层对应", listOf(root, subdir), history.path())
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
        assertFalse(browserBack())
        nav.popBackStack()
        assertEquals(Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `进程未死的重建不动已有历史`() {
        openBrowser(root)
        openBrowser(subdir)

        // 旋转这类 Activity 重建：历史随进程存活、本就与回退栈一致，补齐入口必须让它原样
        seedBrowseHistoryFromBackStack(history, nav)

        assertEquals(listOf(root, subdir), history.path())
        assertTrue(history.canGoBack)
    }
}
