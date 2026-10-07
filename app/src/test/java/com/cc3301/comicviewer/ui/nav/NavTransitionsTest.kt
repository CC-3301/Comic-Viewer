package com.cc3301.comicviewer.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import com.cc3301.comicviewer.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全局页面过渡的声明口径：**只有进出阅读器有动画，且是静态背景滑动**——一次过渡里只有一屏动，
 * 动的那一屏是**画在最上面的那一屏**（进 = 新屏阅读页从右滑入 350ms；出 = 旧屏阅读页往右滑出 300ms），
 * 另一屏原地静止当背景；层级导航与换书**硬切**；冷启动落进阅读器与普通进档同款滑入。
 *
 * 钉住四件事：
 * 1. **呈现方式**（[navTransitionStyle]，纯函数）：文件夹 ↔ 阅读器两向是 [NavTransitionStyle.Slide]；
 *    **层级导航与换书是 [NavTransitionStyle.Cut]**；
 * 2. **规格常量与映射**：[NavTransitions.ENTER_READER_DURATION_MILLIS]（350ms）、
 *    [NavTransitions.EXIT_READER_DURATION_MILLIS]（300ms）、两条曲线各自的取值、
 *    以及「方向 → 框架的滑动方向」（[navSlideTowards]：进从右、出往右）；
 * 3. **位移声明**（[navSlideMotion]）：动的是哪一屏（进 = 新屏、出 = 旧屏）+ 方向 + 时长 + 曲线；
 *    硬切那一档给 null（两个过渡对象都是 `None`）；
 * 4. **不可达的分支要响**：Slide 没有方向直接 `error`，硬切取曲线也直接 `error`。
 *
 * **仍咬不住的是接线那一半**（本文件不为它编造断言）：① 8 个目的地是否真的都不再自带过渡（位移只由
 * [NavTransitions.enterMotion] 给）；② `slideIntoContainer` 的实际像素轨迹、手感与**逐帧观感与帧时长**
 * （设备取数）。① 要跑 Compose 组合才观测得到，本仓无 Compose UI 测试基建（见 SPEC 的 Testing Decisions）
 * ⇒ 守护留在设备清单里。
 *
 * 设备目视项（交付后人工过一遍）：进阅读器点了就看见在滑、滑到一半时另一半是静止的浏览页 ·
 * 进 350ms「起步快、末尾缓停」· 出阅读器（阅读页往右滑出、露出底下静止的浏览页）、300ms「起步慢、末尾冲出」·
 * 进出两个方向都不再出现「静止那一屏跟着系统栏 inset 变化跳动」·
 * 层级导航与换书**瞬间换屏、没有动画** · 冷启动落进阅读器也是同款滑入 ·
 * 没就绪时屏上是黑底（根背景恒黑、不出转圈）、首图就绪直接呈现 · 系统「移除动画」时不播过渡。
 */
class NavTransitionsTest {

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

    // ---------- 规格常量与映射 ----------

    @Test
    fun `规格常量就是那两档时长`() {
        assertEquals("进阅读器 350ms", 350, NavTransitions.ENTER_READER_DURATION_MILLIS)
        assertEquals("出阅读器 300ms", 300, NavTransitions.EXIT_READER_DURATION_MILLIS)
    }

