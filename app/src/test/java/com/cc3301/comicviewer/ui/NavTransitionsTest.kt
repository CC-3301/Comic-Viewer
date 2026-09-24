package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 全局页面过渡的声明口径（票 #111；**整屏滑入划出**——新屏 `translateX` 从 ±100% → 0、旧屏 0 → ∓100%，
 * 见 `AppNav.kt` 的 `NavTransitions` / [NavSlideFrame] / [navSlideOffsetX] 的 KDoc）。
 *
 * 钉住三件事：
 * 1. **方向矩阵**（[navTransitionDirection]，纯函数）：进入阅读器按入口（浏览页点书 / 抽屉「阅读器」= 从右；
 *    冷启动落地 = 只淡入）、退出阅读器**固定反向**、换书按入口给的 `enter` 参数、层级导航压栈从右 / 弹栈从左；
 * 2. **规格常量**：时长 [NavTransitions.DURATION_MILLIS]（300ms）、两屏位移
 *    [NavTransitions.SLIDE_TRAVEL_PERCENT]（100% = 整屏；行程由 [NavSlideFrame] 折进像素，两屏同幅），
 *    以及两条曲线各自的取值；
 * 3. **每屏的位移/亮度算式**（票 #111 r9 C6 自驱之后才有的一层）：[navSlideSpecs] 的**方向矩阵**、
 *    [navSlideOffsetX] 的**符号与整屏幅度**、[navSlideAlpha] 的**只有冷启动才改亮度**，以及
 *    [NavSlideAnimations] 的「同一屏一个 `Animatable`、第一次观察不产生规格」。
 *
 * **驱动方式已从系统改成自驱（票 #111 r9 C6）**：`NavHost` 的四支过渡退化成零视觉空壳
 * （[NavTransitions.holdEnter] / [NavTransitions.holdExit]，alpha 恒 1，只撑重叠窗口），位移与淡入由每屏
 * 自己的 [NavSlideFrame] 驱动。因此 r7/r8 那几条「四支过渡不是同一对象 / 每支只建一次」的结构断言**不再成立**
 * （方向已不由过渡对象承载），换成上面第 3 条那批纯函数断言——判别力**更强**：原来「位移 lambda 到底乘了哪个
 * 比例」读不到，现在 `navSlideOffsetX` 是数值对数值。
 *
 * **自驱之后单测仍咬不住哪一半**（本文件不为它编造断言）：① `NavHost` 的四支 lambda 是否真的返回空壳
 * （`fadeIn(initialAlpha = 1f)` 的参数**不可观测**，反射白名单为空）；② [NavSlideAnimations.observe] 是否真的
 * 在 `NavHost` 内容**之前**被喂了栈、③ 8 个目的地是否**每一个**都包了 [NavSlideFrame]（漏一个就是「那一屏不滑」）；
 * ④ `graphicsLayer` 的实际像素轨迹与手感。前三者要跑 Compose 组合才观测得到，本仓无 Compose UI 测试基建
 * （见 SPEC 的 Testing Decisions）⇒ 守护留在下面的真机清单里。
 *
 * 为什么只钉到「算式 + 常量」这一层：路由级过渡挂在 `ComposeNavigator.Destination` 上，navigation-compose
 * 2.8.1 把那些属性声明为 `internal`，本模块读不到；`NavHost` 自己的四支过渡与 [NavSlideFrame] 的包裹都是
 * **组合期行为**，只有跑 Compose 组合才能观测它们接到哪里，而本仓库没有 Compose UI 测试依赖、SPEC 的
 * Testing Decisions 把 UI 层交给手动验收。余下那条缝（`NavHost(...)` 的四支是否真的返回空壳、`observe` 是否
 * 在 `NavHost` 内容之前喂了栈、8 个目的地是否都包了 [NavSlideFrame]）因此靠**真机判定**：
 *
 * - 进入阅读器（浏览页点书 / 抽屉「阅读器」）：新屏**从右整屏滑入**，300ms，方向可见；
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
 * 单测钉不住的量（票面要求写进清单）：**组合期接线与手感**——`graphicsLayer` 的真实像素轨迹已经由
 * `navSlideOffsetX` **数值对数值**钉住（见 `每屏的位移：压栈从右弹栈从左 旧屏同向移出整屏`），
 * 仍钉不住的是「四支 lambda 是否真的返回空壳」「8 个目的地是否都包了外壳」与「300ms 的曲线手感」
 *（`fadeIn(initialAlpha = 1f)` 的参数、组合树都是过渡对象/组合期的内部，本仓读不到）——因此判据只有
 * 上面的真机目视项。
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
    fun `规格常量就是维护者拍板的那一档 300ms 与整屏`() {
        assertEquals("票面 r9 口径：时长 300ms", 300, NavTransitions.DURATION_MILLIS)
        assertEquals(
            "票面 r7 口径：两屏都走整屏（100%）——新屏 ±100% → 0，旧屏 0 → ∓100%（旧口径的 30% 已整套推翻）",
            100,
            NavTransitions.SLIDE_TRAVEL_PERCENT,
        )
    }

    /**
     * 两条曲线各自的**取值与角色**：对称缓入缓出那条（[NavTransitions.TRANSITION_EASING]）是**两屏位移与冷启动淡入淡出
     * 共用的唯一一条**（票面 r9 口径 `CubicBezier(0.42, 0, 0.58, 1)`）；加速那条（[NavTransitions.EXIT_EASING]）
     * 只剩阅读菜单面板的消失支在用（`ui/ReaderMenuTransitions.kt` 直接读它，不另起别名）。
     *
     * 本用例只钉**两条曲线本身**（数值对数值，改曲线就红）；它不管谁 read 了哪一条（那层读不到，见类 KDoc）。
     */
    @Test
    fun `两条曲线各自仍是那一条`() {
        assertEquals(
            "两屏位移 + 冷启动淡入淡出都读这条对称缓入缓出曲线（票面 r9 口径）：头 100ms 走 23%、两端速度皆为 0",
            CubicBezierEasing(0.42f, 0f, 0.58f, 1f),
            NavTransitions.TRANSITION_EASING,
        )
        assertEquals(
            "加速曲线剩余唯一调用方是阅读菜单的消失支（ReaderMenuTransitions 直接读它）",
            CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
            NavTransitions.EXIT_EASING,
        )
    }

    @Test
    fun `两个空壳过渡都不是零时长`() {
        // 空壳（alpha 恒 1）只用来撑住重叠窗口，但**不能是 None**——None 的话两屏不会重叠，
        // 位移就变成「一屏先走完另一屏才出现」。起点/终点 alpha 都是 1 这一点读不到（反射白名单为空），
        // 由代码审查 + 真机清单守护。
        assertNotSame(EnterTransition.None, transitions.holdEnter)
        assertNotSame(ExitTransition.None, transitions.holdExit)
    }

    // ---------- 每屏的位移 / 亮度算式（票 #111 r9 C6）----------

    /**
     * 位移的**符号与幅度**（数值对数值）：`progress` 的语义是「新屏 0 → 1、旧屏 1 → 0」。
     * 把 `navSlideOffsetX` 里的方向取反、或把 `remaining` 写成 `progress`，这里即红。
     */
    @Test
    fun `每屏的位移：压栈从右弹栈从左 旧屏同向移出整屏`() {
        val width = 1000f
        val travel = width * NavTransitions.SLIDE_TRAVEL_PERCENT / 100f

        // 压栈：新屏从**右**（+整屏）→ 0；旧屏 0 → **左**（−整屏，完全出屏、不残留）
        assertEquals(travel, navSlideOffsetX(NavTransitionDirection.Forward, NavSlideRole.Entering, 0f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(NavTransitionDirection.Forward, NavSlideRole.Entering, 1f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(NavTransitionDirection.Forward, NavSlideRole.Exiting, 1f, width), 0.001f)
        assertEquals(-travel, navSlideOffsetX(NavTransitionDirection.Forward, NavSlideRole.Exiting, 0f, width), 0.001f)

        // 弹栈：**固定反向**（新屏从左、旧屏向右）
        assertEquals(-travel, navSlideOffsetX(NavTransitionDirection.Back, NavSlideRole.Entering, 0f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(NavTransitionDirection.Back, NavSlideRole.Entering, 1f, width), 0.001f)
        assertEquals(travel, navSlideOffsetX(NavTransitionDirection.Back, NavSlideRole.Exiting, 0f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(NavTransitionDirection.Back, NavSlideRole.Exiting, 1f, width), 0.001f)

        // 冷启动落地：**不滑**（整条过渡里恒 0）
        listOf(0f, 0.5f, 1f).forEach { p ->
            assertEquals(0f, navSlideOffsetX(NavTransitionDirection.Fade, NavSlideRole.Entering, p, width), 0.001f)
            assertEquals(0f, navSlideOffsetX(NavTransitionDirection.Fade, NavSlideRole.Exiting, p, width), 0.001f)
        }
    }

    /** 亮度：**只有冷启动那一支改 alpha**（新屏淡入 0→1、旧屏淡出 1→0）；滑入划出那两支恒 1（不做亮度交叉） */
    @Test
    fun `只有冷启动那一支改亮度 滑入划出恒 1`() {
        assertEquals(0f, navSlideAlpha(NavTransitionDirection.Fade, NavSlideRole.Entering, 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionDirection.Fade, NavSlideRole.Entering, 1f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionDirection.Fade, NavSlideRole.Exiting, 1f), 0.001f)
        assertEquals(0f, navSlideAlpha(NavTransitionDirection.Fade, NavSlideRole.Exiting, 0f), 0.001f)

        assertEquals(1f, navSlideAlpha(NavTransitionDirection.Forward, NavSlideRole.Entering, 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionDirection.Forward, NavSlideRole.Exiting, 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionDirection.Back, NavSlideRole.Entering, 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionDirection.Back, NavSlideRole.Exiting, 0f), 0.001f)
    }

    // ---------- 方向矩阵（由栈变化算，票 #111 r9 C6）----------

    /** 栈变化 → 每屏的规格：压栈 / 弹栈 / 冷启动 / 换书（含 replace 时旧屏是谁） */
    @Test
    fun `栈变化决定每屏的方向与角色`() {
        fun specs(previous: List<String>, current: List<String>, routes: Map<String, String>, hints: Map<String, String> = emptyMap()) =
            navSlideSpecs(previous, current, { routes[it] }, { hints[it] })

        // 压栈（层级导航进子文件夹 / 抽屉入口进入）：新屏从右，上一帧的栈顶是被盖住的旧屏
        assertEquals(
            mapOf(
                "b" to NavSlideSpec(NavTransitionDirection.Forward, NavSlideRole.Entering),
                "a" to NavSlideSpec(NavTransitionDirection.Forward, NavSlideRole.Exiting),
            ),
            specs(listOf("a"), listOf("a", "b"), mapOf("a" to Routes.BROWSER, "b" to Routes.BROWSER)),
        )

        // 弹栈（返回上一级）：固定反向，旧屏是**这一帧消失的那一项**
        assertEquals(
            mapOf(
                "a" to NavSlideSpec(NavTransitionDirection.Back, NavSlideRole.Entering),
                "b" to NavSlideSpec(NavTransitionDirection.Back, NavSlideRole.Exiting),
            ),
            specs(listOf("a", "b"), listOf("a"), mapOf("a" to Routes.BROWSER, "b" to Routes.BROWSER)),
        )

        // 冷启动落地：入口显式给 FADE ⇒ 两屏都只淡不滑
        assertEquals(
            mapOf(
                "r" to NavSlideSpec(NavTransitionDirection.Fade, NavSlideRole.Entering),
                "b" to NavSlideSpec(NavTransitionDirection.Fade, NavSlideRole.Exiting),
            ),
            specs(
                listOf("b"),
                listOf("b", "r"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
                mapOf("r" to ReaderEnter.FADE),
            ),
        )

        // 换书「下一本」：入口缺省 = 从右；旧屏是**旧阅读器 entry**（replace 把旧 entry 换成新的）
        assertEquals(
            mapOf(
                "r2" to NavSlideSpec(NavTransitionDirection.Forward, NavSlideRole.Entering),
                "r1" to NavSlideSpec(NavTransitionDirection.Forward, NavSlideRole.Exiting),
            ),
            specs(listOf("r1"), listOf("r2"), mapOf("r1" to Routes.READER, "r2" to Routes.READER)),
        )

        // 换书「上一本」：入口显式给 BACK ⇒ 从左
        assertEquals(
            mapOf(
                "r2" to NavSlideSpec(NavTransitionDirection.Back, NavSlideRole.Entering),
                "r1" to NavSlideSpec(NavTransitionDirection.Back, NavSlideRole.Exiting),
            ),
            specs(
                listOf("r1"),
                listOf("r2"),
                mapOf("r1" to Routes.READER, "r2" to Routes.READER),
                mapOf("r2" to ReaderEnter.BACK),
            ),
        )
    }

    // ---------- 每屏一个 Animatable ----------

    /**
     * 起始目的地**不播过渡**（第一次观察不产生规格）、同一屏的进度动画**只建一次**（重组不重启动画）。
     * 拿掉「第一次观察不产生规格」，AppNav 启动那一屏会自己从屏外滑进来（真机可见的回归）。
     */
    @Test
    fun `起始目的地不播过渡 同一屏的进度动画只建一次`() {
        val slide = NavSlideAnimations()
        val routes = mapOf("start" to Routes.STARTUP, "b" to Routes.BROWSER)
        val routeOf: (String) -> String? = { routes[it] }

        slide.observe(listOf("start"), routeOf, { null })
        assertNull("起始目的地本来就不播过渡", slide.specOf("start"))

        slide.observe(listOf("start", "b"), routeOf, { null })
        assertEquals(
            NavSlideSpec(NavTransitionDirection.Forward, NavSlideRole.Entering),
            slide.specOf("b"),
        )
        assertEquals(
            NavSlideSpec(NavTransitionDirection.Forward, NavSlideRole.Exiting),
            slide.specOf("start"),
        )

        // 栈没变：不重记（重组不重记，也就不会重播）
        val recorded = slide.specOf("b")
        slide.observe(listOf("start", "b"), routeOf, { null })
        assertSame("栈没变就不动", recorded, slide.specOf("b"))

        // 同一屏读两次是同一个 Animatable（重组不重启动画）；不同屏各是一个（判据不是恒真）
        assertSame(
            slide.progressOf("b", NavSlideRole.Entering),
            slide.progressOf("b", NavSlideRole.Entering),
        )
        assertNotSame(
            slide.progressOf("b", NavSlideRole.Entering),
            slide.progressOf("start", NavSlideRole.Exiting),
        )
    }
}
