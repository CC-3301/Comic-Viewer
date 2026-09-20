package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
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
 * 浏览历史与回退栈的同步（票 #70，spec 故事 37/38）。
 *
 * 本票的根因：`ServiceLocator.browseHistory` 是**进程级单例**，而 NavController 的回退栈随 Activity 一并销毁；
 * 退出 APP 时 `closeSession()` 不清历史，重启后启动路径又把恢复到的位置 **record 到上一会话的旧历史栈上**，
 * 于是浏览页的返回处理器（`enabled = canGoBack`）落到一个**不在回退栈上**的层级——
 * 用户看到「从二级界面按返回直接被扔回首页」，再按一次真的退出 APP。
 *
 * 这里不组合 UI，只跑真实的 `NavController`（Robolectric，与 [ReaderSwapNavTest] 同一手法）：
 * ① `closeSession()` 必须清历史；② 启动落地必须把历史**重置为**它创建的浏览层（回退栈里只有一层时 canGoBack 不得为真）；
 * ③ 返回必须真的到达历史里的那一层；④ 抽屉顶层入口收掉根首页之上的层级并与历史同步（首页是唯一根）。
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

    @Before
    fun setUp() {
        history.clear()
        nav = newNav() // 起手 = 冷启动落在首页（与 AppNav 落地后的回退栈同形：[HOME]）
    }

    @After
    fun tearDown() {
        history.clear()
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

    @Test
    fun `退出 APP 清空浏览历史 回退栈只有一层时 canGoBack 不为真`() {
        // 会话 1：进根层 → 进子目录（回退栈 [HOME, 根, 子]；历史 [根, 子] 与栈一致）
        openBrowser(root)
        openBrowser(subdir)
        assertTrue(history.canGoBack)
        assertEquals(subdir, history.current)

        // 首页返回 → Activity finish → MainActivity.onDestroy(isFinishing) → closeSession()
        ServiceLocator.closeSession()

        // 重启：新的 NavController（回退栈随 Activity 重建），启动只还原出这一层浏览页
        nav = newNav()
        resetBrowseHistoryForStartup(history, subdir)
        nav.navigate(Routes.browser(subdir.connId, subdir.containerId)) { launchSingleTop = true }

        assertFalse("回退栈只有一层，启动恢复后不得残留上一会话的旧历史", history.canGoBack)
        assertEquals(subdir, history.current)
        assertFalse("回退栈只有一层：返回处理器不消费，交回系统", browserBack())
        nav.popBackStack() // 系统返回
        assertEquals("回到上一层（首页），不是退出 APP", Routes.HOME, nav.currentDestination?.route)
    }

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

    @Test
    fun `启动重置把上一会话的残留历史换成只剩恢复的那一层`() {
        // 上一会话残留：历史里还有多个位置，前进栈也非空
        val deep = BrowseLocation(connId = 7, containerId = "dir-deep")
        history.record(root)
        history.record(subdir)
        history.record(deep)
        history.goBack()
        assertTrue(history.canGoBack)
        assertTrue(history.canGoForward)

        resetBrowseHistoryForStartup(history, subdir)

        assertEquals(subdir, history.current)
        assertFalse("启动落到某层后，历史必须只有这一层", history.canGoBack)
        assertFalse("前进栈一并作废", history.canGoForward)
    }

    @Test
    fun `启动落首页或书柜这类无浏览层的目标时清空历史`() {
        history.record(root)
        history.record(subdir)

        resetBrowseHistoryForStartup(history, null)

        assertNull(history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
    }

    @Test
    fun `抽屉顶层入口收掉根首页之上的层级 不叠第二层首页且历史同步清空`() {
        openBrowser(root)
        openBrowser(subdir)
        assertEquals("两个浏览层在栈上", 2, nav.currentBackStack.value.count { it.destination.route == Routes.BROWSER })

        navigateTopLevel(history, nav, Routes.HOME)

        assertEquals(Routes.HOME, nav.currentDestination?.route)
        assertEquals(
            "首页是唯一根：其上不再压第二层首页",
            1,
            nav.currentBackStack.value.count { it.destination.route == Routes.HOME },
        )
        assertEquals(
            "移动端回退栈里不再残留浏览层",
            0,
            nav.currentBackStack.value.count { it.destination.route == Routes.BROWSER },
        )
        assertNull("回退栈里已无浏览层，历史必须同步清空", history.current)
        assertFalse(history.canGoBack)
    }

    @Test
    fun `抽屉书柜与设置入口同样收掉根首页之上的层级`() {
        openBrowser(root)
        openBrowser(subdir)

        navigateTopLevel(history, nav, Routes.BOOKSHELF)
        assertEquals(Routes.BOOKSHELF, nav.currentDestination?.route)
        assertEquals("书柜只一层", 1, nav.currentBackStack.value.count { it.destination.route == Routes.BOOKSHELF })
        assertEquals("浏览层被收掉", 0, nav.currentBackStack.value.count { it.destination.route == Routes.BROWSER })
        assertFalse(history.canGoBack)
        assertNull(history.current)

        // 再次点同一入口不叠加（launchSingleTop）
        navigateTopLevel(history, nav, Routes.BOOKSHELF)
        assertEquals(1, nav.currentBackStack.value.count { it.destination.route == Routes.BOOKSHELF })
        assertEquals(Routes.BOOKSHELF, nav.currentDestination?.route)

        navigateTopLevel(history, nav, Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, nav.currentDestination?.route)
        assertEquals(
            "顶层入口互斥地挂在根首页之下（书柜被收掉）",
            0,
            nav.currentBackStack.value.count { it.destination.route == Routes.BOOKSHELF },
        )
        assertEquals(0, nav.currentBackStack.value.count { it.destination.route == Routes.BROWSER })
        assertFalse(history.canGoBack)
    }
}
