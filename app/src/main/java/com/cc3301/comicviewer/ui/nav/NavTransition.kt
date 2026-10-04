package com.cc3301.comicviewer.ui.nav

import android.animation.ValueAnimator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.cc3301.comicviewer.ui.ReaderEnter
import com.cc3301.comicviewer.ui.Routes

/**
 * 导航过渡的**唯一规格出处**：时长 / 曲线 / 方向 / 幅度 / 角色的声明、纯函数、空壳过渡、动画持有、帧壳
 * 都只落在本文件（守护用例：`NavTransitionsTest` 的「过渡规格只声明在一处」）。
 */

/**
 * 一次导航过渡的**呈现方式**（原 `NavTransitionDirection`）：三档。
 *
 * - [Slide]：横向整屏滑入划出（两屏同幅、同时长、同曲线），**只有「文件夹 ↔ 阅读器」的进出**走它；
 *   「进」与「出」的镜像方向见 [NavSlideDirection]；
 * - [Fade]：**只淡入不滑**——冷启动直接落进阅读器（新屏不滑；退场的是刚落盘的浏览层，兜底路径下是启动中转页）；
 * - [Cut]：**硬切**（零过渡、瞬间换屏，也不留重叠窗口）——层级导航（文件夹之间 / 返回上级 / 抽屉入口 /
 *   书柜进柜）与换书（上一本 / 下一本 / 跨书条）都是它。
 *
 * 沿革：曾把方向矩阵作废、所有导航统一成同一套滑动，后来推翻它（只有进出阅读器有动画）。
 */
internal enum class NavTransitionStyle { Slide, Fade, Cut }

/**
 * 滑动这一档的**两个方向**（纯函数 [navSlideDirection] 的产物）：出是进的**逆过程**。
 *
 * - [IntoReader]：进阅读器（文件夹 → 阅读器）——新屏（阅读页）从**右**滑入，旧屏（文件夹）往**左**滑出；
 * - [OutOfReader]：出阅读器（阅读器 → 文件夹）——**镜像**：新屏（文件夹）从**左**滑入，旧屏（阅读页）往**右**滑出。
 */
internal enum class NavSlideDirection { IntoReader, OutOfReader }

/**
 * 一次导航的呈现方式判定（纯函数，由 `NavTransitionsTest` 锁定）。
 *
 * **只有「文件夹 ↔ 阅读器」有动画，其余一律硬切**：
 *
 * | 前后路由 | 结果 |
 * |---|---|
 * | 文件夹 → 阅读器（浏览页点书 / 抽屉「阅读器」） | [NavTransitionStyle.Slide]（进） |
 * | 阅读器 → 阅读器（换书） | [NavTransitionStyle.Cut] |
 * | 阅读器 → 文件夹（返回） | [NavTransitionStyle.Slide]（出，镜像） |
 * | 其余（文件夹之间 / 抽屉入口 / 书柜进柜） | [NavTransitionStyle.Cut] |
 * | 进阅读器且入口显式给 [ReaderEnter.FADE] / 旧屏是启动中转页（[Routes.STARTUP]） | [NavTransitionStyle.Fade] |
 *
 * 换书（阅读器 → 阅读器）必须先于「进阅读器」那一支判掉：它同样以阅读器为落点，但口径是硬切。
 */
internal fun navTransitionStyle(
    initialRoute: String?,
    targetRoute: String?,
    enterHint: String?,
): NavTransitionStyle = when {
    // 换书（replace：旧阅读器 entry 被移除、压入新 entry）：硬切
    initialRoute == Routes.READER && targetRoute == Routes.READER -> NavTransitionStyle.Cut
    // 进阅读器里唯一的例外：入口显式给的只淡不滑（冷启动落地），或旧屏是启动中转页（兜底路径）
    targetRoute == Routes.READER && (enterHint == ReaderEnter.FADE || initialRoute == Routes.STARTUP) ->
        NavTransitionStyle.Fade
    // 进阅读器（其余三条入口）：从右滑入
    targetRoute == Routes.READER -> NavTransitionStyle.Slide
    // 出阅读器（返回浏览层）：镜像滑动
    initialRoute == Routes.READER -> NavTransitionStyle.Slide
    // 层级导航（文件夹之间 / 抽屉入口 / 书柜进柜）与启动落地：硬切
    else -> NavTransitionStyle.Cut
}

/**
 * 滑动档的方向（**非空**）：这一档只由「初始路由是不是阅读器」决定——旧屏是阅读器就是「出」（换书先被
 * [navTransitionStyle] 判成硬切，到不了这里），否则是「进」。
 *
 * **为什么单独一个非空函数**：原来每处消费点各自接一个可空的 [navSlideDirection] 并用
 * `else` 兜底，三个兜底答案还互不相同（350ms 进档 / 250ms 出档的空壳 / 匀速曲线）——`Slide` 的语义一变
 * 就是难查的错数。现在「Slide 却没有方向」在类型上不存在（调用点只在 Slide 那一支里用本函数）。
 */
private fun slideDirectionOf(initialRoute: String?): NavSlideDirection =
    if (initialRoute == Routes.READER) NavSlideDirection.OutOfReader else NavSlideDirection.IntoReader

