package com.cc3301.comicviewer.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.snapshots.Snapshot
import com.cc3301.comicviewer.core.view.NavTransitionTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局页面过渡的声明口径（票 #111 **r13**：**只有进出阅读器有动画**——进 350ms 从右进 / 出 250ms 镜像，
 * 层级导航与换书**硬切**，冷启动落进阅读器仍纯淡入不滑；见 `AppNav.kt` 的 `NavTransitions` /
 * [NavSlideFrame] / [navSlideOffsetX] 的 KDoc）。
 *
 * 钉住四件事：
 * 1. **呈现方式**（[navTransitionStyle]，纯函数）：文件夹 ↔ 阅读器两向是 [NavTransitionStyle.Slide]；
 *    进阅读器且入口显式给 [ReaderEnter.FADE]（冷启动落地）或旧屏是启动中转页是 [NavTransitionStyle.Fade]；
 *    **层级导航与换书是 [NavTransitionStyle.Cut]**（r13 §1 推翻了 r11 的「全导航横向滑入」）；
 * 2. **规格常量**：[NavTransitions.ENTER_READER_DURATION_MILLIS]（350ms）、
 *    [NavTransitions.EXIT_READER_DURATION_MILLIS]（250ms）、[NavTransitions.FADE_DURATION_MILLIS]（300ms）、
 *    两屏位移 [NavTransitions.SLIDE_TRAVEL_PERCENT]（100% = 整屏），以及三条曲线各自的取值；
 * 3. **每屏的位移/亮度算式**（自驱之后才有的一层）：[navSlideSpecs] 的规格表（呈现方式 / 角色 / 时长 / 方向）、
 *    [navSlideOffsetX] 的符号与整屏幅度（**进从右、出镜像**）、[navSlideAlpha] 的「只有冷启动才改亮度」，以及
 *    [NavSlideAnimations] 的「同一屏一个 `Animatable`、第一次观察不产生规格、硬切不建动画」；
 * 4. **规格是可观察状态**（r11 §0 的根因，r13 继续成立）：`NavSlideFrame` 在组合期读 [NavSlideAnimations.specOf]，
 *    而**旧屏**那时已在组合里 —— 规格是普通字段时它变了不会让旧屏重组，旧屏会一直拿着自己「新屏」那份规格
 *    （进度停在 1、位移停在 0）⇒ 真机现象「只有新屏在动、旧屏杵着不动，过一会儿被直接撤掉」。
 *    见 `旧屏那侧的规格读取是可观察状态 否则它不会重组`。
 *
 * **驱动方式是自驱**（第 9 轮 C6）：`NavHost` 的四支过渡退化成零视觉空壳
 * （[NavTransitions.holdEnter] / [NavTransitions.holdExit]，alpha 恒 1，只撑重叠窗口；硬切那一档是 `None`），
 * 位移与淡入由每屏自己的 [NavSlideFrame] 驱动，**启动点在 `observe`（组合期、`NavHost` 内容之前）**，不再等
 * 新屏首次组合（r11 §6，见 `规格一更新就把两屏点起来 不必等新屏组合 硬切不点`）。
 * **仍咬不住的是接线那一半**（本文件不为它编造断言）：
 * ① 四支 lambda 是否真的返回空壳（`fadeIn(initialAlpha = 1f)` 的参数**不可观测**，反射白名单为空）；
 * ② [NavSlideAnimations.observe] 是否真的在 `NavHost` 内容**之前**被喂了栈；
 * ③ 8 个目的地是否**每一个**都包了 [NavSlideFrame]（漏一个就是「那一屏不滑」或「那一屏不硬切」）；
 * ④ `graphicsLayer` 的实际像素轨迹、手感与真正的**逐帧观感与帧时长**（`AppNav.kt` 的 `ENTERING_SHELL_FRAMES`
 *    的**帧数本身已被本文件的常量用例钉住**，这里剩下的是「那几帧到底长什么样、多长」）。
 * 前三者要跑 Compose 组合才观测得到，本仓无 Compose UI 测试基建（见 SPEC 的 Testing Decisions）
 * ⇒ 守护留在真机清单里。
 *
 * 真机目视项（交付后由维护者判）：进阅读器点了就看见在滑、滑之前没有整屏黑帧 · 进 350ms「起步快、末尾缓停」·
 * 出阅读器**镜像**（浏览页从左回、阅读页往右走）、250ms、不再先闪一下 · 层级导航与换书**瞬间换屏、没有动画**·
 * 冷启动落进阅读器仍只淡入不滑 · 系统「移除动画」时不播过渡。
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
     * **只有「文件夹 ↔ 阅读器」有动画，其余一律硬切**（r13 §1）。口径变更登记：r11 的「所有导航都是同一套
     * 滑动」（含文件夹之间、返回、换书）在本用例里被逐条改成硬切。
     */
    @Test
    fun `只有文件夹与阅读器之间才滑动 其余硬切`() {
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
            "返回（阅读器 → 浏览页）：**镜像滑动**（不是硬切，也不是 r11 的同向滑动）",
            NavTransitionStyle.Slide,
            navTransitionStyle(Routes.READER, Routes.BROWSER, null),
        )
        assertEquals(
            "换书（阅读器 → 阅读器）：硬切（r13 §1 口径变更；r11 是滑动）",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.READER, Routes.READER, null),
        )
        assertEquals(
            "层级导航（浏览页 → 浏览页）：硬切（r13 §1 口径变更；r11 是滑动）",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.BROWSER, Routes.BROWSER, null),
        )
        assertEquals(
            "抽屉顶层入口（设置 → 首页这类同级顶层）：硬切",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.SETTINGS, Routes.HOME, null),
        )
        assertEquals(
            "启动落地（中转页 → 首页）：硬切（没有旧屏可看，也不需要过渡）",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.STARTUP, Routes.HOME, null),
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
        assertEquals(
            "换书即便带了 FADE 也仍是硬切：冷启动落地永远不是「阅读器 → 阅读器」",
            NavTransitionStyle.Cut,
            navTransitionStyle(Routes.READER, Routes.READER, ReaderEnter.FADE),
        )
    }

    /** 滑动档的镜像方向（纯函数）：落点是阅读器就是「进」，旧屏是阅读器（落点不是）就是「出」 */
    @Test
    fun `滑动档的方向按前后路由分进与出`() {
        assertEquals(
            NavSlideDirection.IntoReader,
            navSlideDirection(Routes.BROWSER, Routes.READER, ReaderEnter.SLIDE),
        )
        assertEquals(
            "抽屉「阅读器」入口（初始屏是设置）同样是「进阅读器」",
            NavSlideDirection.IntoReader,
            navSlideDirection(Routes.SETTINGS, Routes.READER, null),
        )
        assertEquals(
            NavSlideDirection.OutOfReader,
            navSlideDirection(Routes.READER, Routes.BROWSER, null),
        )
        assertNull("换书是硬切：没有方向", navSlideDirection(Routes.READER, Routes.READER, null))
        assertNull(
            "层级导航是硬切：没有方向",
            navSlideDirection(Routes.BROWSER, Routes.BROWSER, null),
        )
        assertNull(
            "冷启动淡入：没有方向（不滑）",
            navSlideDirection(Routes.BROWSER, Routes.READER, ReaderEnter.FADE),
        )
    }

    // ---------- 规格常量 ----------

    @Test
    fun `规格常量就是维护者拍板的那三档时长与整屏`() {
        assertEquals("r13 §1：进阅读器 350ms", 350, NavTransitions.ENTER_READER_DURATION_MILLIS)
        assertEquals("r13 §1：出阅读器 250ms（镜像，比进快一点）", 250, NavTransitions.EXIT_READER_DURATION_MILLIS)
        assertEquals("r13 §1：冷启动落进阅读器 300ms（保持现口径）", 300, NavTransitions.FADE_DURATION_MILLIS)
        assertEquals(
            "r13 §1：两屏都走整屏（100%）——两屏都是整个行程、完全出屏（终点不残留半透明影像）",
            100,
            NavTransitions.SLIDE_TRAVEL_PERCENT,
        )
        assertEquals(
            "「新屏首帧只挂壳、正文晚几帧」的旋钮（r13 的空档修复，见 AppNav.kt 的 ENTERING_SHELL_FRAMES）：" +
                "两帧——只影响「看见在动的时刻」，不动行程与时长",
            2,
            ENTERING_SHELL_FRAMES,
        )
    }

    /**
     * 时长判定（纯函数，由每屏的动画与量测窗口共用）：进 350 / 出 250 / 冷启动 300 / 硬切 0。
     * 口径变更登记：原来这里是「进阅读器 500、其余 300」。
     */
    @Test
    fun `过渡时长 进 350 出 250 冷启动 300 硬切 0`() {
        assertEquals(
            "进阅读器（文件夹 → 阅读器）：350ms",
            350,
            navTransitionWindowMillis(Routes.BROWSER, Routes.READER, ReaderEnter.SLIDE),
        )
        assertEquals(
            "出阅读器（阅读器 → 浏览页）：250ms",
            250,
            navTransitionWindowMillis(Routes.READER, Routes.BROWSER, null),
        )
        assertEquals(
            "换书：0（硬切没有过渡窗口）",
            0,
            navTransitionWindowMillis(Routes.READER, Routes.READER, null),
        )
        assertEquals(
            "文件夹之间：0（硬切没有过渡窗口）",
            0,
            navTransitionWindowMillis(Routes.BROWSER, Routes.BROWSER, null),
        )
        assertEquals(
            "冷启动只淡入那一支：300ms（与固定不变的 r13 定稿一致）",
            300,
            navTransitionWindowMillis(Routes.BROWSER, Routes.READER, ReaderEnter.FADE),
        )
    }

    /**
     * 三条曲线各自的**取值与角色**：[navSlideEasing] 按**档**（style + 方向）取（r13 §1），
     * 冷启动淡入那一档仍是匀速（r13 定稿：这一档不动）。
     *
     * 本用例只钉**曲线本身**（数值对数值，改曲线就红）；它不管谁 read 了哪一条（那层读不到，见类 KDoc）。
     * E2 批把签名从「只吃方向」改成「吃档 + 方向」：匀速那条按**档**判，而不是「方向缺失时的兑底」。
     * 原来还多钉一条导航侧零引用的 `EXIT_EASING`（唯一调用方是阅读菜单的消失支）；票 #129 r8 归属 A 案
     * 把那条曲线归回菜单（`ReaderMenuTransitions.EXIT_EASING`），本文件那条断言一并删除。
     */
    @Test
    fun `三条曲线各自仍是那一条`() {
        assertEquals(
            "进阅读器：起步约 1.6 倍匀速、末尾几乎停下",
            CubicBezierEasing(0f, 0f, 0.6f, 1f),
            navSlideEasing(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertEquals(
            "出阅读器：同族但略不同的一条（出只有 250ms，不能靠进那条撑手感）",
            CubicBezierEasing(0.25f, 0.5f, 0.7f, 1f),
            navSlideEasing(NavTransitionStyle.Slide, NavSlideDirection.OutOfReader),
        )
        assertSame(
            "冷启动那一档：匀速（按档判，不看方向是否为 null）",
            LinearEasing,
            navSlideEasing(NavTransitionStyle.Fade, null),
        )
    }

    /**
     * 规范轴 P2-1 的判据：**「Slide 却没有方向」这格不可达，而且必须是响的**——以前三个消费点各自用
     * `else` 兜底、兜底答案还互不相同（350ms 进档 / 250ms 出档的空壳 / 匀速曲线）⇒ 语义一变就是难查的错数。
     * 现在取值只从 [navSlideDirection] 那一处来，不可达的分支直接 `error`（仓库先例：`ui/CoverThumb.kt`）。
     */
    @Test
    fun `不可达的 null 方向是响的 不再静默给一个数`() {
        val easing = runCatching { navSlideEasing(NavTransitionStyle.Slide, null) }.exceptionOrNull()
        assertTrue("Slide 却没有方向：曲线这一处必须直接报错（以前静默给匀速）", easing is IllegalStateException)

        val offset = runCatching {
            navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350), 0f, 1000f)
        }.exceptionOrNull()
        assertTrue("Slide 却没有方向：位移这一处必须直接报错（以前静默当成「进」那一向）", offset is IllegalStateException)

        val cut = runCatching { navSlideEasing(NavTransitionStyle.Cut, null) }.exceptionOrNull()
        assertTrue("硬切不建动画：取曲线这一格也必须直接报错", cut is IllegalStateException)
    }

    /**
     * E2 批：**壳先行只给滑动档的新屏**——冷启动交叉淡变那一档（维护者 2026-09-27 拍板「保持原样、不进
     * 改动面」）与旧屏都是当帧挂正文。抽成纯函数就是为了本用例能钉住它（`NavSlideFrame` 里的接线仍不可观测）。
     */
    @Test
    fun `壳先行只给滑动档的新屏`() {
        assertTrue(
            "进入阅读器的新屏",
            shellFirst(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350, NavSlideDirection.IntoReader)),
        )
        assertTrue(
            "返回浏览页的新屏（滑动档的另一向，同属进出阅读器）",
            shellFirst(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 250, NavSlideDirection.OutOfReader)),
        )
        assertFalse(
            "旧屏不能壳先行：会把正在退场的那一屏内容抽空",
            shellFirst(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, NavSlideDirection.IntoReader)),
        )
        assertFalse(
            "冷启动淡变那一档保持原样（当帧挂正文）",
            shellFirst(spec(NavTransitionStyle.Fade, NavSlideRole.Entering, 300)),
        )
        assertFalse(
            "硬切不包节点",
            shellFirst(spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0)),
        )
    }

    /**
     * 空壳过渡（只撑重叠窗口）：**滑动与淡入两档不是 `None`**（`None` 的话两屏不会重叠，位移就变成
     * 「一屏先走完另一屏才出现」）；**硬切那一档必须是 `None`**（r13 §2：不建动画、也不留重叠窗口，
     * 退场屏当帧不在）。起点 / 终点 alpha 都是 1 这一点读不到（反射白名单为空），由代码审查 + 真机清单守护。
     */
    @Test
    fun `滑动与淡入的空壳不是零时长 硬切那一档是 None`() {
        assertNotSame(
            EnterTransition.None,
            transitions.holdEnter(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertNotSame(
            EnterTransition.None,
            transitions.holdEnter(NavTransitionStyle.Slide, NavSlideDirection.OutOfReader),
        )
        assertNotSame(EnterTransition.None, transitions.holdEnter(NavTransitionStyle.Fade, null))
        assertNotSame(
            ExitTransition.None,
            transitions.holdExit(NavTransitionStyle.Slide, NavSlideDirection.IntoReader),
        )
        assertNotSame(ExitTransition.None, transitions.holdExit(NavTransitionStyle.Fade, null))

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

    // ---------- 每屏的位移 / 亮度算式 ----------

    /**
     * 位移的**符号与幅度**（数值对数值）：`progress` 的语义是「新屏 0 → 1、旧屏 1 → 0」。
     * **进阅读器**新屏从右（+整屏）→ 0、旧屏 0 → 左（−整屏）；**出阅读器**是进阅读器的**逆过程**（全部取反）。
     * 把 [navSlideOffsetX] 的符号取反、或把 `remaining` 写成 `progress`，这里即红。
     * 口径变更登记：r11 的「返回也同向（不镜像）」在本用例里被改成镜像。
     */
    @Test
    fun `每屏的位移：进从右出从左 出阅读器镜像`() {
        val width = 1000f
        val travel = width * NavTransitions.SLIDE_TRAVEL_PERCENT / 100f
        val into = NavSlideDirection.IntoReader
        val out = NavSlideDirection.OutOfReader

        // 进阅读器：新屏从**右**（+整屏）→ 0；旧屏 0 → **左**（−整屏，完全出屏、不残留）
        assertEquals(travel, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350, into), 0f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350, into), 1f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, into), 1f, width), 0.001f)
        assertEquals(-travel, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, into), 0f, width), 0.001f)

        // 出阅读器：**镜像**——新屏从**左**（−整屏）→ 0；旧屏 0 → **右**（+整屏）
        assertEquals(-travel, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 250, out), 0f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 250, out), 1f, width), 0.001f)
        assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 250, out), 1f, width), 0.001f)
        assertEquals(travel, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 250, out), 0f, width), 0.001f)

        // 中点：两屏各走一半（不是错开、也不是「旧屏只移 30%」）
        assertEquals(travel / 2f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350, into), 0.5f, width), 0.001f)
        assertEquals(-travel / 2f, navSlideOffsetX(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, into), 0.5f, width), 0.001f)

        // 冷启动落地与硬切：**都不滑**（整条过渡里恒 0）
        listOf(0f, 0.5f, 1f).forEach { p ->
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Fade, NavSlideRole.Entering, 300), p, width), 0.001f)
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Fade, NavSlideRole.Exiting, 300), p, width), 0.001f)
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0), p, width), 0.001f)
            assertEquals(0f, navSlideOffsetX(spec(NavTransitionStyle.Cut, NavSlideRole.Exiting, 0), p, width), 0.001f)
        }
    }

    /** 亮度：**只有冷启动那一支改 alpha**（新屏淡入 0→1、旧屏淡出 1→0）；滑入划出与硬切恒 1（不做亮度交叉） */
    @Test
    fun `只有冷启动那一支改亮度 滑入划出与硬切恒 1`() {
        assertEquals(0f, navSlideAlpha(spec(NavTransitionStyle.Fade, NavSlideRole.Entering, 300), 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(spec(NavTransitionStyle.Fade, NavSlideRole.Entering, 300), 1f), 0.001f)
        assertEquals(1f, navSlideAlpha(spec(NavTransitionStyle.Fade, NavSlideRole.Exiting, 300), 1f), 0.001f)
        assertEquals(0f, navSlideAlpha(spec(NavTransitionStyle.Fade, NavSlideRole.Exiting, 300), 0f), 0.001f)

        assertEquals(1f, navSlideAlpha(spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350, NavSlideDirection.IntoReader), 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, NavSlideDirection.IntoReader), 0f), 0.001f)
        assertEquals(1f, navSlideAlpha(spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 250, NavSlideDirection.OutOfReader), 1f), 0.001f)
        assertEquals(1f, navSlideAlpha(spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0), 0f), 0.001f)
    }

    // ---------- 每屏的规格（由栈变化算）----------

    /**
     * 栈变化 → 每屏的规格：进阅读器 / 出阅读器 / 冷启动 / 硬切（层级导航与换书）各自的呈现方式、方向、角色与**时长**。
     * 这张表同时是「**旧屏必须拿到 [NavSlideRole.Exiting] 规格**」的判据（r11 §0：拿到 Entering 就不播滑出）。
     * 口径变更登记：r11 的「压栈/弹栈/换书都是 Slide 300ms、进阅读器 500ms」在这张表里整表重写。
     */
    @Test
    fun `栈变化决定每屏的呈现方式方向角色与时长`() {
        fun specs(previous: List<String>, current: List<String>, routes: Map<String, String>, hints: Map<String, String> = emptyMap()) =
            navSlideSpecs(previous, current, { routes[it] }, { hints[it] })

        val into = NavSlideDirection.IntoReader
        val out = NavSlideDirection.OutOfReader
        val folder = mapOf("a" to Routes.BROWSER, "b" to Routes.BROWSER)

        // 进阅读器（浏览页点书）：新屏（阅读页）从右滑入 350ms，旧屏（浏览页）同长同向地往左出
        assertEquals(
            mapOf(
                "r" to spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350, into),
                "b" to spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, into),
            ),
            specs(
                listOf("b"),
                listOf("b", "r"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
            ),
        )

        // 出阅读器（返回）：**镜像** 250ms——新屏（浏览页）从左进，旧屏（阅读页）往右出
        assertEquals(
            mapOf(
                "b" to spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 250, out),
                "r" to spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 250, out),
            ),
            specs(
                listOf("b", "r"),
                listOf("b"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
            ),
        )

        // 冷启动落地：入口显式给 FADE ⇒ 两屏都只淡不滑，300ms，没有方向
        assertEquals(
            mapOf(
                "r" to spec(NavTransitionStyle.Fade, NavSlideRole.Entering, 300),
                "b" to spec(NavTransitionStyle.Fade, NavSlideRole.Exiting, 300),
            ),
            specs(
                listOf("b"),
                listOf("b", "r"),
                mapOf("b" to Routes.BROWSER, "r" to Routes.READER),
                mapOf("r" to ReaderEnter.FADE),
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
     * 拿掉「第一次观察不产生规格」，AppNav 启动那一屏会自己从屏外滑进来（真机可见的回归）。
     */
    @Test
    fun `起始目的地不播过渡 同一屏的进度动画只建一次`() {
        val slide = NavSlideAnimations(AnimationLauncher {})
        val routes = mapOf("b" to Routes.BROWSER, "r" to Routes.READER)
        val routeOf: (String) -> String? = { routes[it] }

        slide.observe(listOf("b"), routeOf, { null })
        assertNull("起始目的地本来就不播过渡", slide.specOf("b"))

        slide.observe(listOf("b", "r"), routeOf, { null })
        assertEquals(
            spec(NavTransitionStyle.Slide, NavSlideRole.Entering, 350, NavSlideDirection.IntoReader),
            slide.specOf("r"),
        )
        assertEquals(
            spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, NavSlideDirection.IntoReader),
            slide.specOf("b"),
        )

        // 栈没变：不重记（重组不重记，也就不会重播）
        val recorded = slide.specOf("r")
        slide.observe(listOf("b", "r"), routeOf, { null })
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
     * r11 §0 的根因判据：**读规格必须是快照状态的读**。
     *
     * `NavSlideFrame` 在组合期读 `specs`；**旧屏**那时已经在组合里了，只有「读的是可观察状态」这一条
     * 才会让 Compose 在规格变化时失效它的组合作用域、让它重组并拿到 [NavSlideRole.Exiting] 规格。
     * 规格退回普通字段时本用例是**红的**（读观察者收不到任何状态读）——真机现象也随之回来：旧屏杵着不动。
     */
    @Test
    fun `旧屏那侧的规格读取是可观察状态 否则它不会重组`() {
        val slide = NavSlideAnimations(AnimationLauncher {})
        val routes = mapOf("b" to Routes.BROWSER, "r" to Routes.READER)
        val routeOf: (String) -> String? = { routes[it] }
        slide.observe(listOf("b"), routeOf, { null })
        slide.observe(listOf("b", "r"), routeOf, { null })
        assertEquals(
            "前置条件：旧屏在这一帧拿到了 Exiting 规格",
            spec(NavTransitionStyle.Slide, NavSlideRole.Exiting, 350, NavSlideDirection.IntoReader),
            slide.specOf("b"),
        )

        val readStates = mutableListOf<Any>()
        Snapshot.observe(readObserver = { readStates += it }) { slide.specOf("b") }

        assertTrue(
            "specOf 必须留下快照读依赖（普通字段时收不到任何读 ⇒ 旧屏不重组 ⇒ 不播滑出）",
            readStates.isNotEmpty(),
        )
    }

    /**
     * §6 的判据：**规格一更新就把两屏的动画点起来**——与规格同一步（组合期、`NavHost` 内容之前），不再等
     * 新屏首次组合 + 布局之后的 `LaunchedEffect` ⇒ 「新屏那一帧有多重」影响不到启动；而且两屏的启动在**同一批
     * 调用**里发出（旧屏的滑出不再比新屏的滑入晚一帧）。
     * **硬切不点动画**（r13 §2：不建动画、也不留窗口）——加在同一个用例里，因为两者都是「谁被点起来了」。
     *
     * 本用例在**没有跑任何组合**的纯单测里数启动次数，因此能咬住三件事：起始目的地不起（第一次观察无规格 ⇒
     * 透传分支不变）、一次过渡两屏各起一次、栈没变不重复起（也不重播）。回到「在 `NavSlideFrame` 里
     * `LaunchedEffect` 起」的旧形态或两处一起驱动时，本用例看不出区别——那一半由代码结构（本类 KDoc 的
     * 「唯一驱动点」）与真机守着。
     */
    @Test
    fun `规格一更新就把两屏点起来 不必等新屏组合 硬切不点`() {
        var started = 0
        val slide = NavSlideAnimations(AnimationLauncher { started++ })
        val routes = mapOf("b" to Routes.BROWSER, "r" to Routes.READER)
        val routeOf: (String) -> String? = { routes[it] }

        slide.observe(listOf("b"), routeOf, { null })
        assertEquals("起始目的地没有规格 ⇒ 不起动画（透传分支不变）", 0, started)

        slide.observe(listOf("b", "r"), routeOf, { null })
        assertEquals("一帧里两屏 ⇒ 两次启动，且与任何组合无关（本用例没跑组合）", 2, started)

        val armed = slide.progressOf("r", NavSlideRole.Entering)
        assertEquals("新屏首帧读到的进度就是它被点起时的初值（还在屏外）", 0f, armed.value, 0.001f)

        slide.observe(listOf("b", "r"), routeOf, { null })
        assertEquals("栈没变：不重复起（也不重播）", 2, started)

        // 硬切（换书）：规格照旧算出来（NavSlideFrame 据此透传），但**不建动画**、也不点任何缓冲
        slide.observe(listOf("b", "r"), routeOf, { null })
        slide.observe(listOf("b", "r2"), { id -> if (id == "r2") Routes.READER else routeOf(id) }, { null })
        assertEquals("硬切不起动画（+0）", 2, started)
        assertEquals(
            "硬切的规格仍然是 Cut / 时长 0（NavSlideFrame 据此透传）",
            spec(NavTransitionStyle.Cut, NavSlideRole.Entering, 0),
            slide.specOf("r2"),
        )
    }

    /**
     * 时刻线的**类别**（票 #111 r13 的黑帧取数）：四条开书入口的进屏（含冷启动落地）都算 `enterReader`、
     * 阅读器 → 阅读器是 `swapReader`、返回是 `exitReader`、其余（文件夹 / 抽屉 / 书柜）是 `hierarchy`。
     * 类别错了，真机上那五个时刻的差值就会按错的形状去读（例如把换书的时刻算进「进阅读器」）。
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
}
