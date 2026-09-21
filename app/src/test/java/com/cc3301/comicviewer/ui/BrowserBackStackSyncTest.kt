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
 * ⑤ 进程被杀后系统还原出来的回退栈要能把历史补齐；
 * ⑥ 票 #70 r3：浏览历史是回退栈里浏览层的**镜像**——同一层重复进入是**替换**而不是追加，
 * 从侧滑菜单离开浏览会话后重进来源不得把新层级叠在已离开的那一段上（否则返回递归嵌套），
 * 历史与回退栈不一致时返回交回系统（不再弹到错误层级）。
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
        graph.addDestination(destination(controller, Routes.LOCAL_ROOTS))
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
     * 与 `BrowserScreen` 显示某层时同形：**先按回退栈重建镜像**，再把「停留位置 + 整条路径」一次落盘
     * （[recordBrowsePosition]，票 #70 r3 后路径的唯一来源是回退栈）。
     */
    private fun showBrowser(location: BrowseLocation) {
        openBrowser(location)
        recordBrowsePosition(nav, history, location)
    }

    /**
     * `BrowserScreen` 的返回处理器语义：返回决议与回退栈一致时消费并弹一层，否则交回 NavController
     * （生产实现就是 [browseBackInterception] + `goBack()` + `popBackStack()`）。
     */
    private fun browserBack(): Boolean {
        if (!browseBackInterception(nav, history)) return false
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

        syncBrowseHistory(history, nav)

        assertEquals("历史补齐到与回退栈里的浏览层逐层对应", listOf(root, subdir), history.path())
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
        assertFalse(browserBack())
        nav.popBackStack()
        assertEquals(Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `进程未死的重建把历史按栈重建（幂等）`() {
        openBrowser(root)
        openBrowser(subdir)

        // 旋转这类 Activity 重建：历史随进程存活、本就与回退栈一致，重建入口必须让它原样
        syncBrowseHistory(history, nav)

        assertEquals(listOf(root, subdir), history.path())
        assertTrue(history.canGoBack)

        // 上一会话残留（镜像多出一层栈里没有的层）：重建入口按栈把它抹掉，返回决议随之与栈一致
        history.record(deep)
        syncBrowseHistory(history, nav)
        assertEquals(listOf(root, subdir), history.path())
    }

    // ---------- 票 #70 r3：以实际回退栈为准（维护者真机反馈的“一直嵌套下去”）----------

    /**
     * 维护者复现序列：目录 A-B-C，人在子文件夹 B 时从侧滑菜单去首页 → 来源 → 进连接 → 进 A → 返回。
     * 旧写法把新的层**追加**到已离开的那一段路径上（堆栈里的浏览层与历史镜像同时长），每绕一圈多一段，
     * 返回于是从 A 退到连接列表再退到那个已离开的 B，永不得清。
     */
    @Test
    fun `子文件夹里从侧滑去首页再重进同一连接 同一层是替换 返回不再嵌套`() {
        showBrowser(root)
        showBrowser(subdir)

        // 侧滑 → 首页 → 来源（本地根列表）→ 点该连接（`openConnectionRoot`）→ 再进 B（`openEntry`）
        navigateTopLevel(nav, Routes.HOME)
        nav.navigate(Routes.LOCAL_ROOTS)
        navigateToBrowseLocation(nav, history, root)
        navigateToBrowseLocation(nav, history, subdir)

        assertEquals("重进同一层是替换：回退栈里的浏览层不增长", 2, browserLayers())
        assertEquals("历史是栈里浏览层的镜像", listOf(root, subdir), history.path())
        assertEquals("离开时压上的首页/来源层被丢掉，不再夹在返回路径里", 1, layers(Routes.HOME))

        // 返回逐级：B → 根 → 首页；首页再返回才退出 APP（不会退到已离开那一段的副本）
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
        assertFalse("到了最早位置：返回处理器不再消费", browserBack())
        nav.popBackStack()
        assertEquals(Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `反复绕圈后浏览层不增长 每步返回都到达历史里的那一层`() {
        showBrowser(root)
        showBrowser(subdir)

        repeat(3) { round ->
            navigateTopLevel(nav, Routes.HOME)
            nav.navigate(Routes.LOCAL_ROOTS)
            navigateToBrowseLocation(nav, history, root)

            assertEquals("第 ${round + 1} 圈：重进同一层是替换（回到栈里那一层，不新增）", 1, browserLayers())
            navigateToBrowseLocation(nav, history, subdir)
            assertEquals("下钻一层：栈里共两层浏览层", 2, browserLayers())
            assertEquals(listOf(root, subdir), history.path())
            assertTrue(browserBack())
            assertEquals("返回真的到达历史里的那一层（而不是连接列表/首页）", 7L to null, browserLocation())
            assertEquals(root, history.current)
            assertFalse(browserBack())
            assertEquals("只剩一层浏览层：下一圈从它上面重新开始（旧写法每圈多一段）", 1, browserLayers())
        }
    }

    @Test
    fun `换了连接后重进来源 已离开的那一段会话被收掉`() {
        showBrowser(root)
        showBrowser(subdir)

        navigateTopLevel(nav, Routes.HOME)
        nav.navigate(Routes.LOCAL_ROOTS)
        val other = BrowseLocation(connId = 9, containerId = null)
        navigateToBrowseLocation(nav, history, other)

        assertEquals("新连接的根层是唯一一段：旧连接的层不会叠在它下面", 1, browserLayers())
        assertEquals(listOf(other), history.path())
        assertFalse("只有一层浏览层：返回交回系统", browserBack())
        nav.popBackStack()
        // 收掉旧那一段时连它之上的层（离开时压上的首页/来源）一并被收：NavController 只能从栈顶往下弹（见 dropAbandonedBrowsingSession）
        assertEquals(Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `导航到新位置清掉前进历史 返回后镜像同步不清（前进侧键仍可用）`() {
        showBrowser(root)
        showBrowser(subdir)
        assertTrue(browserBack())
        assertTrue("返回后还有可前进的位置（spec 故事 37）", history.canGoForward)

        // 浏览页显示时的镜像同步不清前进栈（否则鼠标前进侧键会在返回后立刻失效）
        syncBrowseHistory(history, nav)
        assertTrue(history.canGoForward)

        // 导航到新位置（下钻）清掉前进历史
        navigateToBrowseLocation(nav, history, deep)
        assertFalse("导航到新位置：前进历史作废（与 record 一致）", history.canGoForward)
    }

    @Test
    fun `进连接根层后立即退出（浏览页还没显示）重启仍按整条路径逐级返回`() {
        showBrowser(root)
        showBrowser(subdir)

        navigateTopLevel(nav, Routes.HOME)
        nav.navigate(Routes.LOCAL_ROOTS)
        navigateToBrowseLocation(nav, history, root)

        // 两个落盘键必须同源（路径取自回退栈）：否则启动侧「最后一层 = 恢复位置」判据不成立、只恢复一层
        val browsing = StartupStore.lastBrowsing()!!
        assertEquals(BrowseLocation(browsing.connId, browsing.containerId), StartupStore.browsingPath().last())
        assertEquals("路径就是栈里的浏览层", listOf(root), StartupStore.browsingPath())

        // 重启：按落盘路径重建（只有一层 → 首页再返回才退出）
        nav = newNav()
        val path = startupBrowsePath(
            StartupStore.browsingPath(),
            BrowseLocation(browsing.connId, browsing.containerId),
        )
        resetBrowseHistoryForStartup(history, path)
        pushBrowserPath(nav, path)
        syncBrowseHistory(history, nav)

        assertEquals(1, browserLayers())
        assertFalse(browserBack())
        nav.popBackStack()
        assertEquals(Routes.HOME, nav.currentDestination?.route)
    }

    @Test
    fun `启动重建不重复压已在栈里的浏览层`() {
        // 进程被杀 + saved state：系统还原出来的栈里已经有这些浏览层
        nav = newNav()
        nav.navigate(Routes.browser(root.connId, root.containerId))
        nav.navigate(Routes.browser(subdir.connId, subdir.containerId))

        // 落地按落盘路径重建（旧写法只跳过「栈顶那一层」，栈里已有的层会被再压一遍 → 镜像与栈多出一段）
        pushBrowserPath(nav, listOf(root, subdir))
        syncBrowseHistory(history, nav)

        assertEquals("已还原的层不重复压", 2, browserLayers())
        assertEquals(listOf(root, subdir), history.path())
        assertTrue(browserBack())
        assertEquals(7L to null, browserLocation())
    }

    @Test
    fun `历史与回退栈不一致时不接管返回 交回系统仍是逐级`() {
        openBrowser(root)
        openBrowser(subdir)
        // 进程级历史被上一会话残留污染：镜像里多了一层栈里没有的层（游标漂移同形）
        history.record(deep)

        assertFalse("不一致：不接管返回（不会弹到历史里那层）", browseBackInterception(nav, history))
        nav.popBackStack() // 系统返回：弹一层
        assertEquals("回到上一层（不是弹到历史里的 deep，也不是退出 APP）", 7L to null, browserLocation())

        // 浏览页显示时按栈重建镜像，此后返回决议又与栈一致
        syncBrowseHistory(history, nav)
        assertEquals(listOf(root), history.path())
        assertFalse(browseBackInterception(nav, history))
    }

    @Test
    fun `栈顶之下紧邻的不是浏览层时不接管返回`() {
        openBrowser(root)
        // 构造一条不一致的栈：浏览层之上又夹了一层设置（生产路径不会造出来；这里是返回决议的降级校验）
        navigateTopLevel(nav, Routes.SETTINGS)
        nav.navigate(Routes.browser(subdir.connId, subdir.containerId))
        history.record(subdir)

        assertEquals("镜像与栈里的浏览层一致", listOf(root, subdir), history.path())
        assertFalse("但弹一层落到的不是历史里的那一层（设置）→ 不接管", browseBackInterception(nav, history))
        nav.popBackStack()
        assertEquals("交回系统：弹一层落到设置，不会弹到历史里的 root", Routes.SETTINGS, nav.currentDestination?.route)
    }
}