/**
 * 滑动这一档的方向（纯函数，由 `NavTransitionsTest` 锁定）；非 [NavTransitionStyle.Slide]
 * 的两档没有方向（返回 null）。取值就是 [slideDirectionOf]（同一处来源，不可能与时长/曲线分叉）。
 *
 * 只按**前后路由**判：落点是阅读器就是「进」，旧屏是阅读器（而落点不是）就是「出」，因此换书那一类先在
 * [navTransitionStyle] 里判成硬切，到不了这里。
 */
internal fun navSlideDirection(
    initialRoute: String?,
    targetRoute: String?,
    enterHint: String?,
): NavSlideDirection? =
    if (navTransitionStyle(initialRoute, targetRoute, enterHint) == NavTransitionStyle.Slide) {
        slideDirectionOf(initialRoute)
    } else {
        // 冷启动淡变不滑、硬切没有过渡：两档都没有方向
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
 * 一次过渡的时长（毫秒，纯函数，由 `NavTransitionsTest` 锁定）：
 *
 * - **进阅读器（文件夹 → 阅读器）500ms**；
 * - **出阅读器（阅读器 → 文件夹）350ms**（镜像）；
 * - 冷启动落进阅读器 **300ms**（平芜淡变那一档）；
 * - 硬切（层级导航 / 换书）**0**：不建动画、也不留重叠窗口。
 *
 * 两个调用点必须得到**同一个数**：每屏自己的动画（[navSlideSpecs] 算进规格）与量测窗口
 * （[beginNavTransitionProbe]）——所以都调这一个函数，不再各自读一个常量。
 * 曲线跟它同源：[navSlideEasing] 同读这一档（同一套 style + 方向），量测窗与两个空壳也读同一个数。
 */
internal fun navTransitionWindowMillis(
    previousRoute: String?,
    enteringRoute: String?,
    enterHint: String?,
): Int = when (navTransitionStyle(previousRoute, enteringRoute, enterHint)) {
    NavTransitionStyle.Cut -> 0
    NavTransitionStyle.Fade -> NavTransitions.FADE_DURATION_MILLIS
    // 滑动档的长短只由 [slideDirectionOf] 的真实取值决定（没有「没有方向」那一支可以兜底）：
    // 三处消费点因此不可能各拿一个数
    NavTransitionStyle.Slide -> when (slideDirectionOf(previousRoute)) {
        NavSlideDirection.IntoReader -> NavTransitions.ENTER_READER_DURATION_MILLIS
        NavSlideDirection.OutOfReader -> NavTransitions.EXIT_READER_DURATION_MILLIS
    }
}

/**
 * 一次过渡的曲线（纯函数）：与 [navTransitionWindowMillis] 成对、同样由**一处**给出。
 *
 * 进用 `CubicBezier(0, 0, 0.6, 1)`、出用 `CubicBezier(0.25, 0.5, 0.7, 1)`（都是「起步快、末尾缓缓停下」），
 * 冷启动淡入仍用匀速。
 *
 * 按**档**读（而不是按「方向是不是 null」）：匀速那条是冷启动淡入那一档的，不是「方向缺失时的兑底」；
 * 两个不可达的分支直接 `error`，不静默给一个数（以前三处的兜底答案互不相同）。
 */
internal fun navSlideEasing(style: NavTransitionStyle, direction: NavSlideDirection?): Easing = when (style) {
    NavTransitionStyle.Fade -> NavTransitions.FADE_EASING
    NavTransitionStyle.Slide -> when (direction) {
        NavSlideDirection.IntoReader -> NavTransitions.INTO_READER_EASING
        NavSlideDirection.OutOfReader -> NavTransitions.OUT_OF_READER_EASING
        // 「Slide 却没有方向」不可达（见 [navSlideDirection]）
        null -> error("Slide 档必有方向（见 navSlideDirection）")
    }
    // 硬切不建动画（[NavSlideAnimations.observe] 不为它起动画）：根本取不到曲线
    NavTransitionStyle.Cut -> error("硬切没有过渡曲线")
}

/** 一屏在一次过渡里的角色：新屏 / 旧屏 */
internal enum class NavSlideRole { Entering, Exiting }

/**
 * 一次过渡里「哪一屏怎么走」（[navSlideSpecs] 的产物）：呈现方式 + 角色 + **时长** + 方向。
 *
 * 时长放进规格：一帧里只由 [navSlideSpecs] 算一次，两屏各读自己的规格 ⇒
 * 两屏**必然共用同一个数**（曲线同理：从 [direction] 经 [navSlideEasing] 取，两屏同一个方向）。
 * [direction] 只有 [NavTransitionStyle.Slide] 有（连续两档没有方向，为 null）。
 */
internal data class NavSlideSpec(
    val style: NavTransitionStyle,
    val role: NavSlideRole,
    val durationMillis: Int,
    val direction: NavSlideDirection?,
)

/**
 * 这一帧的栈变化 ⇒ 每屏的过渡规格（纯函数，由 `NavTransitionsTest` 钉住）。
 *
 * - **新屏 = 栈顶那一项**（`currentIds.last()`）：它是不是这一帧新出现的，决定旧屏是谁；
 * - **旧屏**：压栈时是**上一帧的栈顶**（被盖住的那一屏），弹栈时是**这一帧消失的那一项**；
 *   换书（replace：`popUpTo(READER){inclusive}` + navigate）因此正好是**旧阅读器 entry**；
 * - 呈现方式 / 方向 / 时长：同一套判据（[navTransitionStyle] / [navSlideDirection] / [navTransitionWindowMillis]）——
 *   只有文件夹 ↔ 阅读器两向是 [NavTransitionStyle.Slide]，冷启动落进阅读器是 [NavTransitionStyle.Fade]，
 *   其余（层级导航 / 换书）是 [NavTransitionStyle.Cut]（时长 0）。
 */
internal fun navSlideSpecs(
    previousIds: List<String>,
    currentIds: List<String>,
    routeOf: (String) -> String?,
    enterHintOf: (String) -> String?,
): Map<String, NavSlideSpec> {
    if (currentIds.isEmpty()) return emptyMap()
    val enteringId = currentIds.last()
    val enteringNew = enteringId !in previousIds
    val previousRoute = previousIds.lastOrNull()?.let(routeOf)
    val enteringRoute = routeOf(enteringId)
    val enterHint = enterHintOf(enteringId)
    val entering = NavSlideSpec(
        style = navTransitionStyle(previousRoute, enteringRoute, enterHint),
        role = NavSlideRole.Entering,
        durationMillis = navTransitionWindowMillis(previousRoute, enteringRoute, enterHint),
        direction = navSlideDirection(previousRoute, enteringRoute, enterHint),
    )
    val exitingId = if (enteringNew) {
        previousIds.lastOrNull()
    } else {
        previousIds.lastOrNull { it !in currentIds }
    }
    return buildMap {
        put(enteringId, entering)
        if (exitingId != null && exitingId != enteringId) {
            put(exitingId, entering.copy(role = NavSlideRole.Exiting))
        }
    }
}

/**
 * 一屏的进度初值（纯函数）：新屏 0（还在屏外）、旧屏 1（还没开始动）——与 [NavSlideAnimations.progressOf] 同口径。
 * 黑帧取数的「动画第一次真的动」据它判定（`progress.value` 一旦离开这个初值就是首帧动画）。
 */
internal fun initialProgressOf(role: NavSlideRole): Float =
    if (role == NavSlideRole.Entering) 0f else 1f

/**
 * 一屏的横向位移（px，纯函数）：[progress] 的语义**统一为「新屏 0 → 1、旧屏 1 → 0」**——
 * 于是同一屏从「新屏」变成「旧屏」时，[Animatable] 从当前值续接即可，不必重头播。
 *
 * 整屏行程（[NavTransitions.SLIDE_TRAVEL_PERCENT] 由调用方折进 [travelPx]）；方向取**规格里的** [NavSlideSpec.direction]
 * （镜像）：
 *
 * - 进阅读器：新屏 `+travelPx` → 0（从右进）、旧屏 0 → `-travelPx`（往左出）；
 * - 出阅读器：**全部取反**——新屏 `-travelPx` → 0（从左进）、旧屏 0 → `+travelPx`（往右出）。
 *
 * [NavTransitionStyle.Fade]（冷启动落地）与 [NavTransitionStyle.Cut]（硬切）**都不滑**，恒 0。
 */
internal fun navSlideOffsetX(spec: NavSlideSpec, progress: Float, travelPx: Float): Float {
    if (spec.style != NavTransitionStyle.Slide) return 0f
    val remaining = (1f - progress).coerceIn(0f, 1f)
    val roleSign = if (spec.role == NavSlideRole.Entering) 1f else -1f
    val directionSign = when (spec.direction) {
        NavSlideDirection.IntoReader -> 1f
        NavSlideDirection.OutOfReader -> -1f
        // 滑动档之外已在上面 return 掉 ⇒ 这里的 null 不可达；以前它被静默当成「进」那一向
        null -> error("Slide 档必有方向（见 navSlideDirection）")
    }
    return roleSign * directionSign * remaining * travelPx
}

/**
 * 一屏的亮度（纯函数）：**只有冷启动落地那一支（[NavTransitionStyle.Fade]）改 alpha**——
 * 新屏淡入（`0 → 1`）、旧屏淡出（`1 → 0`）；滑入划出与硬切两支恒 1（不做亮度交叉）。
 */
internal fun navSlideAlpha(spec: NavSlideSpec, progress: Float): Float =
    if (spec.style == NavTransitionStyle.Fade) progress.coerceIn(0f, 1f) else 1f

/**
 * 起动画的接缝：[NavSlideAnimations] 只决定「哪屏动、动到哪、多长」，真正起协程由调用方给。
 *
 * 为什么要这个接缝而不在 [NavSlideFrame] 里 `LaunchedEffect` 起：自驱动画原来是在**新屏首次组合 +
 * 布局之后**的 `LaunchedEffect` 里 `animateTo` 的 ⇒ 启动被「新屏那一帧要干的活」拖在身后（阅读页组合、
 * 开书、首图解码）。现在已经提前到 [NavSlideAnimations.observe]（**在 `NavHost` 内容之前**），两屏的启动
 * 因此与新屏的组合无关，也**同一帧发出**（旧屏的滑出不再比新屏的滑入晚一帧）。
 *
 * 测试里传 `AnimationLauncher {}`（不起动画，只钉规格与进度实例的语义）。
 */
internal fun interface AnimationLauncher {
    fun launch(block: suspend () -> Unit)
}

/**
 * 每屏一个 [Animatable]：`entryId → 进度`，进度语义见 [navSlideOffsetX]。
 * 规格由 [observe] 在**组合期、`NavHost` 内容之前**更新（否则新屏首帧拿不到初始偏移）。
 *
 * **规格必须是可观察状态**：`NavSlideFrame` 在组合期读 [specOf]，而**旧屏**
 * 那时已经在组合里了——规格放在普通字段里时，它变了不会让旧屏重组，旧屏就一直拿着自己「新屏」那份
 * 规格（进度停在 1、位移停在 0）⇒ 现象「只有新屏在动、旧屏杵着不动，过一会儿被直接撤掉」。
 * 放进 `mutableStateOf` 后，读过它的组合作用域会被失效，旧屏才会拿到 Exiting 规格并重启动画。
 *
 * **唯一驱动点**：动画在 [observe] 里起，[NavSlideFrame] 只**读**进度（`graphicsLayer` 的
 * block）——它不再自己起动画，同一个 [Animatable] 上不会有两处 `animateTo` 打架。
 */
internal class NavSlideAnimations(private val launcher: AnimationLauncher) {
    private var previousIds: List<String> = emptyList()

    /** 本帧的规格表：**必须是快照状态**（见类 KDoc），否则旧屏不重组、不播滑出 */
    private var specs: Map<String, NavSlideSpec> by mutableStateOf(emptyMap())
    private val progress = mutableMapOf<String, Animatable<Float, AnimationVector1D>>()

    /**
     * 已见过的路由（`entryId → route`）：[navSlideSpecs] 要用它算「上一帧那一屏」的路由——那一项
     * 这时可能已经不在栈里了（pop 场景），必须自己记。
     */
    private val routes = mutableMapOf<String, String?>()

    /**
     * 记下这一帧的栈变化，并**当场把两屏的动画点起来**。
     *
     * - **栈没变就不动**（重组不重记、也不重播动画）；**第一次观察不产生规格**——`NavHost` 的起始目的地
     *   本来就不播过渡（[NavSlideFrame] 读到 null 直接透传，也不会被建出进度动画）；
     * - 启动就在**更新规格的同一步**里（而不是等某屏自己首次组合后的 `LaunchedEffect`）：新屏的启动因此
     *   不依赖「新屏那一帧有多重」，且新屏与旧屏**同一帧发出**动画（旧屏的滑出不再比新屏的滑入晚一帧）；
     * - 同一屏重复（连续快速操作 / 角色翻转）只会再次 `animateTo` 同一个 [Animatable]：`Animatable` 会打断
     *   上一个动画并从**当前值**续接（不重头播、不叠加两层）。
     */
    fun observe(
        currentIds: List<String>,
        routeOf: (String) -> String?,
        enterHintOf: (String) -> String?,
    ) {
        if (currentIds == previousIds) return
        currentIds.forEach { routes[it] = routeOf(it) }
        // 黑帧取数：类别与首尾路由在覆盖 `previousIds` 之前取好（后面要打进时刻线）
        val previousRoute = previousIds.lastOrNull()?.let { routes[it] }
        val enteringRoute = routes[currentIds.last()]
        specs = if (previousIds.isEmpty()) {
            emptyMap()
        } else {
            navSlideSpecs(previousIds, currentIds, { routes[it] }, enterHintOf)
        }
        previousIds = currentIds
        // 硬切的过渡：**不建动画、也不留重叠窗口**——规格照旧算出来（NavSlideFrame
        // 据此透传），但不起缓冲、不打点：没有滑动就没有「帧时长」可量。
        val animated = specs.values.any { it.style != NavTransitionStyle.Cut }
        // 时刻线的起点：有更早的同类「导航请求」就用它，没有就用这一刻；
        // 开在起动画**之前**，后面的 compose / animIssue / firstDraw 才挂得上同一个 id。
        // `detail` 走 lambda：开关关着时连字符串都不拼（与 `PerfTiming.log` 同一口径）。
        if (specs.isNotEmpty() && animated) {
            NavTransitionTimeline.begin(navTransitionKind(previousRoute, enteringRoute)) {
                "from=" + (previousRoute ?: NO_ROUTE) + " to=" + (enteringRoute ?: NO_ROUTE)
            }
        }
        if (animated) {
            specs.forEach { (entryId, spec) -> startAnimation(entryId, spec) }
        }
        // 收口：只留还在栈里的与还在动画里的（entryId 不会重号，离场的直接丢）
        val live = currentIds.toSet() + specs.keys
        progress.keys.retainAll(live)
        routes.keys.retainAll(live)
    }

    /**
     * 给一屏起一次过渡动画：目标值由角色给（新屏 → 1、旧屏 → 0），时长与曲线都取自**这一屏的规格**
     * （两屏同一帧算一次 ⇒ 必然同长同曲线）。
     *
     * 进度动画在**这里**（组合期、`NavHost` 内容之前）先建出来：新屏首帧就拿到同一个实例与正确初值。
     * 系统「移除动画」（AC-10）也在这里兜：自驱动画不读 `Settings.Global.animator_duration_scale`，
     * `ValueAnimator.areAnimatorsEnabled()` 为假时直接 `snapTo` 终值（不播）。
     */
    private fun startAnimation(entryId: String, spec: NavSlideSpec) {
        val progress = progressOf(entryId, spec.role)
        // 黑帧取数（时刻③）：**动画发出那一刻**（`observe` 的组合期，与栈变化同一帧）。
        // 与绘制块里的 `animStart`（首次真的动）分开，才能把「动画起晚（结构性）」与「首帧绘制被重活拖晚」
        // 分开——两者同形时设备上读不出主因。
        NavTransitionTimeline.mark("animIssue", onceKey = "issue:" + entryId) {
            "entry=" + entryId + " role=" + spec.role.name
        }
        launcher.launch {
            val target = if (spec.role == NavSlideRole.Entering) 1f else 0f
            if (!ValueAnimator.areAnimatorsEnabled()) {
                progress.snapTo(target)
                return@launch
            }
            progress.animateTo(
                targetValue = target,
                // 曲线取规格里的档与方向算出来的那一条——两屏同方向 ⇒ 必然同一条
                animationSpec = tween(spec.durationMillis, easing = navSlideEasing(spec.style, spec.direction)),
            )
        }
    }

    fun specOf(entryId: String): NavSlideSpec? = specs[entryId]

    /** 取该屏的进度动画：**新建时的初值按角色给**（新屏 0 = 还在屏外；旧屏 1 = 还没开始动） */
    fun progressOf(entryId: String, role: NavSlideRole): Animatable<Float, AnimationVector1D> =
        progress.getOrPut(entryId) {
            Animatable(initialProgressOf(role))
        }
}

/**
 * 一屏的过渡外壳：把这一屏的根节点包一层，用 [graphicsLayer] 做位移与亮度。
 *
 * 为什么不用 `NavHost` 的过渡对象：`EnterTransition` 只有 alpha / scale / slide 三种属性，且整屏滑入在
 * Compose 1.7 里走**布局阶段**（每帧 measure/placement 两屏）；自驱则走**绘制层**，并且能做到「打断旧
 * 动画、从当前值续接」（[Animatable] 的语义，过渡对象做不到）。
 *
 * 系统「移除动画」（AC-10）在**启动点**兜（[NavSlideAnimations.startAnimation] 里看
 * `ValueAnimator.areAnimatorsEnabled()`）：本函数只**读**进度，不起动画（唯一驱动点）。
 *
 * **新屏壳先行**（空档修复，见 [ENTERING_SHELL_FRAMES]）：**进阅读器**那一档的**新屏**
 * 头几帧只挂一张主题底色壳，重内容（阅读页整棵子树 / 浏览列表重组合）等动画真的跑起来再挂；
 * **返回档（出阅读器的新屏 = 浏览页）当帧挂正文**（2026-09-27：返回时旧屏本来就在
 * 屏上、没有「点下去先愣一下」要腾的时间，而那一档 2 帧 ≈ 33ms 时屏已进来约 17%（350ms 返回曲线在 t=33ms 处的位移百分比），纯色底就是看到的那下「闪」）。
 * 冷启动淡变那一档与旧屏也都是当帧挂正文。
 */
@Composable
internal fun NavSlideFrame(
    slide: NavSlideAnimations,
    entryId: String,
    content: @Composable () -> Unit,
) {
    val spec = slide.specOf(entryId)
    if (spec == null || spec.style == NavTransitionStyle.Cut) {
        // 透传（不包节点、不建动画、首帧不滑）的两类：①起始目的地 / 与本帧栈变化无关的屏；
        // ②硬切（层级导航 / 换书）——硬切既无动画也不留重叠窗口。
        content()
        return
    }
    // 进度动画由 `observe` 在组合期先建好并点起（见 [NavSlideAnimations]）；这里只取同一个实例。
    // 还没建出来（例如本屏与本帧的栈变化无关）时按角色给初值，与之前的行为一致。
    val progress = slide.progressOf(entryId, spec.role)
    // 黑帧取数（时刻③）：**新屏帧壳首次组合**——`remember` 只在首帧求值一次，早于首帧绘制。
    // 壳先行（[ENTERING_SHELL_FRAMES]）只作用于**「进阅读器」那一档的新屏**（见 [shellFirst]）：正文
    // （阅读页整棵子树 / 浏览列表）比这一行晚那个帧数才组合 ⇒ 这段差不是「动画起晚」。
    // **返回档（出阅读器的新屏 = 浏览页）当帧组合同屏**（那条路不挂壳）；冷启动淡变那一档与旧屏同样当帧组合同屏。
    // 组合期写日志是刻意的：要的就是这个时刻；换 `LaunchedEffect` 量到的是它之后（会把组合延迟漏掉）。
    // `remember` 放在开关**之外**：开关在过渡中途被打开时，已组合的屏不会因为多出一个槽而补记一条晚到的 compose。
    remember(entryId) {
        NavTransitionTimeline.mark("compose", onceKey = "compose:" + entryId) {
            "entry=" + entryId + " role=" + spec.role.name + " style=" + spec.style.name +
                " dur=" + spec.durationMillis + "ms"
        }
    }
    // 行程 = **这一屏**的宽度：用本层自己的约束，不读 `LocalConfiguration`
    // （多窗口 / 分屏 / 自由窗口下 screenWidthDp 与实际宽度可以不等；「取该容器自己的约束」是本仓既有口径）。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val travelPx = with(LocalDensity.current) {
            maxWidth.toPx() * NavTransitions.SLIDE_TRAVEL_PERCENT / 100f
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 进度**只在这里读**：`Animatable.value` 是快照状态，
                    // 在组合期读的话这一档时长里每帧都会重组本屏（并重跑 `content()`），
                    // 与「自驱之后走绘制层、不再每帧摆放两屏」的初衷相左；放进 `graphicsLayer` 的 block 只失效图层。
                    val current = progress.value
                    translationX = navSlideOffsetX(spec, current, travelPx)
                    alpha = navSlideAlpha(spec, current)
                    // 黑帧取数（时刻④）：本屏**第一帧绘制**（带当时的位移）与**动画第一次真的动**。
                    // 两行各自「这次过渡只记一次」（绘制块每帧都会被求值）；开关关着时不拼字符串（mark 的第一行即返回）。
                    // `offX ≈ 0` 而 `travel != 0` = 新屏首帧就整屏画在屏上（滑之前那一帧就是黑的）。
                    // 壳先行的改动**不动这条红线**：壳也走同一层，首帧的 `offX` 仍是整个行程。
                    NavTransitionTimeline.mark("firstDraw", onceKey = "draw:" + entryId) {
                        "entry=" + entryId + " role=" + spec.role.name +
                            " offX=" + translationX.toInt() + " travel=" + travelPx.toInt()
                    }
                    if (current != initialProgressOf(spec.role)) {
                        NavTransitionTimeline.mark("animStart", onceKey = "anim:" + entryId) {
                            "entry=" + entryId + " role=" + spec.role.name
                        }
                    }
                },
        ) {
            if (shellFirst(spec)) {
                // 壳先行（空档修复；收窄到「进阅读器」那一档）：头 [ENTERING_SHELL_FRAMES] 帧只画壳，重内容推后。
                // 范围见 [shellFirst]：只有**进阅读器**那一档的**新屏**；返回档当帧挂正文。
                // 壳必须有底色：既保证这一帧真的被画出来（`firstDraw` 才有得记），也是口径要求的
                // 「滑入期间新屏是主题背景色」——内容未挂时不能露出下面正在退场的那一屏。
                // 键用 `entryId`（不是角色）：同一屏后来变成旧屏时**保持已挂载**，不会把自己的内容抽空。
                var contentMounted by remember(entryId) { mutableStateOf(false) }
                LaunchedEffect(entryId) {
                    repeat(ENTERING_SHELL_FRAMES) { withFrameNanos { } }
                    contentMounted = true
                }
                if (contentMounted) {
                    content()
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                }
            } else {
                // 旧屏已经在屏上：内容一个字不动（没它就会变成「退场屏自己先空一下」）；
                // **返回档的新屏、冷启动那一档的新屏也都走这里**（当帧挂正文，见 [shellFirst]）。
                content()
            }
        }
    }
}

