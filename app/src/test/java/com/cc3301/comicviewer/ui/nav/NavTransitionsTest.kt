package com.cc3301.comicviewer.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.snapshots.Snapshot
import com.cc3301.comicviewer.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全局页面过渡的声明口径：**只有进出阅读器有动画，且是静态背景滑动**——一次过渡里只有一屏动，
 * 被盖住的旧屏原地静止当背景（进 = 阅读页从右滑入 400ms；出 = 浏览页从左滑入 300ms，镜像）；
 * 层级导航与换书**硬切**；冷启动落进阅读器与普通进档同款滑入（不再有交叉淡变）。
 *
 * 钉住四件事：
 * 1. **呈现方式**（[navTransitionStyle]，纯函数）：文件夹 ↔ 阅读器两向是 [NavTransitionStyle.Slide]；
 *    **层级导航与换书是 [NavTransitionStyle.Cut]**；
 * 2. **规格常量**：[NavTransitions.ENTER_READER_DURATION_MILLIS]（400ms）、
 *    [NavTransitions.EXIT_READER_DURATION_MILLIS]（300ms）、两屏位移
 *    [NavTransitions.SLIDE_TRAVEL_PERCENT]（100% = 整屏），以及两条曲线各自的取值；
 * 3. **一次过渡只有一屏动**：[navSlideMoves]（哪一屏动）与 [navSlideOffsetX] 的符号与幅度——
 *    动的那一屏永远是**新屏**（进 = 阅读页从右滑入；出 = 浏览页从左滑入），静止那一屏位移恒 0；
 * 4. **规格是可观察状态**：`NavSlideFrame` 在组合期读 [NavSlideAnimations.specOf]，
 *    而静止侧（旧屏或新屏）那时已在组合里 —— 规格是普通字段时它变了不会让那一屏重组，
 *    它就一直拿着上一份规格（进度停在初值、位移停在 0）。
 *
 * **驱动方式是自驱**：`NavHost` 的四支过渡退化成零视觉空壳
 * （[NavTransitions.holdEnter] / [NavTransitions.holdExit]，alpha 恒 1，只撑重叠窗口；硬切那一档是 `None`），
 * 位移由每屏自己的 [NavSlideFrame] 驱动，**启动点在 `observe`（组合期、`NavHost` 内容之前）**，
 * 不等新屏首次组合（见 `规格一更新就把动的那一屏点起来 不必等新屏组合 硬切不点`）。
 * **仍咬不住的是接线那一半**（本文件不为它编造断言）：
 * ① 四支 lambda 是否真的返回空壳（`fadeIn(initialAlpha = 1f)` 的参数**不可观测**，反射白名单为空）；
 * ② [NavSlideAnimations.observe] 是否真的在 `NavHost` 内容**之前**被喂了栈；
 * ③ 8 个目的地是否**每一个**都包了 [NavSlideFrame]（漏一个就是「那一屏不动/不被包住」）；
 * ④ `graphicsLayer` 的实际像素轨迹、手感与真正的**逐帧观感与帧时长**（设备取数）。
 * 前三者要跑 Compose 组合才观测得到，本仓无 Compose UI 测试基建（见 SPEC 的 Testing Decisions）
 * ⇒ 守护留在设备清单里。
 *
 * 设备目视项（交付后人工过一遍）：进阅读器点了就看见在滑、滑到一半时另一半是静止的浏览页 ·
 * 进 400ms「起步快、末尾缓停」· 出阅读器**镜像**（阅读页往右走、浏览页原地静止）、300ms ·
 * 层级导航与换书**瞬间换屏、没有动画** · 冷启动落进阅读器也是同款滑入 ·
 * 进档新屏没就绪时不画占位（屏上是根背景纯色）、首图就绪直接呈现 · 系统「移除动画」时不播过渡。
 */
class NavTransitionsTest {

    private val transitions = NavTransitions()

