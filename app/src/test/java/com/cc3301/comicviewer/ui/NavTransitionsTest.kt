package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 全局页面过渡的声明口径（票 #111，取代票 #107 的「四支零时长」）：`NavHost` 四支过渡必须是**淡入淡出**
 * （不是 `EnterTransition.None` / `ExitTransition.None`），规格是维护者选的那一档——**约 240ms、8dp 位移**
 * （批次 8 定稿 180ms；批次 9 真机反馈「子文件夹返回过渡太短」→ 时长改为 240ms，位移与「纯交叉、不错开」不变）。
 *
 * 为什么只钉到 [NavTransitions] 这一层：
 * - 路由级过渡（`composable(...)` 的 `enterTransition` 等）挂在 `ComposeNavigator.Destination` 上，
 *   navigation-compose 2.8.1 把那些属性声明为 `internal`，本模块读不到（同 `AppNav.kt` 里 READER 路由的
 *   依据注释、`ReaderSwapNavTest` 的「本文件不覆盖的东西」一节）；
 * - `NavHost` 自己的四支过渡是**组合参数**，只有跑 Compose 组合才能观测它们被谁接收，而本仓库没有
 *   Compose UI 测试依赖、SPEC 的 Testing Decisions 把 UI 层交给手动验收。
 * 余下那条缝（`NavHost(...)` 调用点是否真的把四支都接到 [NavTransitions]、且一直交给同一个实例）因此靠真机判定：
 *
 * **真机判定方法**（票 #111 验收 1/2/3）：① 子文件夹里按返回 → 上一屏淡入，不是硬切；② 阅读器里返回浏览页
 * → 同上（这一条同时校验 READER 路由的 `popExitTransition` 已从零时长改成全局淡出）；③ 抽屉里从「设置」返回
 * → 同上。三处都应「一次导航 = 一次过渡」：画面只动一趟，不因为重组重新播一遍。
 *
 * 单测钉不住、只能真机看的部分（票面要求写进清单）：**8dp 位移的方向与幅度**——`slideInVertically` /
 * `slideOutVertically` 的偏移量是 `EnterTransition` 内部的 lambda，本仓读不到（反射白名单只有一处，见 SPEC 的
 * Testing Decisions），因此判据是：返回时上一屏**从上方**轻微下移到位（不是从下方上移、不是左右滑），
 * 幅度只是「轻微」（约 8dp，不是整屏滑）。
 */
class NavTransitionsTest {

    private val transitions = NavTransitions(offsetPx = 24)

    @Test
    fun `四支过渡都不是零时长`() {
        assertNotSame("enterTransition 必须是淡入，不是 EnterTransition.None", EnterTransition.None, transitions.enter)
        assertNotSame("exitTransition 必须是淡出，不是 ExitTransition.None", ExitTransition.None, transitions.exit)
        assertNotSame("popEnterTransition 必须是淡入，不是 EnterTransition.None", EnterTransition.None, transitions.popEnter)
        assertNotSame("popExitTransition 必须是淡出，不是 ExitTransition.None", ExitTransition.None, transitions.popExit)
    }

    @Test
    fun `规格常量就是维护者选的那一档 240ms 与 8dp`() {
        assertEquals("票面（批次 9）：约 240ms", 240, NavTransitions.DURATION_MILLIS)
        assertEquals("票面：8dp 的轻微位移", 8, NavTransitions.OFFSET_DP)
    }

    /**
     * 「一次导航 = 一次过渡」里能在单测里钉住的一半：四支过渡在实例里**只建一次**（属性初始化，不是每次读取
     * 新建）——`NavHost` 的四支 lambda 每次重组返回的就是同一个实例，`AnimatedContent` 因此不会重启动画。
     */
    @Test
    fun `四支过渡只建一次 重组不重启动画`() {
        assertSame(transitions.enter, transitions.enter)
        assertSame(transitions.exit, transitions.exit)
        assertSame(transitions.popEnter, transitions.popEnter)
        assertSame(transitions.popExit, transitions.popExit)
    }

    /**
     * 承上：判据（同实例比较）真的能咬住「每次读取都新建」的写法——那正是会让动画被重启的形状。
     *
     * 用**同形状的替身**（`val enter get() = fadeIn(...)`）而不是拿两个 [NavTransitions] 实例互比：后者比较的是
     * 两个不同对象的属性，对这种形状恒过，咬不到本用例要守的东西（r2 修复：原写法与其宣称不符）。
     */
    @Test
    fun `判据能咬住每次读取都新建的同形状替身`() {
        val recomputed = RecomputedTransitions()

        assertNotSame("替身每次读取都新建 ⇒ 同实例比较会变红（判据不是恒真）", recomputed.enter, recomputed.enter)
        assertSame("对照：生产对象读两次是同一个实例", transitions.enter, transitions.enter)
    }

    /**
     * 承上：`assertNotSame(EnterTransition.None, …)` 这条判据**不是恒真断言**——内建默认的淡变
     * （`fadeIn(tween(700))`，navigation-compose 的 `NavHost` 默认值）与 `EnterTransition.None` 确实是两个不同
     * 实例，因此上面「四支过渡都不是零时长」那组断言才有判别力。
     *
     * 它**不**证明本对象给的是 240ms（换成任何时长的 `fadeIn` 都成立）——时长口径由
     * [NavTransitions.DURATION_MILLIS] 的断言守着（r3 修复：原 KDoc 宣称「能咬住 700ms」，与失败能力不符）。
     */
    @Test
    fun `判据能区分零时长与内建默认淡变 不是恒真断言`() {
        assertNotSame(EnterTransition.None, fadeIn(animationSpec = tween(700)))
        assertNotSame(ExitTransition.None, fadeOut(animationSpec = tween(700)))
    }

    /**
     * 「每次读取都新建」的同形状替身（`val enter get() = fadeIn(...)`）：只为本文件那条反例存在，
     * 不是生产形状（生产是属性初始化，见 [NavTransitions]）。
     */
    private class RecomputedTransitions {
        val enter: EnterTransition get() = fadeIn(animationSpec = tween(NavTransitions.DURATION_MILLIS))
    }
}