/**
 * 新屏「壳先行」要等几个帧回调才挂重内容（空档修复）。
 *
 * **要修的是什么**（设备取数）：`animIssue`（动画发出）与栈变化同帧（0–6ms），但主线程从那一刻起被
 * 「新屏首次组合 + 首帧测量/绘制」占住 ⇒ `animStart`（动画第一次真的动并被画出来）落在 26–104ms——
 * 就是「点下去先愣一下」。
 *
 * **为什么是两帧**（不是一帧）：动画第一次真的动发生在「栈变化那一帧」的**后一帧**，而绘制块要到帧末才跑；
 * 只推后一帧的话，重活恰好落在「动画第一次真的动并被画出来」的那一帧上——`animStart` 会被拖到那一帧的帧末，
 * 白忙。推后两帧则前两帧都是「壳 + 位移」的轻帧：动画在第二帧已可见地动起来，重活落在**之后**的那一帧。
 *
 * **它不改什么**：行程（[NavTransitions.SLIDE_TRAVEL_PERCENT]）、时长（[navTransitionWindowMillis]）、
 * 曲线（[navSlideEasing]）、两屏同时开始/同时结束——动画时钟仍由 `observe` 在栈变化那一帧启动，
 * 推迟的只是「重内容何时挂载」；也与「让动画追赶」相反（口径明令不做：那会把滑动压缩并在首帧跳变）。
 *
 * **作用范围**：只有**进阅读器**那一档的新屏（见 [shellFirst]）——返回档 2 帧 ≈ 33ms 时屏已进来约 17%（350ms 返回曲线在 t=33ms 处的位移百分比），
 * 那条纯色底就是看到的「闪」。
 *
 * **设备取数时它可能要微调**（判据：`navTransitionDetail` 的 `begin → animStart` ≤ 1–2 帧）：
 * 它是个常量，只影响「看见在动的时刻」，不动行程与时长。
 *
 * `internal` 而非 `private`：由 [NavTransitionsTest] 的常量用例直接钉住（与同文件其它测试接缝一致）。
 */
