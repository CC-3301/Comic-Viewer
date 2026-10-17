package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.ui.AppSettings
import com.cc3301.comicviewer.ui.BrowseScrollLayer
import com.cc3301.comicviewer.ui.Routes
import com.cc3301.comicviewer.ui.ServiceLocator
import com.cc3301.comicviewer.ui.StartupStore
import com.cc3301.comicviewer.ui.navHostWith
import com.cc3301.comicviewer.ui.testSessionState

import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 浏览层导航的入口 [navigateToBrowseLocation]：**导航目标**与**落地顺序**。
 *
 * 导航本身是同步的（不预置任何缓存、不挂起），因此两次快速点击的落地顺序 = 发起顺序——
 * 这也是它取代旧「预置 → 导航」临界区的原因（那个临界区存在只为串行化预置的挂起读）。
 * 判别力：把入口改成先别处挂起一下再导航（两次点击交错），第一条用例即红；
 * 把 `containerId` 传成当前栈顶那一层，第二条用例即红（写错层会让新屏显示另一层的内容）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseLayerNavigationOrderTest {

    /** 本类自己那份会话状态（生产那份由组合根持有）：浏览历史因此不再跨用例共享 */
    private val session = testSessionState()

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        session.browseHistory.clear()
        StartupStore.clearBrowsing()
        AppSettings.scrollToTopOnEnterSubdirectory = false
    }

    @After
    fun tearDown() {
        session.browseHistory.clear()
        StartupStore.clearBrowsing()
        AppSettings.scrollToTopOnEnterSubdirectory = false
    }

    /**
     * 同一个事件循环里连续两次导航（与生产里两次点击同落在一个调度器同形）：
     * 落地顺序就是发起顺序，后一次压在前一次之上。
     */
    @Test
    fun `并发发起的两次导航按发起顺序落地`() {
        val nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER))

        navigateToBrowseLocation(nav, session.browseHistory, BrowseLocation(7, "layer-A", "A"))
        navigateToBrowseLocation(nav, session.browseHistory, BrowseLocation(7, "layer-B", "B"))

        assertEquals(
            "先发起的那一层在下、后发起的在上（连续快速操作不叠加两层）",
            listOf("layer-A", "layer-B"),
            browseLayersOnStack(nav).map { it.containerId },
        )
    }

    /**
     * 入口的导航目标就是传入的那一层（「写错层」是硬故障）：
     * 目标取的是 `location.containerId`，**不是**当前栈顶那一层的 container。
     */
    @Test
    fun `导航目标是传入的目标层 不是当前层`() {
        val nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER))
        nav.navigate(Routes.browser(connId = 7, containerId = "current", containerName = "当前层"))

        navigateToBrowseLocation(
            nav = nav,
            history = session.browseHistory,
            location = BrowseLocation(connId = 7, containerId = "target", containerName = "目标层"),
        )

        assertEquals(
            "导航目标 = 传入的目标层（传当前层即红）",
            "target",
            browseLocationOf(nav.currentBackStackEntry)?.containerId,
        )
    }

    /**
     * 「进入子目录时回到顶部」打开：向前进入登记跳顶待办。
     * 两类向前进入都算：从当前页点文件夹下钻，以及从首页/书柜点连接进入浏览根层。
     */
    @Test
    fun `设置打开时 向前进入登记跳顶 下钻与从首页点连接都算`() {
        AppSettings.scrollToTopOnEnterSubdirectory = true
        val nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER))
        val resets = session.forwardEntryScrollResets
        val rootLayer = BrowseScrollLayer(connId = 7, containerId = null)
        val subLayer = BrowseScrollLayer(connId = 7, containerId = "dir")

        // 从首页点连接：栈里没有浏览层 ⇒ 向前进入
        navigateToBrowseLocation(
            nav = nav,
            history = session.browseHistory,
            location = BrowseLocation(connId = 7, containerId = null),
            forwardEntryResets = resets,
        )
        assertTrue("从首页点连接进入根层：登记跳顶", resets.contains(rootLayer))
        resets.consume(rootLayer)

        // 当前页点文件夹下钻：目标不在当前浏览链上 ⇒ 向前进入
        navigateToBrowseLocation(
            nav = nav,
            history = session.browseHistory,
            location = BrowseLocation(connId = 7, containerId = "dir"),
            forwardEntryResets = resets,
        )
        assertTrue("点文件夹下钻：登记跳顶", resets.contains(subLayer))
    }

    /** 设置关闭：导航照常，但不登记跳顶（下一次进入恢复最近一次离开位置）。 */
    @Test
    fun `设置关闭时 向前进入不登记跳顶`() {
        AppSettings.scrollToTopOnEnterSubdirectory = false
        val nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER))

        navigateToBrowseLocation(
            nav = nav,
            history = session.browseHistory,
            location = BrowseLocation(connId = 7, containerId = null),
            forwardEntryResets = session.forwardEntryScrollResets,
        )

        assertFalse(
            "开关关闭：向前进入不登记跳顶",
            session.forwardEntryScrollResets.contains(BrowseScrollLayer(connId = 7, containerId = null)),
        )
    }

    /**
     * 路径菜单跳层不是向前进入：钉了链底（anchor 非 null）的跳兄弟目录，
     * 以及目标已在链上的跳层（如「/」），都不登记跳顶。
     */
    @Test
    fun `路径菜单跳层不登记跳顶`() {
        AppSettings.scrollToTopOnEnterSubdirectory = true
        val nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER))
        val resets = session.forwardEntryScrollResets
        val root = BrowseLocation(connId = 7, containerId = null)
        val sub = BrowseLocation(connId = 7, containerId = "dir")
        val sibling = BrowseLocation(connId = 7, containerId = "other")

        navigateToBrowseLocation(nav, session.browseHistory, root, forwardEntryResets = resets)
        resets.consume(BrowseScrollLayer(connId = 7, containerId = null))
        navigateToBrowseLocation(nav, session.browseHistory, sub, forwardEntryResets = resets)
        resets.consume(BrowseScrollLayer(connId = 7, containerId = "dir"))

        // 路径菜单跳兄弟目录：anchor = 链底，不是向前进入
        navigateToBrowseLocation(nav, session.browseHistory, sibling, anchor = root, forwardEntryResets = resets)
        assertFalse(
            "钉了链底的菜单跳层不登记跳顶",
            resets.contains(BrowseScrollLayer(connId = 7, containerId = "other")),
        )

        // 路径菜单跳「/」：目标根层已在链上，不是向前进入
        navigateToBrowseLocation(nav, session.browseHistory, root, anchor = null, forwardEntryResets = resets)
        assertFalse(
            "目标已在链上的菜单跳层不登记跳顶",
            resets.contains(BrowseScrollLayer(connId = 7, containerId = null)),
        )
    }
}
