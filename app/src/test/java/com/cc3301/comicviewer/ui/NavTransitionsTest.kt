package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局页面过渡的声明口径（票 #111；**整屏滑入划出、方向统一、两屏同一份时长与曲线**——
 * 见 `AppNav.kt` 的 `NavTransitions` / [NavSlideFrame] / [navSlideOffsetX] 的 KDoc）。
 *
 * 钉住四件事：
 * 1. **呈现方式**（[navTransitionStyle]，纯函数）：一律 [NavTransitionStyle.Slide]，只有「进入阅读器且入口显式给
 *    [ReaderEnter.FADE]」（冷启动落地）与「旧屏是启动中转页」两条是 [NavTransitionStyle.Fade]；
 *    **返回不再镜像**（r11 §1 作废了「压栈从右 / 弹栈从左」那套矩阵）；
 * 2. **规格常量**：[NavTransitions.DEFAULT_DURATION_MILLIS]（300ms）、[NavTransitions.ENTER_READER_DURATION_MILLIS]
 *    （500ms）、两屏位移 [NavTransitions.SLIDE_TRAVEL_PERCENT]（100% = 整屏），以及两条曲线各自的取值；
 * 3. **每屏的位移/亮度算式**（自驱之后才有的一层）：[navSlideSpecs] 的规格表（呈现方式 / 角色 / 时长）、
 *    [navSlideOffsetX] 的符号与整屏幅度、[navSlideAlpha] 的「只有冷启动才改亮度」，以及
 *    [NavSlideAnimations] 的「同一屏一个 `Animatable`、第一次观察不产生规格」；
 * 4. **规格是可观察状态**（r11 §0 的根因）：`NavSlideFrame` 在组合期读 [NavSlideAnimations.specOf]，而**旧屏**
 *    那时已在组合里 —— 规格是普通字段时它变了不会让旧屏重组，旧屏会一直拿着自己「新屏」那份规格
 *    （进度停在 1、位移停在 0）⇒ 真机现象「只有新屏在动、旧屏杵着不动，过一会儿被直接撤掉」。
 *    见 `旧屏那侧的规格读取是可观察状态 否则它不会重组`。
 *
 * **驱动方式是自驱**（第 9 轮 C6）：`NavHost` 的四支过渡退化成零视觉空壳
 * （[NavTransitions.holdEnter] / [NavTransitions.holdExit]，alpha 恒 1，只撑重叠窗口），位移与淡入由每屏
 * 自己的 [NavSlideFrame] 驱动，**启动点在 `observe`（组合期、`NavHost` 内容之前）**，不再等新屏首次组合
 * （r11 §6，见 `规格一更新就把两屏点起来 不必等新屏组合`）。
 * **仍咬不住的是接线那一半**（本文件不为它编造断言）：
 * ① 四支 lambda 是否真的返回空壳（`fadeIn(initialAlpha = 1f)` 的参数**不可观测**，反射白名单为空）；
 * ② [NavSlideAnimations.observe] 是否真的在 `NavHost` 内容**之前**被喂了栈；
 * ③ 8 个目的地是否**每一个**都包了 [NavSlideFrame]（漏一个就是「那一屏不滑」）；
 * ④ `graphicsLayer` 的实际像素轨迹、手感与「点了就马上滑」。前三者要跑 Compose 组合才观测得到，
 * 本仓无 Compose UI 测试基建（见 SPEC 的 Testing Decisions）⇒ 守护留在真机清单里。
 *
 * 真机目视项（交付后由维护者判）：旧屏确实在滑出 · 新旧屏同时开始同时结束 · 新屏从右进、旧屏往左出
 * （进 / 返回 / 换书 都是这一套）· 匀速 · 进阅读器 0.5s、其余 0.3s · 点了就马上滑、滑之前没有黑帧 ·
 * 从阅读器返回时系统栏不再等 0.5s 后突然蹦出来 · 冷启动落进阅读器仍不滑 · 系统「移除动画」时不播过渡。
 */
class NavTransitionsTest {

