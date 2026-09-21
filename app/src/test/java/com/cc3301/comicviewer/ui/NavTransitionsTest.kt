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
 * （不是 `EnterTransition.None` / `ExitTransition.None`），规格是维护者选的那一档——**约 180ms、8dp 位移**。
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
    fun `规格就是维护者选的那一档 180ms 与 8dp`() {
        assertEquals("票面：约 180ms", 180, NavTransitions.DURATION_MILLIS)
        assertEquals("票面：8dp 的轻微位移", 8, NavTransitions.OFFSET_DP)
        assertEquals("时长真的进了过渡规格（可注入，因此这一条咬得住）", 180, transitions.durationMillis)
        assertEquals("位移像素真的进了过渡规格（由调用点按密度算好）", 24, transitions.offsetPx)
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

    /** 承上：判据（同实例比较）能咬住「每次读取都新建」的写法——那正是会让动画被重启的形状 */
    @Test
    fun `判据能咬住每次读取都新建的写法`() {
        assertNotSame(NavTransitions(offsetPx = 24).enter, NavTransitions(offsetPx = 24).enter)
        assertNotSame(NavTransitions(offsetPx = 24).popEnter, NavTransitions(offsetPx = 24).popEnter)
    }

    /**
     * 承上：本用例的判据（不同实例）能咬住 NavHost 内建的 700ms 淡入淡出——#107 修的两个现象（返回空白期、
     * 退场期间旧页仍吃点击）就出在它，而 [NavTransitions] 现在给的是 180ms 的另一档。
     */
    @Test
    fun `判据能咬住 NavHost 内建的 700ms 淡入淡出`() {
        assertNotSame(EnterTransition.None, fadeIn(animationSpec = tween(700)))
        assertNotSame(ExitTransition.None, fadeOut(animationSpec = tween(700)))
    }
}