internal const val ENTERING_SHELL_FRAMES: Int = 2

/**
 * 这一屏要不要「壳先行」（纯函数，由 `NavTransitionsTest` 钉住）：**只有「进阅读器」那一档的新屏**
 * （滑动档 + 新屏 + `direction == IntoReader`；2026-09-27 定）。
 *
 * - **返回档（出阅读器的新屏）不壳先行**：2026-09-27 定「接受不了返回时闪」——那条路上旧屏本来就在
 *   屏上，没有「点下去先愣一下」要腾的时间，而 2 帧 ≈ 33ms 时新屏已滑进来约 17%（350ms 返回曲线在 t=33ms 处的位移百分比），那条纯色底就是看到的「闪」
 *   （该开关是 `remember(entryId)`、存不住 ⇒ 每次返回都从「没挂内容」起）；
 * - 冷启动交叉淡变那一档**不**壳先行：2026-09-27 定那一档「保持原样、不进改动面」，
 *   而它也没有「点下去先愣一下」这件事（没有点击、没有整屏位移）⇒ 当帧挂正文；
 * - 旧屏（[NavSlideRole.Exiting]）与硬切都不壳先行：前者会把退场屏的内容抽空，后者根本不包节点。
 */
internal fun shellFirst(spec: NavSlideSpec): Boolean =
    spec.style == NavTransitionStyle.Slide &&
        spec.role == NavSlideRole.Entering &&
        spec.direction == NavSlideDirection.IntoReader