    private val transitions = NavTransitions()

    // ---------- 呈现方式（纯函数）----------

    /** 进入阅读器：浏览页点书 / 抽屉「阅读器」/ 换书 —— 都是滑入；只有入口显式给 FADE 才只淡入 */
    @Test
    fun `进入阅读器只按入口给的那一个例外`() {
        assertEquals(
            "浏览页点书进阅读器：滑入",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.BROWSER, Routes.READER, ReaderEnter.SLIDE),
        )
        assertEquals(
            "抽屉「阅读器」入口的初始屏是首页/书柜/设置，且参数取默认值（null）：同样滑入",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.SETTINGS, Routes.READER, null),
        )
        assertEquals(
            "换书（阅读器 → 阅读器）：滑入，不再按「上一本 / 下一本」分方向",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.READER, Routes.READER, null),
        )
        assertEquals(
            "返回（阅读器 → 浏览页）：**同一套滑动**，不再镜像（r11 §1）",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.READER, Routes.BROWSER, null),
        )
        assertEquals(
            "层级导航（浏览页 → 浏览页）：滑入",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.BROWSER, Routes.BROWSER, null),
        )
    }

    /**
     * 只淡入的两条：① 入口显式给 [ReaderEnter.FADE]（冷启动落地，生产主路径——真实落地顺序由
     * `StartupReaderTransitionTest` 钉住）；② 兜底：旧屏是启动中转页（[Routes.STARTUP]）。
     */
    @Test
    fun `冷启动落地只淡入`() {
        assertEquals(
            "入口显式给 FADE：旧屏是刚落盘的浏览层时不能判成滑入",
            NavTransitionStyle.Fade,
            navTransitionStyle(Routes.BROWSER, Routes.READER, ReaderEnter.FADE),
        )
        assertEquals(
            "兜底：旧屏是启动中转页（参数取默认值）",
            NavTransitionStyle.Fade,
            navTransitionStyle(Routes.STARTUP, Routes.READER, null),
        )
    }

    // ---------- 规格常量 ----------

    @Test
    fun `规格常量就是维护者拍板的那两档时长与整屏`() {
        assertEquals(
            "r11 §2：文件夹之间 / 返回 / 换书 / 冷启动淡入都是 300ms",
            300,
            NavTransitions.DEFAULT_DURATION_MILLIS,
        )
        assertEquals(
            "r11 §2：进阅读器（旧屏不是阅读器）500ms",
            500,
            NavTransitions.ENTER_READER_DURATION_MILLIS,
        )
        assertEquals(
            "票面 r7 口径：两屏都走整屏（100%）——新屏 +100% → 0，旧屏 0 → −100%（完全出屏）",
            100,
            NavTransitions.SLIDE_TRAVEL_PERCENT,
        )
        assertTrue(
            "重叠窗口必须 ≥ 最长的一次滑动，否则退场屏会在滑动没结束时被撤掉（中段露出底）",
            NavTransitions.OVERLAP_DURATION_MILLIS >= NavTransitions.ENTER_READER_DURATION_MILLIS,
        )
    }

    /** 时长判定（纯函数，由每屏的动画与量测窗口共用）：进阅读器 500ms，其余 300ms */
    @Test
    fun `过渡时长 进阅读器 500 其余 300`() {
        assertEquals(
            "进阅读器（旧屏不是阅读器）：500ms",
            500,
            navTransitionWindowMillis(Routes.BROWSER, Routes.READER, ReaderEnter.SLIDE),
        )
        assertEquals(
            "换书（旧屏是阅读器）：300ms",
            300,
            navTransitionWindowMillis(Routes.READER, Routes.READER, null),
        )
        assertEquals(
            "返回阅读器 → 浏览页：300ms",
            300,
            navTransitionWindowMillis(Routes.READER, Routes.BROWSER, null),
        )
        assertEquals(
            "文件夹之间：300ms",
            300,
            navTransitionWindowMillis(Routes.BROWSER, Routes.BROWSER, null),
        )
        assertEquals(
            "冷启动只淡入那一支：300ms（不是「进阅读器滑入」那一档）",
            300,
            navTransitionWindowMillis(Routes.BROWSER, Routes.READER, ReaderEnter.FADE),
        )
    }

    /**
     * 两条曲线各自的**取值与角色**：匀速那条（[NavTransitions.TRANSITION_EASING]）是**两屏位移与冷启动淡入淡出
     * 共用的唯一一条**（r11 §2 口径 `LinearEasing`）；加速那条（[NavTransitions.EXIT_EASING]）只剩阅读菜单
     * 面板的消失支在用（`ui/ReaderMenuTransitions.kt` 直接读它，不另起别名）。
     *
     * 本用例只钉**两条曲线本身**（数值对数值，改曲线就红）；它不管谁 read 了哪一条（那层读不到，见类 KDoc）。
     */
    @Test
    fun `两条曲线各自仍是那一条`() {
        assertSame(
            "r11 §2：两屏位移 + 冷启动淡入淡出都读这条匀速曲线（等价于 CubicBezier(0, 0, 1, 1)）",
            LinearEasing,
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

    // ---------- 每屏的位移 / 亮度算式 ----------

    /**
     * 位移的**符号与幅度**（数值对数值）：`progress` 的语义是「新屏 0 → 1、旧屏 1 → 0」。
     * 方向**统一**（r11 §1）：新屏从右（+整屏）→ 0、旧屏 0 → 左（−整屏），弹栈与压栈同一套。
     * 把 [navSlideOffsetX] 的符号取反、或把 `remaining` 写成 `progress`，这里即红。
     */
    @Test
    fun `每屏的位移：新屏从右进 旧屏往左出 弹栈也一样`() {
        val width = 1000f
        val travel = width * NavTransitions.SLIDE_TRAVEL_PERCENT / 100f

        // 滑入：新屏从**右**（+整屏）→ 0；旧屏 0 → **左**（−整屏，完全出屏、不残留）
        assertEquals(travel, navSlideOffsetX(NavTransitionStyle.Slide, NavSlideRole.Entering, 0f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(NavTransitionStyle.Slide, NavSlideRole.Entering, 1f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(NavTransitionStyle.Slide, NavSlideRole.Exiting, 1f, width), 0.001f)
        assertEquals(-travel, navSlideOffsetX(NavTransitionStyle.Slide, NavSlideRole.Exiting, 0f, width), 0.001f)

        // 中点：两屏各走一半（不是错开、也不是「旧屏只移 30%」）
        assertEquals(travel / 2f, navSlideOffsetX(NavTransitionStyle.Slide, NavSlideRole.Entering, 0.5f, width), 0.001f)
        assertEquals(-travel / 2f, navSlideOffsetX(NavTransitionStyle.Slide, NavSlideRole.Exiting, 0.5f, width), 0.001f)

        // 冷启动落地：**不滑**（整条过渡里恒 0）
        listOf(0f, 0.5f, 1f).forEach { p ->
            assertEquals(0f, navSlideOffsetX(NavTransitionStyle.Fade, NavSlideRole.Entering, p, width), 0.001f)
            assertEquals(0f, navSlideOffsetX(NavTransitionStyle.Fade, NavSlideRole.Exiting, p, width), 0.001f)
        }
    }

    /** 亮度：**只有冷启动那一支改 alpha**（新屏淡入 0→1、旧屏淡出 1→0）；滑入划出那一支恒 1（不做亮度交叉） */
    @Test
    fun `只有冷启动那一支改亮度 滑入划出恒 1`() {
        assertEquals(0f, navSlideAlpha(NavTransitionStyle.Fade, NavSlideRole.Entering, 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionStyle.Fade, NavSlideRole.Entering, 1f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionStyle.Fade, NavSlideRole.Exiting, 1f), 0.001f)
        assertEquals(0f, navSlideAlpha(NavTransitionStyle.Fade, NavSlideRole.Exiting, 0f), 0.001f)

        assertEquals(1f, navSlideAlpha(NavTransitionStyle.Slide, NavSlideRole.Entering, 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionStyle.Slide, NavSlideRole.Exiting, 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(NavTransitionStyle.Slide, NavSlideRole.Exiting, 1f), 0.001f)
    }

    // ---------- 每屏的规格（由栈变化算）----------

    /**
     * 栈变化 → 每屏的规格：压栈 / 弹栈 / 冷启动 / 换书（含 replace 时旧屏是谁）与**时长**。
     * 这张表同时是「**旧屏必须拿到 [NavSlideRole.Exiting] 规格**」的判据（r11 §0：拿到 Entering 就不播滑出）。
     */
    @Test
    fun `栈变化决定每屏的呈现方式角色与时长`() {
        fun specs(previous: List<String>, current: List<String>, routes: Map<String, String>, hints: Map<String, String> = emptyMap()) =
            navSlideSpecs(previous, current, { routes[it] }, { hints[it] })

        val slide300 = NavTransitionStyle.Slide
        val folder = mapOf("a" to Routes.BROWSER, "b" to Routes.BROWSER)

        // 压栈（层级导航进子文件夹 / 抽屉入口进入）：新屏从右，上一帧的栈顶是被盖住的旧屏
        assertEquals(
            mapOf(
                "b" to NavSlideSpec(slide300, NavSlideRole.Entering, 300),
                "a" to NavSlideSpec(slide300, NavSlideRole.Exiting, 300),
            ),
            specs(listOf("a"), listOf("a", "b"), folder),
        )

        // 弹栈（返回上一级）：**同一套滑动**（不再镜像），旧屏是**这一帧消失的那一项**
        assertEquals(
            mapOf(
                "a" to NavSlideSpec(slide300, NavSlideRole.Entering, 300),
                "b" to NavSlideSpec(slide300, NavSlideRole.Exiting, 300),
            ),
            specs(listOf("a", "b"), listOf("a"), folder),
        )

        // 进阅读器（旧屏是浏览层）：两屏都是滑入，但时长是 500ms 那一档
        assertEquals(
            mapOf(
                "r" to NavSlideSpec(slide300, NavSlideRole.Entering, 500),
                "b" to NavSlideSpec(slide300, NavSlideRole.Exiting, 500),
            ),
            specs(
                listOf("b"),
                listOf("b", "r"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
            ),
        )

        // 冷启动落地：入口显式给 FADE ⇒ 两屏都只淡不滑，时长回到默认档
        assertEquals(
            mapOf(
                "r" to NavSlideSpec(NavTransitionStyle.Fade, NavSlideRole.Entering, 300),
                "b" to NavSlideSpec(NavTransitionStyle.Fade, NavSlideRole.Exiting, 300),
            ),
            specs(
                listOf("b"),
                listOf("b", "r"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
                mapOf("r" to ReaderEnter.FADE),
            ),
        )

        // 换书：旧屏是**旧阅读器 entry**（replace 把旧 entry 换成新的），时长是 300ms（不是 500ms）
        assertEquals(
            mapOf(
                "r2" to NavSlideSpec(slide300, NavSlideRole.Entering, 300),
                "r1" to NavSlideSpec(slide300, NavSlideRole.Exiting, 300),
            ),
            specs(listOf("r1"), listOf("r2"), mapOf("r1" to Routes.READER, "r2" to Routes.READER)),
        )
    }

    // ---------- 每屏一个 Animatable ----------

    /**
     * 起始目的地**不播过渡**（第一次观察不产生规格）、同一屏的进度动画**只建一次**（重组不重启动画）。
     * 拿掉「第一次观察不产生规格」，AppNav 启动那一屏会自己从屏外滑进来（真机可见的回归）。
     */
    @Test
    fun `起始目的地不播过渡 同一屏的进度动画只建一次`() {
        val slide = NavSlideAnimations(AnimationLauncher {})
        val routes = mapOf("start" to Routes.STARTUP, "b" to Routes.BROWSER)
        val routeOf: (String) -> String? = { routes[it] }

        slide.observe(listOf("start"), routeOf, { null })
        assertNull("起始目的地本来就不播过渡", slide.specOf("start"))

        slide.observe(listOf("start", "b"), routeOf, { null })
        assertEquals(
            NavSlideSpec(NavTransitionStyle.Slide, NavSlideRole.Entering, 300),
            slide.specOf("b"),
        )
        assertEquals(
            NavSlideSpec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 300),
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

    /**
     * r11 §0 的根因判据：**读规格必须是快照状态的读**。
     *
     * `NavSlideFrame` 在组合期读 `specs`；**旧屏**那时已经在组合里了，只有「读的是可观察状态」这一条
     * 才会让 Compose 在规格变化时失效它的组合作用域、让它重组并拿到 [NavSlideRole.Exiting] 规格。
     * 规格退回普通字段时本用例是**红的**（读观察者收不到任何状态读）——真机现象也随之回来：旧屏杵着不动。
     */
    @Test
    fun `旧屏那侧的规格读取是可观察状态 否则它不会重组`() {
        val slide = NavSlideAnimations(AnimationLauncher {})
        val routes = mapOf("a" to Routes.BROWSER, "b" to Routes.BROWSER)
        val routeOf: (String) -> String? = { routes[it] }
        slide.observe(listOf("a"), routeOf, { null })
        slide.observe(listOf("a", "b"), routeOf, { null })
        assertEquals(
            "前置条件：旧屏在这一帧拿到了 Exiting 规格",
            NavSlideSpec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 300),
            slide.specOf("a"),
        )

        val readStates = mutableListOf<Any>()
        Snapshot.observe(readObserver = { readStates += it }) { slide.specOf("a") }

        assertTrue(
            "specOf 必须留下快照读依赖（普通字段时收不到任何读 ⇒ 旧屏不重组 ⇒ 不播滑出）",
            readStates.isNotEmpty(),
        )
    }

    /**
     * §6 的判据：**规格一更新就把两屏的动画点起来**——与规格同一步（组合期、`NavHost` 内容之前），不再等
     * 新屏首次组合 + 布局之后的 `LaunchedEffect` ⇒ 「新屏那一帧有多重」影响不到启动；而且两屏的启动在**同一批
     * 调用**里发出（旧屏的滑出不再比新屏的滑入晚一帧）。
     *
     * 本用例在**没有跑任何组合**的纯单测里数启动次数，因此能咬住三件事：起始目的地不起（第一次观察无规格 ⇒
     * 透传分支不变）、一次过渡两屏各起一次、栈没变不重复起（也不重播）。回到「在 `NavSlideFrame` 里
     * `LaunchedEffect` 起」的旧形态或两处一起驱动时，本用例看不出区别——那一半由代码结构（本类 KDoc 的
     * 「唯一驱动点」）与真机守着。
     */
    @Test
    fun `规格一更新就把两屏点起来 不必等新屏组合`() {
        val started = mutableListOf<Unit>()
        val slide = NavSlideAnimations(AnimationLauncher { started += Unit })
        val routes = mapOf("start" to Routes.STARTUP, "b" to Routes.BROWSER)
        val routeOf: (String) -> String? = { routes[it] }

        slide.observe(listOf("start"), routeOf, { null })
        assertEquals("起始目的地没有规格 ⇒ 不起动画（透传分支不变）", emptyList<Unit>(), started)

        slide.observe(listOf("start", "b"), routeOf, { null })
        assertEquals("一帧里两屏 ⇒ 两次启动，且与任何组合无关（本用例没跑组合）", 2, started.size)

        val armed = slide.progressOf("b", NavSlideRole.Entering)
        assertEquals("新屏首帧读到的进度就是它被点起时的初值（还在屏外）", 0f, armed.value, 0.001f)

        slide.observe(listOf("start", "b"), routeOf, { null })
        assertEquals("栈没变：不重复起（也不重播）", 2, started.size)
    }
}
