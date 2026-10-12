package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.ui.Routes
import com.cc3301.comicviewer.ui.ServiceLocator
import com.cc3301.comicviewer.ui.StartupStore
import com.cc3301.comicviewer.ui.navHostWith
import com.cc3301.comicviewer.ui.testSessionState

import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
import org.junit.After
import org.junit.Assert.assertEquals
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
    }

    @After
    fun tearDown() {
        session.browseHistory.clear()
        StartupStore.clearBrowsing()
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
}
