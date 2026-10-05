package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.ui.Routes
import com.cc3301.comicviewer.ui.navHostWith

import androidx.navigation.NavHostController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冷启动直进阅读器的过渡：**与普通进档同款滑入**（进 400ms，从右滑入；不再有交叉淡变）。
 *
 * 为什么要用真实落地顺序而不是喂合成的中转页输入：`AppNav` 的启动落地先把**落盘路径上的浏览层**压到
 * 阅读器之下（`resetBrowseHistoryForStartup` + `pushBrowserPath`），再等前置（就绪或 1.5s 超时）后
 * 才导航到阅读器 —— 因此这一屏的旧屏是**浏览层**，不是 [Routes.STARTUP]。用真实栈验证判据，
 * 才能咬住「冷启动落地被误判成别的档」这类回归。
 *
 * 判据（能咬住回归）：按真实顺序搭好「首页 + 浏览层」的回退栈，再调 [navigateStartupReader]，
 * 断言 ① 阅读器路由上**没有**方向参数（不再有冷启动专用的方向通道），② 拿那时的旧屏（BROWSER）
 * 跑 [navTransitionStyle] 得到 [NavTransitionStyle.Slide]、方向是「进」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupReaderTransitionTest {

    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER, Routes.READER))
    }

    @Test
    fun `旧屏是刚落盘的浏览层时 冷启动与普通进档同款滑入`() {
        nav.navigate(Routes.browser(1L, "lib"))
        val oldScreen = nav.currentBackStackEntry?.destination?.route
        assertEquals("前置条件：旧屏确实是浏览层（不是中转页）", Routes.BROWSER, oldScreen)

        navigateStartupReader(nav, "book-a")

        val entry = nav.currentBackStackEntry!!
        assertEquals("落点是阅读器", Routes.READER, entry.destination.route)
        assertNull(
            "路由上没有方向参数（冷启动并入进档后，方向通道整套作废）",
            entry.arguments?.getString("enter"),
        )
        assertEquals(
            "真实回退栈（首页 + 浏览层）下是滑入（与普通进档同款）",
            NavTransitionStyle.Slide,
            navTransitionStyle(initialRoute = oldScreen, targetRoute = Routes.READER),
        )
        assertEquals(
            NavSlideDirection.IntoReader,
            navSlideDirection(initialRoute = oldScreen, targetRoute = Routes.READER),
        )
        assertEquals(400, navTransitionWindowMillis(oldScreen, Routes.READER))
    }
}