    /**
     * 时长判定（纯函数，由四支过渡与量测窗口共用）：进 350 / 出 300 / 硬切 0。
     */
    @Test
    fun `过渡时长 进 350 出 300 硬切 0`() {
        assertEquals(
            "进阅读器（文件夹 → 阅读器）：350ms",
            350,
            navTransitionWindowMillis(Routes.BROWSER, Routes.READER),
        )
        assertEquals(
            "出阅读器（阅读器 → 浏览页）：300ms",
            300,
            navTransitionWindowMillis(Routes.READER, Routes.BROWSER),
        )
        assertEquals(
            "冷启动直进阅读器：也是 350ms（并入进档，不再有单独一档）",
            350,
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
     * 方向 → 框架的滑动方向（纯函数，**枚举对枚举**）：进阅读器的新屏从**右**进
     * （`SlideDirection.Left`：起点 +整屏、终点 0）、出阅读器的新屏从**左**进
     * （`SlideDirection.Right`：起点 −整屏、终点 0）。
     *
     * 判别力：把两向写反（进给 `Right`）时设备上是「新屏从另一侧滑入」，本用例即红。
     */
    @Test
    fun `滑动方向映射 进从右 出从左`() {
        assertEquals(
            "进阅读器：从右进（towards = Left）",
            AnimatedContentTransitionScope.SlideDirection.Left,
            navSlideTowards(NavSlideDirection.IntoReader),
        )
        assertEquals(
            "出阅读器：从左进（towards = Right，镜像）",
            AnimatedContentTransitionScope.SlideDirection.Right,
            navSlideTowards(NavSlideDirection.OutOfReader),
        )
    }

    /**
     * 两条曲线的**取值与角色**：[navSlideEasing] 按档（style + 方向）取。本用例只钉**曲线本身**
     * （数值对数值，改曲线就红）。进是**减速型**（进入屏幕时落位）、出是**加速型**（离开屏幕时让位）。
     */
    @Test
    fun `两条曲线各自仍是那一条`() {
        assertEquals(
            "进档：减速型——起步快、末尾缓停（CubicBezier(0.35, 0.7, 0.7, 1)）",
            CubicBezierEasing(0.35f, 0.7f, 0.7f, 1f),
            navSlideEasing(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertEquals(
            "出档：加速型——起步慢、末尾冲出屏幕（CubicBezier(0.4, 0, 1, 1)）",
            CubicBezierEasing(0.4f, 0f, 1f, 1f),
            navSlideEasing(NavTransitionStyle.Slide, NavSlideDirection.OutOfReader),
        )
    }

    /**
     * 本用例的判据：**「Slide 却没有方向」「硬切取曲线」这几格不可达，而且必须是响的**——
     * 不可达的分支直接 `error`，不静默给一个数（仓库先例：`ui/CoverThumb.kt`）。
     */
    @Test
    fun `不可达的 null 方向是响的 不再静默给一个数`() {
        val easing = runCatching { navSlideEasing(NavTransitionStyle.Slide, null) }.exceptionOrNull()
        assertTrue("Slide 却没有方向：曲线这一处必须直接报错", easing is IllegalStateException)

        val motion = runCatching { navSlideMotion(NavTransitionStyle.Slide, null) }.exceptionOrNull()
        assertTrue("Slide 却没有方向：位移声明这一处必须直接报错", motion is IllegalStateException)

        val cut = runCatching { navSlideEasing(NavTransitionStyle.Cut, null) }.exceptionOrNull()
        assertTrue("硬切不建过渡：取曲线这一格也必须直接报错", cut is IllegalStateException)
    }

    /**
     * 位移声明（纯函数，**数值对数值**）：滑动两向各给出「动的是哪一屏 + 往哪一侧走 + 时长 + 曲线」四样；
     * **硬切给 null** ⇒ 两个过渡对象都是 `None`（不建过渡、也不留窗口）。
     *
     * 判别力：`movingScreen` 写反（出档让新屏动）时设备上是「出档看不见滑动」——那正是这一条要钉住的回归；
     * `Cut` 那一格若给了声明，硬切就会凭空多出一个过渡窗口。
     */
    @Test
    fun `位移声明 进动新屏 出动旧屏 硬切没有`() {
        assertEquals(
            "进阅读器：新屏动、从右滑入、350ms、减速型",
            NavSlideMotion(
                movingScreen = NavSlideScreen.Entering,
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                durationMillis = 350,
                easing = CubicBezierEasing(0.35f, 0.7f, 0.7f, 1f),
            ),
            navSlideMotion(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertEquals(
            "出阅读器：**旧屏**动、往右滑出、300ms、加速型",
            NavSlideMotion(
                movingScreen = NavSlideScreen.Exiting,
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                durationMillis = 300,
                easing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
            ),
            navSlideMotion(NavTransitionStyle.Slide, NavSlideDirection.OutOfReader),
        )
        assertNull(
            "硬切：没有位移声明（两个过渡对象都是 None，不留窗口）",
            navSlideMotion(NavTransitionStyle.Cut, null),
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

    /** `begin` 行的 detail（纯拼接）：`from=` / `to=`，没有前一个屏时写 `none`（不写 null） */
    @Test
    fun `时刻线 detail 缺前一个屏时写 none`() {
        assertEquals(
            "from=browser/{connId}?container={container}&name={name} to=reader/{bookId}",
            navTransitionDetail(Routes.BROWSER, Routes.READER),
        )
        assertEquals(
            "起始目的地那一帧没有前一个屏",
            "from=none to=reader/{bookId}",
            navTransitionDetail(null, Routes.READER),
        )
    }

    // ---------- 规格只此一处（守护）----------

    /**
     * 导航过渡的**规格只在一处声明**：呈现方式 / 方向 / 时长 / 曲线 / 滑动方向映射——
     * 类型、纯函数、常量、四支过渡——必须只落在 `ui/nav/NavTransition.kt` 一个文件里。
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
            "NavTransitionStyle", "NavSlideDirection", "NavSlideScreen", "NavSlideMotion", "NavTransitions",
            "navTransitionStyle", "slideDirectionOf", "navSlideDirection", "navTransitionWindowMillis",
            "navSlideDurationMillis", "navSlideEasing", "navSlideTowards", "navSlideMotion",
            "navTransitionDetail", "navTransitionKind", "navEnterMotion", "navExitMotion",
            "ENTER_READER_DURATION_MILLIS", "EXIT_READER_DURATION_MILLIS",
            "INTO_READER_EASING", "OUT_OF_READER_EASING",
        )
        val violations = buildList {
            for (name in names) {
                // 允许扩展函数（`fun <接收者>.名字`）：接收者里可能带 `<>`、`*`、`?`、逗号
                val declaration = Regex(
                    "(const val|val|fun|interface|class|object)\\s+([A-Za-z0-9_<>,.*? ]+\\.)?$name\\b",
                )
                val holders = mainRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { declaration.containsMatchIn(it.readText()) }
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
