package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 全局页面过渡的声明口径（票 #111；**整屏滑入划出**——新屏 `translateX` 从 ±100% → 0、旧屏 0 → ∓100%，
 * 见 `AppNav.kt` 的 `NavTransitions` 类 KDoc）。
 *
 * 钉住三件事：
 * 1. **方向矩阵**（[navTransitionDirection]，纯函数）：进入阅读器按入口（浏览页点书 / 抽屉「阅读器」= 从右；
 *    冷启动落地 = 只淡入）、退出阅读器**固定反向**、换书按入口给的 `enter` 参数、层级导航压栈从右 / 弹栈从左；
 * 2. **规格常量**：时长 [NavTransitions.DURATION_MILLIS]（400ms）、两屏位移
 *    [NavTransitions.SLIDE_TRAVEL_PERCENT]（100% = 整屏；四支**直接读同一个常量** ⇒ 两屏同幅），
 *    以及两条曲线各自的取值；
 * 3. **「一次导航 = 一次过渡」**：四支过渡在实例里**只建一次**（属性初始化），且每个方向各有一支
 *    （不是四支同一个对象）——`NavHost` 的四支 lambda 每次重组返回的就是同一个实例，`AnimatedContent`
 *    因此不重启动画。
 *
 * **「整屏滑入划出」单测咬不住哪一半**（本文件不为它编造断言）：四支的位移 lambda 到底乘了哪个比例
 * （`{ it }` 与 `{ it * SLIDE_TRAVEL_PERCENT / 100 }` 在单测里是同一个不透明 `EnterTransition`/`ExitTransition`）、
 * 哪一支读的是哪条曲线、以及**两屏是否真的不再带亮度交叉**（`fadeIn` / `fadeOut` 的参数不可观测）——
 * `slideInHorizontally` 的 lambda、`fadeIn` 的 `initialAlpha` 与 `CubicBezierEasing` 对象都读不到（反射白名单
 * 为空，见 SPEC 的 Testing Decisions）。上一轮曾用「`ENTER_TRAVEL_PERCENT` 别名 == `EXIT_TRAVEL_PERCENT`」这类
 * 断言充当守护，那是**恒真断言**（别名定义处就是同一个值，改回别的比例仍绿），已在 r2 删除；现在两屏同幅
 * 由代码**单一来源**表达（四支直接读同一个常量、位移与淡入淡出直接读同一条曲线），守护留在下面的真机清单里。
 *
 * 为什么只钉到 [NavTransitions] 这一层：路由级过渡挂在 `ComposeNavigator.Destination` 上，navigation-compose
 * 2.8.1 把那些属性声明为 `internal`，本模块读不到；`NavHost` 自己的四支过渡是**组合参数**，只有跑 Compose
 * 组合才能观测它们被谁接收，而本仓库没有 Compose UI 测试依赖、SPEC 的 Testing Decisions 把 UI 层交给手动验收。
 * 余下那条缝（`NavHost(...)` 调用点是否真的把四支接到 [NavTransitions] 且按方向挑实例）因此靠**真机判定**：
 *
 * - 进入阅读器（浏览页点书 / 抽屉「阅读器」）：新屏**从右整屏滑入**，400ms，方向可见；
 * - 冷启动直接落进阅读器：**只淡入**，不滑；
 * - 退出阅读器：浏览页**从左整屏滑入**（固定反向）；
 * - 换书：「下一本」/ `forward = true` 从右滑入，「上一本」/ `forward = false` 从左滑入；
 * - 层级导航：进入子文件夹 / 抽屉入口从右滑入，返回上一级 / 抽屉返回从左滑入；
 * - **旧屏完全出屏**：旧屏沿同向滑出整整一屏（不再只移 30%、也不再停在半透明）⇒ 过渡结束不残留上一屏
 *   ——真机反馈的「旧屏所有导航都有残影」就是旧口径「只移 30% + alpha 停在 0.55」造成的；
 * - **两屏都不带亮度交叉**：整屏滑入划出期间两屏都不做 alpha 变化（旧口径的 0.55 镜像已整套推翻）；
 * - 两屏同时动、不做错开；系统「移除动画」时确实不播；
 * - 连续快速操作不叠加两层、不重头播。
 *
 * 单测钉不住的量（票面要求写进清单）：**位移的具体像素轨迹与曲线手感**——`slideInHorizontally` &&
 * `CubicBezierEasing` 都是过渡对象内部的 lambda/对象，本仓读不到（反射白名单为空，见 SPEC 的
 * Testing Decisions），因此判据只有上面的真机目视项。
 */
