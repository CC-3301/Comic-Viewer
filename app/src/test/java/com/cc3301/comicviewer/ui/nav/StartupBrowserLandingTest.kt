package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.ui.Routes
import com.cc3301.comicviewer.ui.ServiceLocator
import com.cc3301.comicviewer.ui.navHostWith
import com.cc3301.comicviewer.ui.testSessionState

import androidx.navigation.NavHostController
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
 * 启动落地「落浏览层」那一支：压首页与压浏览链**同一帧里同步压完**（[landStartupBrowserLayer]）。
 *
 * 判据是什么（设备「重启闪首页」的根因）：两跳之间一旦挂起（以前中间夹着一次落盘快照的挂起读），
 * 主线程就在那个窗口里让出一次 —— 首页于是被组合出一帧（打点判据：诊断日志里出现 `nav route route=home`）。
 *
 * 形状与顺序：落地后栈底是首页、浏览链逐层压在其上，浏览历史是这条链的镜像。
 * 判别力：把 [landStartupBrowserLayer] 改成「先压首页、挂起一下、再压链」，本用例即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupBrowserLandingTest {

    private lateinit var nav: NavHostController

    /** 本类自己那份会话状态（生产那份由组合根持有） */
    private val session = testSessionState()

    private val history get() = session.browseHistory

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

    /**
     * 落地后：首页在最底、浏览链逐层压在其上，历史是这条链的镜像；
     * 且调用返回时栈已是终态（两跳之间没有挂起点，首页不会被组合到任何一帧）。
     */
    @Test
    fun `落地后 首页在最底 浏览链逐层压在其上`() {
        landStartupBrowserLayer(nav, history, path)

        assertEquals(listOf(Routes.HOME, Routes.BROWSER, Routes.BROWSER), stackRoutes())
        assertEquals("返回时栈顶已是浏览链最深层（没有中间帧）", Routes.BROWSER, nav.currentDestination?.route)
        assertEquals("首页在栈底，不是当前目的地", Routes.HOME, stackRoutes().first())
        assertEquals("浏览历史是这条链的镜像（返回逐级）", BrowseLocation(7, "dir-sub"), history.current)
    }
}
