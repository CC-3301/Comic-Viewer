package com.cc3301.comicviewer.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.cc3301.comicviewer.ui.Routes

/**
 * 导航过渡的**唯一规格出处**：呈现方式 / 方向 / 时长 / 曲线 / 幅度 / 「哪一屏动」的声明、纯函数与四支过渡的
 * 构造都只落在本文件（守护用例：`NavTransitionsTest` 的「过渡规格只声明在一处」）。
 *
 * **形态 = 静态背景滑动**：一次过渡里**只有一屏动**，动的永远是**画在最上面的那一屏**（进档 = 新屏滑入、
 * 出档 = 旧屏滑出）；另一屏原地静止当背景，只留一条 alpha 恒 1 的过渡把它留在屏上、位移恒 0。
 * 位移由 `NavHost` 自己的过渡给（[navEnterMotion] / [navExitMotion]）——没有按屏持有的进度动画，
 * 也没有每屏一层帧壳：每次导航的过渡对象由框架新建，初值在同一帧内定下。
 */

/**
 * 一次导航过渡的**呈现方式**：两档。
 *
 * - [Slide]：**静态背景滑动**——只有「文件夹 ↔ 阅读器」的进出走它；
 * - [Cut]：**硬切**（零过渡、瞬间换屏，也不留重叠窗口）——层级导航（文件夹之间 / 返回上级 / 抽屉入口 /
 *   书柜进柜）与换书（上一本 / 下一本 / 跨书条）都是它。
 *
 * 冷启动直接落进阅读器与普通进档同款滑动（[navTransitionStyle] 按前后路由判，进阅读器一律 [Slide]）。
 */
internal enum class NavTransitionStyle { Slide, Cut }

/**
 * 滑动这一档的**两个方向**（纯函数 [navSlideDirection] 的产物）：出是进的**逆过程**。
 *
 * - [IntoReader]：进阅读器（文件夹 → 阅读器）——**新屏**（阅读页）从**右**滑入，旧屏（浏览页）原地静止当背景；
 * - [OutOfReader]：出阅读器（阅读器 → 文件夹）——**旧屏**（阅读页）往**右**滑出，新屏（浏览页）原地静止当背景。
 */
internal enum class NavSlideDirection { IntoReader, OutOfReader }

/**
 * 一次导航的呈现方式判定（纯函数，由 `NavTransitionsTest` 锁定）。
 *
 * **只有「文件夹 ↔ 阅读器」有动画，其余一律硬切**；冷启动落进阅读器与普通进档同款
 * （它的旧屏是刚落盘的浏览层，允许它还在加载，形态不必也不应区分）：
 *
 * | 前后路由 | 结果 |
 * |---|---|
 * | 文件夹（/ 抽屉顶层 / 启动中转页）→ 阅读器 | [NavTransitionStyle.Slide]（进） |
 * | 阅读器 → 阅读器（换书） | [NavTransitionStyle.Cut] |
 * | 阅读器 → 文件夹（返回） | [NavTransitionStyle.Slide]（出，旧屏往右滑出） |
 * | 其余（文件夹之间 / 抽屉入口 / 书柜进柜） | [NavTransitionStyle.Cut] |
 *
 * 换书（阅读器 → 阅读器）必须先于「进阅读器」那一支判掉：它同样以阅读器为落点，但口径是硬切。
 */
internal fun navTransitionStyle(
    initialRoute: String?,
    targetRoute: String?,
): NavTransitionStyle = when {
    // 换书（replace：旧阅读器 entry 被移除、压入新 entry）：硬切
    initialRoute == Routes.READER && targetRoute == Routes.READER -> NavTransitionStyle.Cut
    // 进阅读器（四条开书入口与冷启动落地）：从右滑入
    targetRoute == Routes.READER -> NavTransitionStyle.Slide
    // 出阅读器（返回浏览层）：旧屏（阅读页）往右滑出
    initialRoute == Routes.READER -> NavTransitionStyle.Slide
    // 层级导航（文件夹之间 / 抽屉入口 / 书柜进柜）与启动落地：硬切
    else -> NavTransitionStyle.Cut
}

/**
 * 滑动这一档的方向（**非空**）：这一档只由「初始路由是不是阅读器」决定——旧屏是阅读器就是「出」
 * （换书先在 [navTransitionStyle] 判成硬切，到不了这里），否则是「进」。
 *
 * **为什么单独一个非空函数**：消费点不各自接可空方向再兜底，`Slide` 的语义一变就是难查的错数。
 */
private fun slideDirectionOf(initialRoute: String?): NavSlideDirection =
    if (initialRoute == Routes.READER) NavSlideDirection.OutOfReader else NavSlideDirection.IntoReader