    /** 造一条规格（测试里的写法统一在这里，免得每处把四个字段抄一遍） */
    private fun spec(
        style: NavTransitionStyle,
        role: NavSlideRole,
        durationMillis: Int,
        direction: NavSlideDirection? = null,
    ) = NavSlideSpec(style, role, durationMillis, direction)

    // ---------- 呈现方式（纯函数）----------

    /**
     * **只有「文件夹 ↔ 阅读器」有动画，其余一律硬切**。冷启动落进阅读器与普通进档同款
     * （它的旧屏是刚落盘的浏览层，路由判据判不出也不必判：进阅读器一律滑入）。
     */
    @Test
    fun `只有文件夹与阅读器之间才滑动 其余硬切`() {
        assertEquals(
            "浏览页点书进阅读器：滑入",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.BROWSER, Routes.READER),
        )
        assertEquals(
            "抽屉「阅读器」入口的初始屏是首页/书柜/设置：同样滑入",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.SETTINGS, Routes.READER),
        )
        assertEquals(
            "冷启动直进阅读器（旧屏是启动中转页）：与普通进档同款滑入",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.STARTUP, Routes.READER),
        )
        assertEquals(
            "返回（阅读器 → 浏览页）：**镜像滑动**",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.READER, Routes.BROWSER),
        )
        assertEquals(
            "换书（阅读器 → 阅读器）：硬切（必须先于「进阅读器」那一支判掉：它同样以阅读器为落点）",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.READER, Routes.READER),
        )
        assertEquals(
            "层级导航（浏览页 → 浏览页）：硬切",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.BROWSER, Routes.BROWSER),
        )
        assertEquals(
            "抽屉顶层入口（设置 → 首页这类同级顶层）：硬切",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.SETTINGS, Routes.HOME),
        )
        assertEquals(
            "启动落地（中转页 → 首页）：硬切（没有旧屏可看，也不需要过渡）",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.STARTUP, Routes.HOME),
        )
    }

    /** 滑动档的镜像方向（纯函数）：落点是阅读器就是「进」，旧屏是阅读器（落点不是）就是「出」 */
    @Test
    fun `滑动档的方向按前后路由分进与出`() {
        assertEquals(
            NavSlideDirection.IntoReader,
            navSlideDirection(Routes.BROWSER, Routes.READER),
        )
        assertEquals(
            "抽屉「阅读器」入口（初始屏是设置）同样是「进阅读器」",
            NavSlideDirection.IntoReader,
            navSlideDirection(Routes.SETTINGS, Routes.READER),
        )
        assertEquals(
            "冷启动直进阅读器：也是「进」（与普通进档同款）",
            NavSlideDirection.IntoReader,
            navSlideDirection(Routes.STARTUP, Routes.READER),
        )
        assertEquals(
            NavSlideDirection.OutOfReader,
            navSlideDirection(Routes.READER, Routes.BROWSER),
        )
        assertNull("换书是硬切：没有方向", navSlideDirection(Routes.READER, Routes.READER))
        assertNull(
            "层级导航是硬切：没有方向",
            navSlideDirection(Routes.BROWSER, Routes.BROWSER),
        )
    }

    // ---------- 规格常量 ----------

    @Test
    fun `规格常量就是那两档时长与整屏`() {
        assertEquals("进阅读器 400ms", 400, NavTransitions.ENTER_READER_DURATION_MILLIS)
        assertEquals("出阅读器 300ms（镜像，比进快一点）", 300, NavTransitions.EXIT_READER_DURATION_MILLIS)
        assertEquals(
            "两屏都走整屏（100%）——滑动那一屏是整个行程、完全出屏（终点不残留影像）",
            100,
            NavTransitions.SLIDE_TRAVEL_PERCENT,
        )
    }

    /**
     * 时长判定（纯函数，由每屏的动画与量测窗口共用）：进 400 / 出 300 / 硬切 0。
     */
    @Test
    fun `过渡时长 进 400 出 300 硬切 0`() {
        assertEquals(
            "进阅读器（文件夹 → 阅读器）：400ms",
            400,
            navTransitionWindowMillis(Routes.BROWSER, Routes.READER),
        )
        assertEquals(
            "出阅读器（阅读器 → 浏览页）：300ms",
            300,
            navTransitionWindowMillis(Routes.READER, Routes.BROWSER),
        )
        assertEquals(
            "冷启动直进阅读器：也是 400ms（并入进档，不再有单独一档）",
            400,
            navTransitionWindowMillis(Routes.STARTUP, Routes.READER),
        )
        assertEquals(
            "换书：0（硬切没有过渡窗口）",
            0,
            navTransitionWindowMillis(Routes.READER, Routes.READER),
        )
        assertEquals(
            "文件夹之间：0（硬切没有过渡窗口）",
            0,
            navTransitionWindowMillis(Routes.BROWSER, Routes.BROWSER),
        )
    }

    /**
     * 两条曲线各自的**取值与角色**：[navSlideEasing] 按**档**（style + 方向）取。
     * 本用例只钉**曲线本身**（数值对数值，改曲线就红）。
     */
    @Test
    fun `两条曲线各自仍是那一条`() {
        assertEquals(
            "进阅读器：起步快、末尾缓停（CubicBezier(0.35, 0.7, 0.7, 1)）",
            CubicBezierEasing(0.35f, 0.7f, 0.7f, 1f),
            navSlideEasing(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertEquals(
            "出阅读器：起步慢、越滑越快、终点不减速（CubicBezier(0.4, 0, 1, 1)）",
            CubicBezierEasing(0.4f, 0f, 1f, 1f),
            navSlideEasing(NavTransitionStyle.Slide, NavSlideDirection.OutOfReader),
        )
    }

    /**
     * 本用例的判据：**「Slide 却没有方向」「硬切取曲线」这两格不可达，而且必须是响的**——
     * 不可达的分支直接 `error`，不静默给一个数（仓库先例：`ui/CoverThumb.kt`）。
     */
    @Test
    fun `不可达的 null 方向是响的 不再静默给一个数`() {
        val easing = runCatching { navSlideEasing(NavTransitionStyle.Slide, null) }.exceptionOrNull()
        assertTrue("Slide 却没有方向：曲线这一处必须直接报错", easing is IllegalStateException)

        val offset = runCatching {
            navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400), 0f, 1000f)
        }.exceptionOrNull()
        assertTrue("Slide 却没有方向：位移这一处必须直接报错", offset is IllegalStateException)

        val cut = runCatching { navSlideEasing(NavTransitionStyle.Cut, null) }.exceptionOrNull()
        assertTrue("硬切不建动画：取曲线这一格也必须直接报错", cut is IllegalStateException)

    }

    /**
     * 空壳过渡（只撑重叠窗口）：**滑动两档不是 `None`**（`None` 的话两屏不会重叠，
     * 静止的那一屏就不在屏上）；**硬切那一档必须是 `None`**（不建动画、也不留重叠窗口，
     * 退场屏当帧不在）。起点 / 终点 alpha 都是 1 这一点读不到（反射白名单为空），由代码审查 + 设备清单守护。
     */
    @Test
    fun `滑动的空壳不是零时长 硬切那一档是 None`() {
        assertNotSame(
            EnterTransition.None,
            transitions.holdEnter(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertNotSame(
            EnterTransition.None,
            transitions.holdEnter(NavTransitionStyle.Slide, NavSlideDirection.OutOfReader),
        )
        assertNotSame(
            ExitTransition.None,
            transitions.holdExit(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertNotSame(
            ExitTransition.None,
            transitions.holdExit(NavTransitionStyle.Slide, NavSlideDirection.OutOfReader),
        )

        assertSame(
            "硬切：不留重叠窗口（层级导航 / 换书）",
            EnterTransition.None,
            transitions.holdEnter(NavTransitionStyle.Cut, null),
        )
        assertSame(
            "硬切：不留重叠窗口（层级导航 / 换书）",
            ExitTransition.None,
            transitions.holdExit(NavTransitionStyle.Cut, null),
        )
    }

    // ---------- 一次过渡只有一屏动 ----------

    /**
     * 哪一屏动（纯函数）：**动的永远是「新屏」**（进出都一样），旧屏原地静止当背景；硬切没有动画。
     *
     * 为什么是新屏：`NavHost` 出档把新屏追加到可见列表末尾，Compose 又在 zIndex 相同时按放置先后画 ⇒
     * 新屏永远画在最上面；被盖住的旧屏若在动，滑出根本看不见（曾经的「退出瞬间换屏」）。
     */
    @Test
    fun `动的是新屏 旧屏两次都静止`() {
        val into = NavSlideDirection.IntoReader
        val out = NavSlideDirection.OutOfReader

        assertTrue("进阅读器：新屏（阅读页）从右滑入", navSlideMoves(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400, into)))
        assertFalse("进阅读器：旧屏（浏览页）原地静止当背景", navSlideMoves(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 400, into)))
        assertTrue("出阅读器：新屏（浏览页）从左滑入——动的是它，才不会被盖住", navSlideMoves(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 300, out)))
        assertFalse("出阅读器：旧屏（阅读页）原地静止当背景", navSlideMoves(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 300, out)))
        assertFalse("硬切没有动画", navSlideMoves(spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0)))
        assertFalse("硬切没有动画", navSlideMoves(spec(NavTransitionStyle.Cut, NavSlideRole.Exiting, 0)))
    }

    /**
     * 位移的**符号与幅度**（数值对数值）：`progress` 的语义是「新屏 0 → 1、旧屏 1 → 0」。
     * 动的是新屏，从屏外滑到 0：进从**右**（+整屏）、出从**左**（−整屏）；旧屏位移**恒 0**；硬切不滑。
     * 把 [navSlideOffsetX] 的符号取反、或把 `remaining` 写成 `progress`，这里即红。
     */
    @Test
    fun `新屏从屏外滑到 0 旧屏位移恒 0`() {
        val width = 1000f
        val travel = width * NavTransitions.SLIDE_TRAVEL_PERCENT / 100f
        val into = NavSlideDirection.IntoReader
        val out = NavSlideDirection.OutOfReader

        // 进阅读器：新屏（阅读页）从**右**（+整屏）→ 0
        assertEquals(travel, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400, into), 0f, width), 0.001f)
        assertEquals(travel / 2f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400, into), 0.5f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400, into), 1f, width), 0.001f)

        // 出阅读器：**镜像**——新屏（浏览页）从**左**（−整屏）→ 0
        assertEquals(-travel, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 300, out), 0f, width), 0.001f)
        assertEquals(-travel / 2f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 300, out), 0.5f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 300, out), 1f, width), 0.001f)

        // 旧屏（进档的浏览页 / 出档的阅读页）：整场过渡恒 0——静止侧是这次形态的前提
        listOf(0f, 0.5f, 1f).forEach { p ->
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 400, into), p, width), 0.001f)
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 300, out), p, width), 0.001f)
        }

        // 硬切：**不滑**（整条过渡里恒 0）
        listOf(0f, 0.5f, 1f).forEach { p ->
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0), p, width), 0.001f)
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Cut, NavSlideRole.Exiting, 0), p, width), 0.001f)
        }
    }

    // ---------- 每屏的规格（由栈变化算）----------

    /**
     * 栈变化 → 每屏的规格：进阅读器 / 出阅读器 / 冷启动 / 硬切（层级导航与换书）各自的呈现方式、方向、角色与**时长**。
     * 这张表同时是「**两屏都拿到规格**」的判据（静止那一屏也要有规格：帧壳要包住它、`firstDraw` 才有得记）。
     */
    @Test
    fun `栈变化决定每屏的呈现方式方向角色与时长`() {
        fun specs(previous: List<String>, current: List<String>, routes: Map<String, String>) =
            navSlideSpecs(previous, current, { routes[it] })

        val into = NavSlideDirection.IntoReader
        val out = NavSlideDirection.OutOfReader

        // 进阅读器（浏览页点书）：新屏（阅读页）从右滑入 400ms；旧屏（浏览页）静止当背景、同长同向（同一份规格）
        assertEquals(
            mapOf(
                "r" to spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400, into),
                "b" to spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 400, into),
            ),
            specs(
                listOf("b"),
                listOf("b", "r"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
            ),
        )

        // 出阅读器（返回）：**镜像** 300ms——旧屏（阅读页）往右滑出，新屏（浏览页）静止当背景
        assertEquals(
            mapOf(
                "b" to spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 300, out),
                "r" to spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 300, out),
            ),
            specs(
                listOf("b", "r"),
                listOf("b"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
            ),
        )

        // 冷启动直进阅读器：与普通进档同一份规格（并入进档）
        assertEquals(
            mapOf(
                "r" to spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400, into),
                "b" to spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 400, into),
            ),
            specs(
                listOf("b"),
                listOf("b", "r"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
            ),
        )

        // 换书：旧屏是**旧阅读器 entry**（replace 把旧 entry 换成新的），两屏都是硬切、时长 0、没有方向
        assertEquals(
            mapOf(
                "r2" to spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0),
                "r1" to spec(NavTransitionStyle.Cut, NavSlideRole.Exiting, 0),
            ),
            specs(listOf("r1"), listOf("r2"), mapOf("r1" to Routes.READER, "r2" to Routes.READER)),
        )

        // 层级导航（进子文件夹 / 返回上一级）：同样的硬切——旧屏是这一帧消失的那一项
        val folder = mapOf("a" to Routes.BROWSER, "b" to Routes.BROWSER)
        assertEquals(
            mapOf(
                "b" to spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0),
                "a" to spec(NavTransitionStyle.Cut, NavSlideRole.Exiting, 0),
            ),
            specs(listOf("a"), listOf("a", "b"), folder),
        )
        assertEquals(
            mapOf(
                "a" to spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0),
                "b" to spec(NavTransitionStyle.Cut, NavSlideRole.Exiting, 0),
            ),
            specs(listOf("a", "b"), listOf("a"), folder),
        )
    }

    // ---------- 每屏一个 Animatable ----------

    /**
     * 起始目的地**不播过渡**（第一次观察不产生规格）、同一屏的进度动画**只建一次**（重组不重启动画）。
     * 拿掉「第一次观察不产生规格」，AppNav 启动那一屏会自己从屏外滑进来（设备上可见的回归）。
     */
    @Test
    fun `起始目的地不播过渡 同一屏的进度动画只建一次`() {
        val slide = NavSlideAnimations(AnimationLauncher {})
        val routes = mapOf("b" to Routes.BROWSER, "r" to Routes.READER)
        val routeOf: (String) -> String? = { routes[it] }

        slide.observe(listOf("b"), routeOf)
        assertNull("起始目的地本来就不播过渡", slide.specOf("b"))

        slide.observe(listOf("b", "r"), routeOf)
        assertEquals(
            spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 400, NavSlideDirection.IntoReader),
            slide.specOf("r"),
        )
        assertEquals(
            spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 400, NavSlideDirection.IntoReader),
            slide.specOf("b"),
        )

        // 栈没变：不重记（重组不重记，也就不会重播）
        val recorded = slide.specOf("r")
        slide.observe(listOf("b", "r"), routeOf)
        assertSame("栈没变就不动", recorded, slide.specOf("r"))

        // 同一屏读两次是同一个 Animatable（重组不重启动画）；不同屏各是一个（判据不是恒真）
        assertSame(
            slide.progressOf("r", NavSlideRole.Entering),
            slide.progressOf("r", NavSlideRole.Entering),
        )
        assertNotSame(
            slide.progressOf("r", NavSlideRole.Entering),
            slide.progressOf("b", NavSlideRole.Exiting),
        )
    }

    /**
     * 根因判据：**读规格必须是快照状态的读**。
     *
     * `NavSlideFrame` 在组合期读 `specs`；**静止侧**（旧屏或新屏）那时已经在组合里了，
     * 只有「读的是可观察状态」这一条才会让 Compose 在规格变化时失效它的组合作用域、
     * 让它重组并拿到本帧的规格。规格退回普通字段时本用例是**红的**（读观察者收不到任何状态读）。
     */
    @Test
    fun `旧屏那侧的规格读取是可观察状态 否则它不会重组`() {
        val slide = NavSlideAnimations(AnimationLauncher {})
        val routes = mapOf("b" to Routes.BROWSER, "r" to Routes.READER)
        val routeOf: (String) -> String? = { routes[it] }
        slide.observe(listOf("b"), routeOf)
        slide.observe(listOf("b", "r"), routeOf)
        assertEquals(
            "前置条件：旧屏在这一帧拿到了 Exiting 规格",
            spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 400, NavSlideDirection.IntoReader),
            slide.specOf("b"),
        )

        val readStates = mutableListOf<Any>()
        Snapshot.observe(readObserver = { readStates += it }) { slide.specOf("b") }

        assertTrue(
            "specOf 必须留下快照读依赖（普通字段时收不到任何读 ⇒ 静止侧不重组 ⇒ 拿不到本帧规格）",
            readStates.isNotEmpty(),
        )
    }

    /**
     * 本用例的判据：**规格一更新就把动的那一屏点起来**——与规格同一步（组合期、`NavHost` 内容之前），
     * 不等新屏首次组合 + 布局之后的 `LaunchedEffect`；**静止那一屏不起动画**（它原地不动，
     * 进度动画对它没有意义，建了也是白占一个 `Animatable`）。
     * **硬切不点动画**（不建动画、也不留窗口）。
     *
     * 本用例在**没有跑任何组合**的纯单测里数启动次数，因此能咬住三件事：起始目的地不起（第一次观察无规格）、
     * 一次过渡只起动的那一屏、栈没变不重复起（也不重播）。
     */
    @Test
    fun `规格一更新就把动的那一屏点起来 不必等新屏组合 硬切不点`() {
        var started = 0
        val launcher = AnimationLauncher { started++ }
        val routes = mapOf("b" to Routes.BROWSER, "r" to Routes.READER)
        val routeOf: (String) -> String? = { routes[it] }

        // 进阅读器：只动新屏（阅读页）
        val enter = NavSlideAnimations(launcher)
        enter.observe(listOf("b"), routeOf)
        assertEquals("起始目的地没有规格 ⇒ 不起动画", 0, started)
        enter.observe(listOf("b", "r"), routeOf)
        assertEquals("一次过渡只起动的那一屏（静止侧不建进度动画）", 1, started)
        assertEquals(
            "动的那一屏首帧读到的进度就是它被点起时的初值（还在屏外）",
            0f,
            enter.progressOf("r", NavSlideRole.Entering).value,
            0.001f,
        )
        enter.observe(listOf("b", "r"), routeOf)
        assertEquals("栈没变：不重复起（也不重播）", 1, started)

        // 出阅读器：只动旧屏（阅读页）
        val exit = NavSlideAnimations(launcher)
        exit.observe(listOf("b", "r"), routeOf)
        exit.observe(listOf("b"), routeOf)
        assertEquals("出档也只起动的那一屏", 2, started)

        // 硬切（换书）：规格照旧算出来（NavSlideFrame 据此包节点、位移恒 0），但**不建动画**、也不点任何缓冲
        val cut = NavSlideAnimations(launcher)
        cut.observe(listOf("r1"), { id -> if (id == "r1") Routes.READER else null })
        cut.observe(listOf("r2"), { id -> if (id == "r2") Routes.READER else null })
        assertEquals("硬切不起动画（+0）", 2, started)
        assertEquals(
            "硬切的规格仍然是 Cut / 时长 0",
            spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0),
            cut.specOf("r2"),
        )
    }

    /**
     * 时刻线的**类别**（黑帧取数）：四条开书入口的进屏（含冷启动落地）都算 `enterReader`、
     * 阅读器 → 阅读器是 `swapReader`、返回是 `exitReader`、其余（文件夹 / 抽屉 / 书柜）是 `hierarchy`。
     * 类别错了，设备上那几个时刻的差值就会按错的形状去读（例如把换书的时刻算进「进阅读器」）。
     */
    @Test
    fun `过渡类别按前后路由分四种`() {
        assertEquals(
            "文件夹 → 阅读器（浏览页点书 / 抽屉「阅读器」/ 冷启动落地都是它）",
            NavTransitionTimeline.KIND_ENTER_READER,
            navTransitionKind(previousRoute = Routes.BROWSER, enteringRoute = Routes.READER),
        )
        assertEquals(
            "阅读器 → 阅读器",
            NavTransitionTimeline.KIND_SWAP_READER,
            navTransitionKind(previousRoute = Routes.READER, enteringRoute = Routes.READER),
        )
        assertEquals(
            "阅读器 → 浏览页",
            NavTransitionTimeline.KIND_EXIT_READER,
            navTransitionKind(previousRoute = Routes.READER, enteringRoute = Routes.BROWSER),
        )
        assertEquals(
            "文件夹之间 / 抽屉 / 书柜",
            NavTransitionTimeline.KIND_HIERARCHY,
            navTransitionKind(previousRoute = Routes.BROWSER, enteringRoute = Routes.BROWSER),
        )
        assertEquals(
            "起始目的地那一帧（没有前一个屏）",
            NavTransitionTimeline.KIND_ENTER_READER,
            navTransitionKind(previousRoute = null, enteringRoute = Routes.READER),
        )
    }

    // ---------- 规格只此一处（守护）----------

    /**
     * 导航过渡的**规格只在一处声明**：呈现方式 / 方向 / 角色 / 时长 / 曲线 / 幅度 / 哪一屏动——
     * 类型、纯函数、常量、空壳过渡、动画持有——必须只落在 `ui/nav/NavTransition.kt` 一个文件里。
     * 任何别的文件（含 AppNav）再声明一份，本用例即红。
     */
    @Test
    fun `过渡规格只声明在一处`() {
        var root = File(System.getProperty("user.dir")!!)
        while (root != null && !File(root, "settings.gradle.kts").isFile) root = root.parentFile
        assertNotNull("找不到仓库根（settings.gradle.kts）", root)
        val mainRoot = File(root, "app/src/main/java/com/cc3301/comicviewer")
        val target = File(mainRoot, "ui/nav/NavTransition.kt").canonicalFile
        val names = listOf(
            "NavTransitionStyle", "NavSlideDirection", "NavSlideRole", "NavSlideSpec",
            "NavSlideAnimations", "NavSlideFrame", "NavTransitions", "AnimationLauncher",
            "navTransitionStyle", "slideDirectionOf", "navSlideDirection", "navTransitionWindowMillis",
            "navSlideEasing", "navSlideSpecs", "navSlideOffsetX", "navSlideMoves",
            "navTransitionKind", "initialProgressOf",
            "holdEnter", "holdExit", "ENTER_READER_DURATION_MILLIS", "EXIT_READER_DURATION_MILLIS",
            "SLIDE_TRAVEL_PERCENT", "INTO_READER_EASING", "OUT_OF_READER_EASING",
        )
        val violations = buildList {
            for (name in names) {
                val declaration = Regex("(const val|val|fun|interface|class|object)\\s+$name\\b")
                val holders = mainRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { declaration.containsMatchIn(it.readText()) }
                    // 同名不同物：ReaderMenuTransitions 有自己的 `SLIDE_TRAVEL_PERCENT`（菜单面板行程，与本导航规格无关）
                    .filterNot { name == "SLIDE_TRAVEL_PERCENT" && it.name == "ReaderMenuTransitions.kt" }
                    .map { it.canonicalFile }
                    .toList()
                if (holders.toSet() != setOf(target)) add("$name → $holders")
            }
        }
        assertTrue(
            "导航过渡的规格必须只声明在 ui/nav/NavTransition.kt 一处；越界：$violations",
            violations.isEmpty(),
        )
    }
}