class NavTransitionsTest {

    private val transitions = NavTransitions()

    // ---------- 方向矩阵（纯函数）----------

    /** 进入阅读器：浏览页点书 / 抽屉「阅读器」——初始屏不是阅读器，也不是冷启动中转页 ⇒ 从右滑入 */
    @Test
    fun `进入阅读器按进入方向`() {
        assertEquals(
            NavTransitionDirection.Forward,
            navTransitionDirection(
                push = true,
                initialRoute = Routes.BROWSER,
                targetRoute = Routes.READER,
                enterHint = ReaderEnter.FORWARD,
            ),
        )
        assertEquals(
            "抽屉「阅读器」入口的初始屏是首页/书柜/设置——同样按「进入阅读器」处理",
            NavTransitionDirection.Forward,
            navTransitionDirection(true, Routes.SETTINGS, Routes.READER, null),
        )
    }

    /**
     * 冷启动落地：只淡入。两个分支——
     * ① **入口显式给 [ReaderEnter.FADE]**（生产主路径：旧屏是刚落盘的浏览层，判据猜不出来，
     *    真实顺序由 `StartupReaderTransitionTest` 钉住）；
     * ② 兜底：旧屏是启动中转页（STARTUP）时同样只淡不滑。
     */
    @Test
    fun `冷启动落地只淡入`() {
        assertEquals(
            "入口显式给 FADE：旧屏是刚落盘的浏览层时不能判成进入阅读器",
            NavTransitionDirection.Fade,
            navTransitionDirection(true, Routes.BROWSER, Routes.READER, ReaderEnter.FADE),
        )
        assertEquals(
            "兜底：旧屏是启动中转页",
            NavTransitionDirection.Fade,
            navTransitionDirection(true, Routes.STARTUP, Routes.READER, ReaderEnter.FORWARD),
        )
    }

    /** 退出阅读器：固定反向（浏览页从左滑入），不看 `enter` 参数 */
    @Test
    fun `退出阅读器固定反向`() {
        assertEquals(
            NavTransitionDirection.Back,
            navTransitionDirection(false, Routes.READER, Routes.BROWSER, null),
        )
    }

    /** 换书：方向**由入口显式给出**（`下一本` / `forward = true` = 从右；`上一本` / `forward = false` = 从左） */
    @Test
    fun `换书方向由入口显式给出`() {
        assertEquals(
            NavTransitionDirection.Forward,
            navTransitionDirection(true, Routes.READER, Routes.READER, ReaderEnter.FORWARD),
        )
        assertEquals(
            NavTransitionDirection.Back,
            navTransitionDirection(true, Routes.READER, Routes.READER, ReaderEnter.BACK),
        )
        assertEquals(
            "缺省（没有方向参数）按「进入阅读器」处理，不猜方向",
            NavTransitionDirection.Forward,
            navTransitionDirection(true, Routes.READER, Routes.READER, null),
        )
    }

    /** 层级导航：压栈从右、弹栈从左（抽屉入口进入/返回同理） */
    @Test
    fun `层级导航压栈从右弹栈从左`() {
        assertEquals(
            NavTransitionDirection.Forward,
            navTransitionDirection(true, Routes.BROWSER, Routes.BROWSER, null),
        )
        assertEquals(
            NavTransitionDirection.Back,
            navTransitionDirection(false, Routes.BROWSER, Routes.BROWSER, null),
        )
    }

    // ---------- 规格常量 ----------

    @Test
    fun `规格常量就是维护者拍板的那一档 400ms 与整屏`() {
        assertEquals("票面 r7 口径：时长 400ms", 400, NavTransitions.DURATION_MILLIS)
        assertEquals(
            "票面 r7 口径：两屏都走整屏（100%）——新屏 ±100% → 0，旧屏 0 → ∓100%（旧口径的 30% 已整套推翻）",
            100,
            NavTransitions.SLIDE_TRAVEL_PERCENT,
        )
    }