/**
 * 滑动这一档的方向（纯函数，由 `NavTransitionsTest` 锁定）；[NavTransitionStyle.Cut] 没有方向（返回 null）。
 * 取值就是 [slideDirectionOf]（同一处来源，不可能与时长/曲线分叉）。
 */
internal fun navSlideDirection(
    initialRoute: String?,
    targetRoute: String?,
): NavSlideDirection? =
    if (navTransitionStyle(initialRoute, targetRoute) == NavTransitionStyle.Slide) {
        slideDirectionOf(initialRoute)
    } else {
        // 硬切没有过渡：没有方向
        null
    }

/**
 * 一次过渡的**类别**（纯函数，只服务黑帧取数的时刻线）：四条开书入口的进屏都是
 * [NavTransitionTimeline.KIND_ENTER_READER]（**冷启动落地也在内**），阅读器 → 阅读器是
 * [NavTransitionTimeline.KIND_SWAP_READER]，其余（文件夹之间 / 抽屉入口 / 书柜进柜）是
 * [NavTransitionTimeline.KIND_HIERARCHY]。
 *
 * 与 [navTransitionStyle] 是两件事：那个答「怎么动」，这个只答「日志里怎么认」。因此本函数的字面量
 * 由 `NavTransitionsTest` 钉住（改词即红），读日志的人不必猜。
 */
internal fun navTransitionKind(previousRoute: String?, enteringRoute: String?): String = when {
    enteringRoute == Routes.READER && previousRoute == Routes.READER -> NavTransitionTimeline.KIND_SWAP_READER
    enteringRoute == Routes.READER -> NavTransitionTimeline.KIND_ENTER_READER
    previousRoute == Routes.READER -> NavTransitionTimeline.KIND_EXIT_READER
    else -> NavTransitionTimeline.KIND_HIERARCHY
}

/** 时刻线里「没有前一个屏」的占位（起始目的地那一帧）：不写 null，读日志时按同一个词筛 */
private const val NO_ROUTE: String = "none"

/**
 * 时刻线 `begin` 行的 detail（纯拼接，唯一出处）：`from=` 上一屏路由 / `to=` 新屏路由，
 * 没有前一个屏时写 [NO_ROUTE]。调用点（`AppNav` 的过渡 lambda）不再各拼一份。
 */
internal fun navTransitionDetail(previousRoute: String?, enteringRoute: String?): String =
    "from=" + (previousRoute ?: NO_ROUTE) + " to=" + (enteringRoute ?: NO_ROUTE)

/**
 * 滑动这一档两向各自的**时长**（毫秒，纯函数）：进 [NavTransitions.ENTER_READER_DURATION_MILLIS]（300）、
 * 出 [NavTransitions.EXIT_READER_DURATION_MILLIS]（250，比进快一档：返回是回到已知界面，底下那屏已在位）。
 *
 * 量测窗口（[navTransitionWindowMillis]）与四支过渡都从它取数 ⇒ 两处不可能各写一个数。
 */
internal fun navSlideDurationMillis(direction: NavSlideDirection): Int = slidePlanOf(direction).durationMillis

/**
 * 一次过渡的时长（毫秒，纯函数，由 `NavTransitionsTest` 锁定）：
 *
 * - **进阅读器（文件夹 → 阅读器，含冷启动落地）300ms**；
 * - **出阅读器（阅读器 → 文件夹）250ms**；
 * - 硬切（层级导航 / 换书）**0**：不建过渡、也不留重叠窗口。
 *
 * 两个调用点必须得到**同一个数**：四支过渡的时长与量测窗口（`beginNavTransitionProbe`）——所以都调这一个
 * 函数，不再各自读一个常量。曲线与它同源：[navSlideEasing] 同读这一档。
 */
internal fun navTransitionWindowMillis(
    previousRoute: String?,
    enteringRoute: String?,
): Int = when (navTransitionStyle(previousRoute, enteringRoute)) {
    NavTransitionStyle.Cut -> 0
    // 滑动档的长短只由 [slideDirectionOf] 的真实取值决定（没有「没有方向」那一支可以兜底）：
    // 消费点因此不可能各拿一个数
    NavTransitionStyle.Slide -> navSlideDurationMillis(slideDirectionOf(previousRoute))
}

/**
 * 一次过渡的曲线（纯函数）：与 [navTransitionWindowMillis] 成对、同样由**一处**给出。
 * **两向共用一条** `CubicBezier(0.25, 0.5, 0.7, 1)`（起步快、末尾缓停）——进出是同一手势的镜像，曲线同族；
 * 取值与阅读菜单面板出现那条（`ui/ReaderMenuTransitions.kt` 的 `ENTER_EASING`，各自声明、不跨模块引用）相同。
 *
 * 不可达的那档直接 `error`，不静默给一个数。
 */
