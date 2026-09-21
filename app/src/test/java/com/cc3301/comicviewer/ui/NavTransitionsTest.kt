package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 全局页面过渡的声明口径（票 #107 AC10）：`NavHost` 四支过渡必须都是 navigation-compose 的零时长
 * 「无动画」值（`EnterTransition.None` / `ExitTransition.None`）——这是 #98 返回空白等待与 #99
 * 「过渡期旧页面仍接收点击」的共同根因所在的**唯一可单测口径**。
 *
 * 为什么只钉到 [NavTransitions] 这一层：
 * - 路由级过渡（`composable(...)` 的 `enterTransition` 等）挂在 `ComposeNavigator.Destination` 上，
 *   navigation-compose 2.8.1 把那些属性声明为 `internal`，本模块读不到（同 `AppNav.kt` 里 READER 路由的
 *   依据注释、`ReaderSwapNavTest` 的「本文件不覆盖的东西」一节）；
 * - `NavHost` 自己的四支过渡是**组合参数**，只有跑 Compose 组合才能观测它们被谁接收，而本仓库没有
 *   Compose UI 测试依赖、SPEC 的 Testing Decisions 把 UI 层交给手动验收。
 * 余下那条缝（`NavHost(...)` 调用点是否真的把四支都接到 [NavTransitions]）因此靠真机判定：
 *
 * **真机判定方法**（AC1/AC2/AC4/AC11）：打开阅读器 → 全面屏手势/鼠标侧键/系统返回键返回，屏幕应**立即**
 * 到位、无空白等待；书柜 A（含子文件夹 C）→ 进入 A → 返回 → **立刻**点与 C 同坐标的 B，应打开 B；
 * 若某支过渡被改回默认，返回时会重新出现约 700ms 的淡入（空白期）或误开。
 */
class NavTransitionsTest {

    @Test
    fun `四支过渡都是零时长无动画`() {
        assertSame("enterTransition 必须是 EnterTransition.None", EnterTransition.None, NavTransitions.enter)
        assertSame("exitTransition 必须是 ExitTransition.None", ExitTransition.None, NavTransitions.exit)
        assertSame("popEnterTransition 必须是 EnterTransition.None", EnterTransition.None, NavTransitions.popEnter)
        assertSame("popExitTransition 必须是 ExitTransition.None", ExitTransition.None, NavTransitions.popExit)
    }

    /** 承上：本用例的判据（同一性比较）能咬住 NavHost 内建的 700ms 淡入淡出，不是恒真断言 */
    @Test
    fun `判据能咬住 NavHost 内建的 700ms 淡入淡出`() {
        assertNotSame(EnterTransition.None, fadeIn(animationSpec = tween(700)))
        assertNotSame(ExitTransition.None, fadeOut(animationSpec = tween(700)))
    }
}