/**
 * 全局页面过渡：**只有进出阅读器有动画，其余一律硬切**。
 *
 * - 方向：出是进的**逆过程**——进阅读器新屏从**右**滑入（`+100% → 0`）、旧屏往**左**滑出；
 *   出阅读器全部取反（新屏从左进、旧屏往右出）。两屏都是整屏行程、完全出屏（见 [navSlideOffsetX]）；
 * - 时长与曲线（时长于 2026-09-28 改为进 500 / 出 350，曲线未动）：**两屏同一份**——
 *   进阅读器 **500ms** + `CubicBezier(0, 0, 0.6, 1)`、出阅读器 **350ms** + `CubicBezier(0.25, 0.5, 0.7, 1)`；
 *   冷启动落进阅读器 300ms + 匀速（交叉淡变）；
 *   时长由 [navTransitionWindowMillis] 一帧算一次、曲线由 [navSlideEasing] 一处给出，都进 [NavSlideSpec]，
 *   两屏各读自己的规格 ⇒ 不可能各写一份；
 * - **硬切**（[NavTransitionStyle.Cut]：层级导航 / 换书）：不建动画、**也不留重叠窗口**——
 *   [NavSlideFrame] 直接透传、两个空壳回 [EnterTransition.None] / [ExitTransition.None]；
 * - **没有亮度交叉**：滑入划出与硬切两档 alpha 恒 1（旧口径的 0.55 镜像已整套推翻，见沿革）；
 * - **不做错开**（两屏同时动）；驱动方式**自驱**：`NavHost` 的四支过渡退化成**零视觉空壳**
 *   （[holdEnter] / [holdExit]，alpha 恒 1，只用来撑住重叠窗口，长度取**本次过渡自己的时长**），
 *   真正的位移与冷启动淡入挂在**每一屏自己的根节点**上（[NavSlideFrame] + `Animatable`）。
 *
 * 沿革：「纵向 8dp 纯交叉」→ 2026-09-22「横向整屏滑入」→ 2026-09-23「新屏收到 30% + 亮度镜像」
 *（新屏飞快、旧屏所有导航都有残影）→ 整套推翻回整屏滑入划出、拿掉亮度交叉 →
 *（方向统一、曲线匀速、进阅读器 500ms + 修掉「旧屏根本没有滑出动画」，根因见 [NavSlideAnimations]）→
 * 只有「文件夹 ↔ 阅读器」有动画（进 350ms / 出 250ms 镜像），层级导航与换书改硬切，
 * 并修「点了先愣一下」的空档（见 [ENTERING_SHELL_FRAMES]）→ **2026-09-28 定**：时长改为
 * 进 500ms / 出 350ms（曲线不动，进 350 / 出 250 作废）。
 *
 * **可测面**：呈现方式、方向、角色、整屏幅度、两条曲线、三档时长、冷启动不滑、「旧屏终点完全出屏」、
 * 「只有 Fade 改 alpha」、硬切不建动画（不向缓冲表里放东西）、
 * 以及「规格是可观察状态」（旧屏能不能重组的根因）——都是**纯函数 / 常量 / 快照读**，`NavTransitionsTest` 钉住。
 * **仍钉不住**的是接线那一半：① 四支 lambda 是否真的返回零视觉空壳（alpha 1→1、`None` 以外的时长不可观测）、
 * ② 8 个目的地是否真的都包了 [NavSlideFrame]、③「壳先行」的帧数与真实手感。
 *
 * 已知代价：
 * - 自驱之后位移走**绘制层**（`graphicsLayer`），不再是 Compose 1.7.2 `EnterExitTransitionModifierNode` 的
 *   measure/placement 每帧摆放两屏；换来的是 AppNav 组合期多读一次 `nav.currentBackStack.value`
 *   （栈一变就重组导航壳）与 [NavSlideAnimations] 的一张进度表；掉帧仍以量化数据为准
 *   （`NavTransitionProbe`，挂在导航壳上），不过关不阻塞交付；
 * - **不做自动降级**：掉帧时不会自己退化成淡入；
 * - 进出阅读器的过渡存在期间（500 / 350ms），正在退场的那一屏**仍接收点击**（既有机制，
 *   选择保持现状）；层级导航与换书是硬切、没有窗口，这条代价在那两类上随之消失。
 */