internal fun navSlideEasing(style: NavTransitionStyle): Easing = when (style) {
    NavTransitionStyle.Slide -> NavTransitions.SLIDE_EASING
    // 硬切不建过渡（[navSlideMotion] 给 null）：根本取不到曲线
    NavTransitionStyle.Cut -> error("硬切没有过渡曲线")
}

/**
 * 滑动这一档的方向 → 框架自己的滑动方向（纯函数，由 `NavTransitionsTest` 锁定）：
 *
 * - 进阅读器：新屏（阅读页）从**右**滑入 ⇒ `slideIntoContainer(towards = Left)`（起点 +整屏、终点 0）；
 * - 出阅读器：旧屏（阅读页）往**右**滑出 ⇒ `slideOutOfContainer(towards = Right)`（起点 0、终点 +整屏）。
 *
 * 两向取的是同一个 [AnimatedContentTransitionScope.SlideDirection]，含义由「哪一屏动」决定（见 [NavSlideScreen]）。
 */
internal fun navSlideTowards(direction: NavSlideDirection): AnimatedContentTransitionScope.SlideDirection =
    slidePlanOf(direction).towards

/**
 * 滑动这一档两向各一份的**全部**参数（纯函数）：动的是哪一屏 + 往哪一侧走 + 时长。曲线两向共用
 * （见 [navSlideEasing]），不在这里。**「方向 → 参数」只此一处 `when`**：[navSlideDurationMillis] /
 * [navSlideTowards] / [navSlideMotion] 都从它派生，三处不可能各写一份。
 */
private data class NavSlidePlan(
    val movingScreen: NavSlideScreen,
    val towards: AnimatedContentTransitionScope.SlideDirection,
    val durationMillis: Int,
)

private fun slidePlanOf(direction: NavSlideDirection): NavSlidePlan = when (direction) {
    // 谁画在最上面谁动（见 NavTransitions 的 KDoc）：进档新屏在最上面 ⇒ 新屏从右滑入；
    // 出档旧屏在最上面 ⇒ 旧屏往右滑出
    NavSlideDirection.IntoReader -> NavSlidePlan(
        movingScreen = NavSlideScreen.Entering,
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        durationMillis = NavTransitions.ENTER_READER_DURATION_MILLIS,
    )
    NavSlideDirection.OutOfReader -> NavSlidePlan(
        movingScreen = NavSlideScreen.Exiting,
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        durationMillis = NavTransitions.EXIT_READER_DURATION_MILLIS,
    )
}

/** 一次过渡里**动的那一屏**：进档是新屏（滑入）、出档是旧屏（滑出）；另一屏原地静止当背景 */
internal enum class NavSlideScreen { Entering, Exiting }

/**
 * 一次过渡要交给框架的**位移声明**（纯函数）：**动的是哪一屏**（[movingScreen]）+ 往哪一侧走 + 时长 + 曲线，
 * 四样一次给全。硬切返回 null ⇒ 两个过渡对象都是 `None`（不建过渡，也不留重叠窗口）。
 */
internal data class NavSlideMotion(
    val movingScreen: NavSlideScreen,
    val towards: AnimatedContentTransitionScope.SlideDirection,
    val durationMillis: Int,
    val easing: Easing,
)

/**
 * 见 [NavSlideMotion]（纯函数，由 `NavTransitionsTest` 锁定）：`Slide` 四样都给、硬切给 null。
 * 「Slide 却没有方向」不可达（见 [navSlideDirection]）⇒ 直接 `error`，不静默当成某一向。
 */
internal fun navSlideMotion(style: NavTransitionStyle, direction: NavSlideDirection?): NavSlideMotion? =
    when (style) {
        NavTransitionStyle.Cut -> null
        NavTransitionStyle.Slide -> {
            val plan = slidePlanOf(direction ?: error("Slide 档必有方向（见 navSlideDirection）"))
            NavSlideMotion(
                movingScreen = plan.movingScreen,
                towards = plan.towards,
                durationMillis = plan.durationMillis,
                easing = navSlideEasing(style),
            )
        }
    }

/**
 * 进入屏的过渡（新屏）：**必须在 `NavHost` 的过渡 lambda 里调**——`slideIntoContainer` /
 * `slideOutOfContainer` 只挂在 [AnimatedContentTransitionScope] 上（本版 Compose 没有等价的全顶层函数）。
 *
 * - 进档：**新屏动**——整屏行程滑入（`initialOffset` 默认满行程：起点完全出屏、终点不残留影像）；
 * - 出档：**新屏是被压在最下面的静止背景**——只留一条 alpha 恒 1 的过渡把它留在屏上（它若是 `None`，
 *   当帧就不在屏上，旧屏滑开后露出来的是窗口底色）；
 * - 硬切 → [EnterTransition.None]（不建过渡，也不留重叠窗口）。
 */