    /**
     * 两条曲线各自的**取值与角色**：缓入缓出那条（[NavTransitions.TRANSITION_EASING]）是**两屏位移与冷启动淡入淡出
     * 共用的唯一一条**（票面 r7 口径 `CubicBezier(0.2, 0, 0, 1)`）；加速那条（[NavTransitions.EXIT_EASING]）
     * 只剩阅读菜单面板的消失支在用（`ui/ReaderMenuTransitions.kt` 直接读它，不另起别名）。
     *
     * 本用例只钉**两条曲线本身**（数值对数值，改曲线就红）；它不管谁 read 了哪一条（那层读不到，见类 KDoc）。
     */
    @Test
    fun `两条曲线各自仍是那一条`() {
        assertEquals(
            "两屏位移 + 冷启动淡入淡出都读这条缓入缓出曲线（票面 r7 口径）：起步缓、中段快、收尾缓",
            CubicBezierEasing(0.2f, 0f, 0f, 1f),
            NavTransitions.TRANSITION_EASING,
        )
        assertEquals(
            "加速曲线剩余唯一调用方是阅读菜单的消失支（ReaderMenuTransitions 直接读它）",
            CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
            NavTransitions.EXIT_EASING,
        )
    }

    @Test
    fun `六支过渡都不是零时长`() {
        assertNotSame(EnterTransition.None, transitions.enter(NavTransitionDirection.Forward))
        assertNotSame(EnterTransition.None, transitions.enter(NavTransitionDirection.Back))
        assertNotSame(EnterTransition.None, transitions.enter(NavTransitionDirection.Fade))
        assertNotSame(ExitTransition.None, transitions.exit(NavTransitionDirection.Forward))
        assertNotSame(ExitTransition.None, transitions.exit(NavTransitionDirection.Back))
        assertNotSame(ExitTransition.None, transitions.exit(NavTransitionDirection.Fade))
    }

    /** 方向真的换了一支（不是三支同对象——那样「从右/从左/只淡入」就白写了） */
    @Test
    fun `三个方向各是一支 不是同一对象`() {
        assertNotEquals(
            transitions.enter(NavTransitionDirection.Forward),
            transitions.enter(NavTransitionDirection.Back),
        )
        assertNotEquals(
            transitions.enter(NavTransitionDirection.Back),
            transitions.enter(NavTransitionDirection.Fade),
        )
    }

    /**
     * 「一次导航 = 一次过渡」里能在单测里钉住的一半：每支过渡在实例里**只建一次**（属性初始化，不是每次读取
     * 新建）——`NavHost` 的四支 lambda 每次重组返回的就是同一个实例，`AnimatedContent` 因此不会重启动画。
     */
    @Test
    fun `每支过渡只建一次 重组不重启动画`() {
        NavTransitionDirection.entries.forEach { direction ->
            assertSame(transitions.enter(direction), transitions.enter(direction))
            assertSame(transitions.exit(direction), transitions.exit(direction))
        }
    }

    /**
     * 承上：判据（同实例比较）真的能咬住「每次读取都新建」的写法——那正是会让动画被重启的形状。
     * 用**同形状的替身**（`val enter get() = fadeIn(...)`）而不是拿两个 [NavTransitions] 实例互比。
     */
    @Test
    fun `判据能咬住每次读取都新建的同形状替身`() {
        val recomputed = RecomputedTransitions()

        assertNotSame("替身每次读取都新建 ⇒ 同实例比较会变红（判据不是恒真）", recomputed.enter, recomputed.enter)
        assertSame("对照：生产对象读两次是同一个实例", transitions.enter(NavTransitionDirection.Back), transitions.enter(NavTransitionDirection.Back))
    }

    /** 「每次读取都新建」的同形状替身：只为本文件那条反例存在，不是生产形状 */
    private class RecomputedTransitions {
        val enter: EnterTransition get() = fadeIn(animationSpec = tween(NavTransitions.DURATION_MILLIS))
    }
}
