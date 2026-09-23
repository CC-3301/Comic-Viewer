package com.cc3301.comicviewer.ui

import androidx.navigation.NavHostController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冷启动直进阅读器的过渡方向（票 #111 AC-2，修复轮）：**只淡入**。
 *
 * 为什么要用真实落地顺序而不是喂合成的中转页输入：`AppNav` 的启动落地先把**落盘路径上的浏览层**压到
 * 阅读器之下（`resetBrowseHistoryForStartup` + `pushBrowserPath`），再等 #108 前置（就绪或 1.5s 超时）后
 * 才导航到阅读器 —— 因此这一屏的旧屏是**浏览层**，不是 [Routes.STARTUP]。只按「旧屏是不是中转页」判方向的
 * 兜底分支在这条常见路径上取不到：它会把冷启动判成「进入阅读器」而从右滑入（与旧屏同幅的那一支），
 * 还会先多播一次 STARTUP→BROWSER 的横向滑入。
 *
 * 判据（能咬住回归）：冷启动那条导航必须**显式给** [ReaderEnter.FADE]——本用例按真实顺序搭好
 * 「首页 + 浏览层」的回退栈，再调 [navigateStartupReader]，断言 ① 阅读器 entry 的方向参数是 FADE，
 * ② 拿那时的旧屏（BROWSER）跑 [navTransitionDirection] 得到 [NavTransitionDirection.Fade]。
 * 少了 ① 时本用例立刻红（路由参数的默认值是 forward）。
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
    fun `旧屏是刚落盘的浏览层时 冷启动仍然只淡入`() {
        nav.navigate(Routes.browser(1L, "lib"))
        val oldScreen = nav.currentBackStackEntry?.destination?.route
        assertEquals("前置条件：旧屏确实是浏览层（不是中转页）", Routes.BROWSER, oldScreen)

        navigateStartupReader(nav, "book-a")

        val enterHint = nav.currentBackStackEntry?.arguments?.getString(ARG_READER_ENTER)
        assertEquals("冷启动那条必须显式给 FADE（旧屏是浏览层，猜不出来）", ReaderEnter.FADE, enterHint)
        assertEquals(
            "真实回退栈（首页 + 浏览层）下方向必须是 Fade；判成 Forward 就是从右滑入",
            NavTransitionDirection.Fade,
            navTransitionDirection(
                push = true,
                initialRoute = oldScreen,
                targetRoute = Routes.READER,
                enterHint = enterHint,
            ),
        )
    }
}