internal class NavTransitions {

    /**
     * 一档过渡的两个空壳（进入 / 退出）：只用来撑住「两屏同时在屏上」的窗口，视觉零变化（alpha 恒 1）。
     */
    private data class Shell(val enter: EnterTransition, val exit: ExitTransition)

    private val shellIntoReader = shell(ENTER_READER_DURATION_MILLIS, INTO_READER_EASING)
    private val shellOutOfReader = shell(EXIT_READER_DURATION_MILLIS, OUT_OF_READER_EASING)
    private val shellFade = shell(FADE_DURATION_MILLIS, FADE_EASING)

    private fun shell(durationMillis: Int, easing: Easing): Shell {
        val spec = tween<Float>(durationMillis, easing = easing)
        return Shell(fadeIn(spec, initialAlpha = 1f), fadeOut(spec, targetAlpha = 1f))
    }

    /**
     * 进入屏的空壳：起点 / 终点 alpha 都是 1 ⇒ **视觉零变化**，唯一作用是让 `NavHost` 在**本次过渡的时长**里
     * 同时保留新旧两屏（真正的位移/淡入由每一屏自己的 [NavSlideFrame] 驱动，见 [navSlideOffsetX]）。
     * [NavTransitionStyle.Cut] → [EnterTransition.None]（硬切不留窗口，旧屏当帧不在）。
     */
    fun holdEnter(style: NavTransitionStyle, direction: NavSlideDirection?): EnterTransition =
        if (style == NavTransitionStyle.Cut) EnterTransition.None else shellOf(style, direction).enter

