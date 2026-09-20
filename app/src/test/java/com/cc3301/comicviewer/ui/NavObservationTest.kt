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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导航观测点的契约（票 #70 r2 收口）：事件名与输出字段是真机验收「返回被扔回首页/直接退出」的**唯一证据通道**
 * （`PerfTiming` 的 KDoc 只登记它、无法自证）。这里把两者锁住——事件名收成共享常量 [NavEvent]（四处打点引用它，
 * 不再各写一遍字面量）、输出由 [navObservationLine] 统一拼，改名/改字段时本用例先红，避免与文档清单漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavObservationTest {

    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        ServiceLocator.browseHistory.clear()
        val context: Context = ApplicationProvider.getApplicationContext()
        nav = NavHostController(context)
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        val graphNavigator = nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java)
        val graph: NavGraph = graphNavigator.createDestination().apply {
            route = "root"
            setStartDestination(Routes.HOME)
        }
        graph.addDestination(destination(Routes.HOME))
        graph.addDestination(destination(Routes.BROWSER))
        nav.setGraph(graph, null)
    }

    @After
    fun tearDown() {
        ServiceLocator.browseHistory.clear()
    }

    private fun destination(route: String): ComposeNavigator.Destination =
        ComposeNavigator.Destination(
            nav.navigatorProvider.getNavigator(ComposeNavigator::class.java),
        ) { }.apply { this.route = route }

    /** 事件名 = 四处打点共用的字面量；本断言即「单一出处」的守护 */
    @Test
    fun `事件名常量就是四个打点字面量`() {
        assertEquals("nav startup skip", NavEvent.STARTUP_SKIP)
        assertEquals("nav startup land", NavEvent.STARTUP_LAND)
        assertEquals("nav startup fallback", NavEvent.STARTUP_FALLBACK)
        assertEquals("nav browseBack", NavEvent.BROWSE_BACK)
    }

    /** 一行观测给出排查所需的四项：回退栈深度 + 栈顶路由 + 历史游标 + 历史能否后退 */
    @Test
    fun `观测行给出回退栈深度 栈顶路由 历史游标与可否后退`() {
        val history = ServiceLocator.browseHistory
        history.record(BrowseLocation(connId = 7, containerId = "dir-sub"))
        nav.navigate(Routes.browser(7, "dir-sub"))

        val line = navObservationLine(NavEvent.BROWSE_BACK, nav, history)

        assertTrue("事件名前缀：$line", line.startsWith("nav browseBack "))
        assertTrue("栈顶路由：$line", line.contains("route=${Routes.BROWSER}"))
        // 回退栈 = 路由图根 + 首页 + 浏览页（currentBackStack 含图入口本身，与生产观测点同源）
        assertTrue("回退栈深度：$line", line.contains("depth=3"))
        assertTrue("历史游标：$line", line.contains("historyCurrent=7/dir-sub"))
        assertTrue("历史能否后退：$line", line.contains("historyCanGoBack=false"))
    }
}
