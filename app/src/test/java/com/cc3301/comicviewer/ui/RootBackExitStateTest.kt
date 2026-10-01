package com.cc3301.comicviewer.ui

import androidx.navigation.NavHostController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 根路由（首页）的返回键「再按一次退出」（票 #128）。
 *
 * 两层各钉一半，缺一不可：
 * ① 反复按的**时间窗判定**是纯状态机 [RootBackExitState]（第一次 ⇒ 提示且不退出；窗内第二次 ⇒ 退出信号；
 *    超时/复位 ⇒ 回到「第一次」状态）——这一半不需要组合；
 * ② 「现在算不算停在根路由」是 [atRootRoute]，跑**真实的 `NavController`**（与 [BrowserBackStackSyncTest] /
 *    [ReaderSwapNavTest] 同一手法，建图走共用 [navHostWith]）。
 *
 * ②必须测的理由：抽屉的「首页」入口会把首页**压在浏览层之上**（票 #70 r2 AC9），那时的返回语义是
 * 「回到进入前的界面」，**不是**退出 APP——只看「栈顶路由是不是首页」就会把这条路吞掉。
 *
 * **本文件不覆盖的东西**（不假称护住了）：`AppNav` 里那个 `BackHandler` 的接线本身（enabled 条件、Toast、
 * `finish()`）——仓库无 Compose UI 测试基建（SPEC 把 UI 层交给手动验收），接线由票面真机项兜住
 * （首页连按两次退出 / 只按一次只闪提示）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootBackExitStateTest {

    private lateinit var nav: NavHostController

    /** 路由图 = 生产的子集，start destination = HOME（与 `AppNav` 落地后的栈底同形） */
    @Before
    fun setUp() {
        nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER, Routes.SETTINGS))
    }

    // ---------- ① 时间窗判定（纯状态机）----------

    @Test
    fun `第一次返回只提示、不退出`() {
        val state = RootBackExitState()

        assertEquals(RootBackAction.PROMPT, state.onBack(nowMillis = 1_000))
    }

    @Test
    fun `窗口内第二次返回给出退出`() {
        val state = RootBackExitState()
        state.onBack(nowMillis = 1_000)

        assertEquals(RootBackAction.EXIT, state.onBack(nowMillis = 1_000 + ROOT_BACK_EXIT_WINDOW_MILLIS - 1))
        assertEquals("窗口边界上当次也算窗内", RootBackAction.EXIT, freshSecondPressAt(ROOT_BACK_EXIT_WINDOW_MILLIS))
    }

    @Test
    fun `超时后回到第一次状态`() {
        val state = RootBackExitState()
        state.onBack(nowMillis = 1_000)

        assertEquals(
            "超时的那一次只是重新提示、不退出",
            RootBackAction.PROMPT,
            state.onBack(nowMillis = 1_000 + ROOT_BACK_EXIT_WINDOW_MILLIS + 1),
        )
        assertEquals("提示之后的窗内第二次照旧退出", RootBackAction.EXIT, state.onBack(nowMillis = 1_500 + ROOT_BACK_EXIT_WINDOW_MILLIS))
    }

    @Test
    fun `退出之后重新从第一次计数`() {
        val state = RootBackExitState()
        state.onBack(nowMillis = 1_000)
        state.onBack(nowMillis = 1_100)

        assertEquals(RootBackAction.PROMPT, state.onBack(nowMillis = 1_200))
    }

    @Test
    fun `复位后窗内第二次不再退出`() {
        val state = RootBackExitState()
        state.onBack(nowMillis = 1_000)

        state.reset()

        assertEquals(RootBackAction.PROMPT, state.onBack(nowMillis = 1_100))
    }

    /** 从零起再按一次、间隔正好一个窗口的判定（边界分支单独取一份状态，免与上面那次按混） */
    private fun freshSecondPressAt(gapMillis: Long): RootBackAction {
        val state = RootBackExitState()
        state.onBack(nowMillis = 0)
        return state.onBack(nowMillis = gapMillis)
    }

    // ---------- ② 是否真正停在根路由 ----------

    @Test
    fun `首页在栈底时算根路由`() {
        assertEquals(Routes.HOME, nav.currentDestination?.route)

        assertTrue(atRootRoute(nav))
    }

    @Test
    fun `压到浏览层之上的首页不算根路由`() {
        nav.navigate(Routes.browser(7L, null))
        navigateTopLevel(nav, Routes.HOME)

        assertEquals("栈顶仍是首页（产物），只是它下面还压着浏览层", Routes.HOME, nav.currentDestination?.route)
        assertFalse(atRootRoute(nav))
    }

    @Test
    fun `别的层级不算根路由`() {
        nav.navigate(Routes.SETTINGS)

        assertFalse(atRootRoute(nav))
    }
}