    /** 退场空壳：同上（终点 alpha 仍是 1，不淡出——淡出也由每屏自己驱动，只有冷启动那一支会淡） */
    fun holdExit(style: NavTransitionStyle, direction: NavSlideDirection?): ExitTransition =
        if (style == NavTransitionStyle.Cut) ExitTransition.None else shellOf(style, direction).exit

    private fun shellOf(style: NavTransitionStyle, direction: NavSlideDirection?): Shell = when (style) {
        NavTransitionStyle.Fade -> shellFade
        NavTransitionStyle.Slide -> when (direction) {
            NavSlideDirection.IntoReader -> shellIntoReader
            NavSlideDirection.OutOfReader -> shellOutOfReader
            // 「Slide 却没有方向」不可达（见 navSlideDirection）：以前这里静默给「出」档的空壳，
            // 与时长那一处的兜底答案不一致
            null -> error("Slide 档必有方向（见 navSlideDirection）")
        }
        // 硬切由 holdEnter / holdExit 在调用本函数**之前**返回 None，取不到空壳
        NavTransitionStyle.Cut -> error("硬切没有空壳（holdEnter / holdExit 应先返回 None）")
    }

    companion object {
        /** 进阅读器（文件夹 → 阅读器）的过渡时长（毫秒；2026-09-28 定为 **500ms**） */
        const val ENTER_READER_DURATION_MILLIS: Int = 500

        /** 出阅读器（阅读器 → 文件夹）的过渡时长（毫秒；2026-09-28 定为 **350ms**，镜像） */
        const val EXIT_READER_DURATION_MILLIS: Int = 350

        /** 冷启动直接落进阅读器那一档的时长（毫秒）：**300ms** */
        const val FADE_DURATION_MILLIS: Int = 300

        /**
         * 两屏的位移比例（整屏宽度的百分数）：**100%**（整屏）。
         * 两屏同读这一个值：两屏都是整个行程、完全出屏（终点不残留半透明影像）。
         */
        const val SLIDE_TRAVEL_PERCENT: Int = 100

        /**
         * "进阅读器"那一档的曲线：`CubicBezier(0f, 0f, 0.6f, 1f)`。
         * 起步快、末尾缓缓停下（数值法读数：起步约 1.6 倍匀速、末段几乎为 0）。
         */
        val INTO_READER_EASING: Easing = CubicBezierEasing(0f, 0f, 0.6f, 1f)

        /**
         * "出阅读器"那一档的曲线：`CubicBezier(0.25f, 0.5f, 0.7f, 1f)`。
         * 与进那条同族（起步快、末尾缓停），数值略不同——出比进短（350ms），不能靠同一条撑手感。
         */
        val OUT_OF_READER_EASING: Easing = CubicBezierEasing(0.25f, 0.5f, 0.7f, 1f)

        /**
         * 冷启动那一档的曲线：**匀速**（`LinearEasing`）——这一档定为「保持现口径」，不动它。
         */
        val FADE_EASING: Easing = LinearEasing
    }
}