internal fun AnimatedContentTransitionScope<*>.navEnterMotion(
    style: NavTransitionStyle,
    direction: NavSlideDirection?,
): EnterTransition {
    val motion = navSlideMotion(style, direction) ?: return EnterTransition.None
    return when (motion.movingScreen) {
        NavSlideScreen.Entering -> slideIntoContainer(
            towards = motion.towards,
            animationSpec = tween(durationMillis = motion.durationMillis, easing = motion.easing),
        )
        NavSlideScreen.Exiting -> fadeIn(
            animationSpec = tween(durationMillis = motion.durationMillis, easing = motion.easing),
            initialAlpha = 1f,
        )
    }
}

/**
 * 退场屏的过渡（旧屏）：与 [navEnterMotion] 同一份声明、分工相反。
 *
 * - 出档：**旧屏动**——往右整屏滑出（它画在最上面，滑出全程真的看得见）；
 * - 进档：**旧屏是静止背景**——只留一条 alpha 恒 1 的过渡把它留在屏上（它是被新屏逐渐盖住的那一层）；
 * - 硬切 → [ExitTransition.None]。
 */
internal fun AnimatedContentTransitionScope<*>.navExitMotion(
    style: NavTransitionStyle,
    direction: NavSlideDirection?,
): ExitTransition {
    val motion = navSlideMotion(style, direction) ?: return ExitTransition.None
    return when (motion.movingScreen) {
        NavSlideScreen.Exiting -> slideOutOfContainer(
            towards = motion.towards,
            animationSpec = tween(durationMillis = motion.durationMillis, easing = motion.easing),
        )
        NavSlideScreen.Entering -> fadeOut(
            animationSpec = tween(durationMillis = motion.durationMillis, easing = motion.easing),
            targetAlpha = 1f,
        )
    }
}

/**
 * 全局页面过渡：**只有进出阅读器有动画（静态背景滑动），其余一律硬切**。
 *
 * - 一次过渡**只有一屏动**，动的永远是**画在最上面的那一屏**：进档 = 新屏（阅读页从**右**滑入）、
 *   出档 = 旧屏（阅读页往**右**滑出，露出下面的浏览页）；另一屏原地静止当背景、当帧挂正文
 *   （见 [navEnterMotion] / [navExitMotion]）；
 * - **谁在最上面 = 谁动**：`NavHost` 的 `transitionSpec` 给目标屏算 zIndex——进档 `+1`、出档 `−1`
 *   （`NavHost.kt` 的 `zIndices`）。因此进档新屏在最上面、出档旧屏在最上面；让被压住的那一屏去动，
 *   它动得再对也看不见（看出来的就是「瞬间换屏」）；
 * - 时长与曲线：进阅读器 **300ms**、出阅读器 **250ms**，两向共用 `CubicBezier(0.25, 0.5, 0.7, 1)`；
 *   冷启动落进阅读器与普通进档同款（无单独一档）。
 *   时长由 [navTransitionWindowMillis] 一处给出、曲线由 [navSlideEasing] 一处给出；
 * - **硬切**（[NavTransitionStyle.Cut]：层级导航 / 换书）：两个 `None`——不建过渡，**也不留重叠窗口**；
 * - **不做亮度交叉**（alpha 恒 1）、**不做错开**。
 *
 * **可测面**：呈现方式、方向、时长、曲线、滑动方向映射、动的是哪一屏与位移参数的取值——
 * 都是**纯函数 / 常量**，`NavTransitionsTest` 钉住。
 * **仍钉不住**的是接线那一半：① 8 个目的地是否真的都不再自带过渡（位移只由 [navEnterMotion] 给）、
 * ② 设备上的逐帧观感与帧时长（`NavTransitionProbe`）。
 *
 * 已知代价：
 * - 位移走框架的过渡（布局阶段每帧摆放两屏），不再走自绘层；
 * - **不做自动降级**：掉帧时不会自己退化成淡入；
 * - 进出阅读器的过渡存在期间，正在被盖住的那一屏**仍接收点击**（既有机制，选择保持现状）；
 *   层级导航与换书是硬切、没有窗口，这条代价在那两类上随之消失。
 */
internal object NavTransitions {

    /** 进阅读器（文件夹 → 阅读器，含冷启动落地）的过渡时长（毫秒）：**300ms** */
    const val ENTER_READER_DURATION_MILLIS: Int = 300

    /** 出阅读器（阅读器 → 文件夹）的过渡时长（毫秒）：**250ms**（比进快一档） */
    const val EXIT_READER_DURATION_MILLIS: Int = 250

    /** 滑动两档共用的曲线：`CubicBezier(0.25f, 0.5f, 0.7f, 1f)`——起步快、末尾缓停。 */
    val SLIDE_EASING: Easing = CubicBezierEasing(0.25f, 0.5f, 0.7f, 1f)
}
