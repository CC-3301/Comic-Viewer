package com.cc3301.comicviewer.ui.nav

import android.animation.ValueAnimator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.cc3301.comicviewer.ui.Routes

/**
 * 导航过渡的**唯一规格出处**：时长 / 曲线 / 方向 / 幅度 / 角色与「哪一屏动」的声明、纯函数、空壳过渡、
 * 动画持有、帧壳都只落在本文件（守护用例：`NavTransitionsTest` 的「过渡规格只声明在一处」）。
 */

/**
 * 一次导航过渡的**呈现方式**：两档。
 *
 * - [Slide]：**静态背景滑动**——一次过渡里只有一屏动（动的那一屏永远是**新屏**，见 [navSlideMoves]），
 *   被盖住的另一屏原地静止当背景；只有「文件夹 ↔ 阅读器」的进出走它；
 * - [Cut]：**硬切**（零过渡、瞬间换屏，也不留重叠窗口）——层级导航（文件夹之间 / 返回上级 / 抽屉入口 /
 *   书柜进柜）与换书（上一本 / 下一本 / 跨书条）都是它。
 *
 * 冷启动直接落进阅读器与普通进档同款滑动（[navTransitionStyle] 按前后路由判，进阅读器一律 [Slide]）。
 */
internal enum class NavTransitionStyle { Slide, Cut }

/**
 * 滑动这一档的**两个方向**（纯函数 [navSlideDirection] 的产物）：出是进的**逆过程**。
 *
 * - [IntoReader]：进阅读器（文件夹 → 阅读器）——新屏（阅读页）从**右**滑入（+100% → 0），旧屏（浏览页）原地静止当背景；
 * - [OutOfReader]：出阅读器（阅读器 → 文件夹）——**镜像**：新屏（浏览页）从**左**滑入（−100% → 0），旧屏（阅读页）原地静止当背景。
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
 * | 阅读器 → 文件夹（返回） | [NavTransitionStyle.Slide]（出，镜像） |
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
    // 出阅读器（返回浏览层）：镜像滑动
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
 * 一次过渡的时长（毫秒，纯函数，由 `NavTransitionsTest` 锁定）：
 *
 * - **进阅读器（文件夹 → 阅读器，含冷启动落地）400ms**；
 * - **出阅读器（阅读器 → 文件夹）300ms**（镜像）；
 * - 硬切（层级导航 / 换书）**0**：不建动画、也不留重叠窗口。
 *
 * 两个调用点必须得到**同一个数**：每屏自己的动画（[navSlideSpecs] 算进规格）与量测窗口
 * （`beginNavTransitionProbe`）——所以都调这一个函数，不再各自读一个常量。
 * 曲线跟它同源：[navSlideEasing] 同读这一档（同一套 style + 方向），量测窗与两个空壳也读同一个数。
 */
internal fun navTransitionWindowMillis(
    previousRoute: String?,
    enteringRoute: String?,
): Int = when (navTransitionStyle(previousRoute, enteringRoute)) {
    NavTransitionStyle.Cut -> 0
    // 滑动档的长短只由 [slideDirectionOf] 的真实取值决定（没有「没有方向」那一支可以兜底）：
    // 消费点因此不可能各拿一个数
    NavTransitionStyle.Slide -> when (slideDirectionOf(previousRoute)) {
        NavSlideDirection.IntoReader -> NavTransitions.ENTER_READER_DURATION_MILLIS
        NavSlideDirection.OutOfReader -> NavTransitions.EXIT_READER_DURATION_MILLIS
    }
}

/**
 * 一次过渡的曲线（纯函数）：与 [navTransitionWindowMillis] 成对、同样由**一处**给出。
 *
 * 进用 `CubicBezier(0.35, 0.7, 0.7, 1)`（起步快、末尾缓停）、
 * 出用 `CubicBezier(0.4, 0, 1, 1)`（起步慢、越滑越快、终点不减速）。
 *
 * 按**档**读（而不是按「方向是不是 null」）：两个不可达的分支直接 `error`，不静默给一个数。
 */
internal fun navSlideEasing(style: NavTransitionStyle, direction: NavSlideDirection?): Easing = when (style) {
    NavTransitionStyle.Slide -> when (direction) {
        NavSlideDirection.IntoReader -> NavTransitions.INTO_READER_EASING
        NavSlideDirection.OutOfReader -> NavTransitions.OUT_OF_READER_EASING
        // 「Slide 却没有方向」不可达（见 navSlideDirection）
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
 * [direction] 只有 [NavTransitionStyle.Slide] 有（硬切没有方向，为 null）。
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
 * - 呈现方式 / 方向 / 时长：同一套判据（[navTransitionStyle] / [navSlideDirection] /
 *   [navTransitionWindowMillis]）——只有文件夹 ↔ 阅读器两向是 [NavTransitionStyle.Slide]，
 *   其余（层级导航 / 换书）是 [NavTransitionStyle.Cut]（时长 0）；
 * - **两屏都拿到规格**：静止那一屏也要有（帧壳恒包住每一屏、`firstDraw` 才有得记）。
 */
internal fun navSlideSpecs(
    previousIds: List<String>,
    currentIds: List<String>,
    routeOf: (String) -> String?,
): Map<String, NavSlideSpec> {
    if (currentIds.isEmpty()) return emptyMap()
    val enteringId = currentIds.last()
    val enteringNew = enteringId !in previousIds
    val previousRoute = previousIds.lastOrNull()?.let(routeOf)
    val enteringRoute = routeOf(enteringId)
    val entering = NavSlideSpec(
        style = navTransitionStyle(previousRoute, enteringRoute),
        role = NavSlideRole.Entering,
        durationMillis = navTransitionWindowMillis(previousRoute, enteringRoute),
        direction = navSlideDirection(previousRoute, enteringRoute),
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
 * 一屏的进度初值（纯函数）：新屏 0（还在屏外）、旧屏 1（还没开始动）。
 * 黑帧取数的「动画第一次真的动」据它判定（`progress.value` 一旦离开这个初值就是首帧动画）。
 */
internal fun initialProgressOf(role: NavSlideRole): Float =
    if (role == NavSlideRole.Entering) 0f else 1f

/**
 * 一屏在一次过渡里**动还是当背景**（纯函数，由 `NavTransitionsTest` 锁定）：**动的永远是「新屏」**
 * （[NavSlideRole.Entering]），旧屏原地静止当背景；[NavTransitionStyle.Cut] 没有动画、两屏都不动。
 *
 * **为什么是「新屏」而不是「阅读页」**：`NavHost` 出档时把新屏追加到可见列表末尾
 * （`currentlyVisible.add(targetState)`），而 Compose 在 zIndex 相同时按**放置先后**画 ⇒
 * **新屏永远画在最上面**。被盖住的那一屏若在动，滑出根本看不见（出档曾表现为「瞬间换屏」）。
 * 把滑动给新屏，两个方向的位移都看得见：
 *
 * - 进阅读器：新屏（阅读页）从**右**滑入，逐渐盖住静止的浏览页；
 * - 出阅读器：新屏（浏览页）从**左**滑入，逐渐盖住静止的阅读页——与「阅读页往右退出、露出浏览页」
 *   在画面上是同一张图（未被盖住的那一侧始终是旧屏），差别只是旧屏自己的内容不跟着位移。
 */
internal fun navSlideMoves(spec: NavSlideSpec): Boolean = when (spec.style) {
    NavTransitionStyle.Slide -> spec.role == NavSlideRole.Entering
    NavTransitionStyle.Cut -> false
}

/**
 * 一屏的横向位移（px，纯函数）：[progress] 的语义**统一为「新屏 0 → 1、旧屏 1 → 0」**。
 *
 * 动的那一屏（[navSlideMoves]，即新屏）**从屏外滑到 0**，整屏行程
 * （[NavTransitions.SLIDE_TRAVEL_PERCENT] 由调用方折进 [travelPx]）：
 *
 * - 进阅读器：新屏（阅读页）`+travelPx` → 0（从**右**进）；
 * - 出阅读器：新屏（浏览页）`-travelPx` → 0（从**左**进，镜像）。
 *
 * 旧屏**位移恒 0**——它原地静止当背景，被新屏逐渐盖住（见 [navSlideMoves]）。
 * [NavTransitionStyle.Cut]（硬切）不滑，恒 0。
 */
internal fun navSlideOffsetX(spec: NavSlideSpec, progress: Float, travelPx: Float): Float {
    if (spec.style != NavTransitionStyle.Slide) return 0f
    // 旧屏：原地静止当背景（一次过渡只有一屏动）
    if (!navSlideMoves(spec)) return 0f
    // 滑动档必有方向（见 navSlideDirection）；不可达的分支直接响，不静默当成某一向
    val direction = spec.direction ?: error("Slide 档必有方向（见 navSlideDirection）")
    val sign = when (direction) {
        NavSlideDirection.IntoReader -> 1f // 从右进
        NavSlideDirection.OutOfReader -> -1f // 从左进（镜像）
    }
    val remaining = (1f - progress).coerceIn(0f, 1f)
    return sign * remaining * travelPx
}

/**
 * 起动画的接缝：[NavSlideAnimations] 只决定「哪屏动、动到哪、多长」，真正起协程由调用方给。
 *
 * 为什么不在 [NavSlideFrame] 里 `LaunchedEffect` 起：自驱动画若挂在新屏首次组合 + 布局之后，
 * 启动就被「新屏那一帧要干的活」拖在身后。提前到 [NavSlideAnimations.observe]（**在 `NavHost`
 * 内容之前**）后，动的那一屏的启动与新屏的组合无关。
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
 * **规格必须是可观察状态**：`NavSlideFrame` 在组合期读 [specOf]，而**静止侧**
 * 那时已经在组合里了——规格放在普通字段里时，它变了不会让那一屏重组，那一屏就一直拿着上一帧的
 * 规格（进度停在初值、位移停在 0）。放进 `mutableStateOf` 后，读过它的组合作用域会被失效，
 * 静止侧才会拿到本帧的规格。
 *
 * **唯一驱动点**：动画在 [observe] 里起，且**只起动的那一屏**（[navSlideMoves]）；
 * [NavSlideFrame] 只**读**进度（`graphicsLayer` 的 block），它不自己起动画。
 */
internal class NavSlideAnimations(private val launcher: AnimationLauncher) {
    private var previousIds: List<String> = emptyList()

    /** 本帧的规格表：**必须是快照状态**（见类 KDoc），否则静止侧不重组、拿不到本帧规格 */
    private var specs: Map<String, NavSlideSpec> by mutableStateOf(emptyMap())
    private val progress = mutableMapOf<String, Animatable<Float, AnimationVector1D>>()

    /**
     * 已见过的路由（`entryId → route`）：[navSlideSpecs] 要用它算「上一帧那一屏」的路由——那一项
     * 这时可能已经不在栈里了（pop 场景），必须自己记。
     */
    private val routes = mutableMapOf<String, String?>()

    /**
     * 记下这一帧的栈变化，并**当场把动的那一屏的动画点起来**。
     *
     * - **栈没变就不动**（重组不重记、也不重播动画）；**第一次观察不产生规格**——`NavHost` 的起始目的地
     *   本来就不播过渡（[NavSlideFrame] 读到 null 也照常包节点，位移恒 0）；
     * - 启动就在**更新规格的同一步**里：动的那一屏的启动因此不依赖「新屏那一帧有多重」；
     * - 同一屏重复（连续快速操作 / 角色翻转）只会再次 `animateTo` 同一个 [Animatable]：`Animatable` 会打断
     *   上一个动画并从**当前值**续接（不重头播、不叠加两层）；
     * - **静止那一屏不起动画**：它原地不动，进度动画对它没有意义（也不占 `Animatable`）。
     */
    fun observe(
        currentIds: List<String>,
        routeOf: (String) -> String?,
    ) {
        if (currentIds == previousIds) return
        currentIds.forEach { routes[it] = routeOf(it) }
        // 黑帧取数：类别与首尾路由在覆盖 `previousIds` 之前取好（后面要打进时刻线）
        val previousRoute = previousIds.lastOrNull()?.let { routes[it] }
        val enteringRoute = routes[currentIds.last()]
        specs = if (previousIds.isEmpty()) {
            emptyMap()
        } else {
            navSlideSpecs(previousIds, currentIds, { routes[it] })
        }
        previousIds = currentIds
        // 硬切的过渡：**不建动画、也不留重叠窗口**——规格照旧算出来（NavSlideFrame 据此包节点、位移恒 0），
        // 但不起缓冲、不打点：没有滑动就没有「帧时长」可量。
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
            specs.forEach { (entryId, spec) ->
                if (navSlideMoves(spec)) startAnimation(entryId, spec)
            }
        }
        // 收口：只留还在栈里的与还在动画里的（entryId 不会重号，离场的直接丢）
        val live = currentIds.toSet() + specs.keys
        progress.keys.retainAll(live)
        routes.keys.retainAll(live)
    }

    /**
     * 给动的那一屏起一次过渡动画：目标值由角色给（新屏 → 1、旧屏 → 0），时长与曲线都取自
     * **这一屏的规格**。
     *
     * 进度动画在**这里**（组合期、`NavHost` 内容之前）先建出来：新屏首帧就拿到同一个实例与正确初值。
     * 系统「移除动画」也在这里兜：自驱动画不读 `Settings.Global.animator_duration_scale`，
     * `ValueAnimator.areAnimatorsEnabled()` 为假时直接 `snapTo` 终值（不播）。
     */
    private fun startAnimation(entryId: String, spec: NavSlideSpec) {
        val progress = progressOf(entryId, spec.role)
        // 黑帧取数（时刻）：**动画发出那一刻**（`observe` 的组合期，与栈变化同一帧）。
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
                animationSpec = tween(spec.durationMillis, easing = navSlideEasing(spec.style, spec.direction)),
            )
        }
    }

    fun specOf(entryId: String): NavSlideSpec? = specs[entryId]

    /** 取该屏的进度动画：**新建时的初值按角色给**（新屏 0 = 还在屏外；旧屏 1 = 还没开始动）。
     * 只有动的那一屏会走到这里（静止侧不建进度动画）。 */
    fun progressOf(entryId: String, role: NavSlideRole): Animatable<Float, AnimationVector1D> =
        progress.getOrPut(entryId) {
            Animatable(initialProgressOf(role))
        }
}

/**
 * 一屏的过渡外壳：把这一屏的根节点包一层，用 [graphicsLayer] 做位移。
 *
 * **恒包节点**（不管这一屏有没有过渡规格）：两屏、硬切、起始目的地都走同一个结构
 * （`BoxWithConstraints > Box > content`）。这是「过渡全程两屏都被绘制」的结构前提——
 * 已在屏上的屏如果因为「有了规格才包壳」而中途换结构，它的整棵子树会被丢弃重建
 * （`remember` 清空、`LaunchedEffect` 重跑），退出阅读器时表现为阅读页重新开书 + 两屏同时重画
 * ⇒ 过渡里出现整屏底色。结构恒定后，过渡里唯一的组合是新屏自己的首建。
 *
 * 为什么不用 `NavHost` 的过渡对象：`EnterTransition` 只有 alpha / scale / slide 三种属性，且整屏滑入在
 * Compose 1.7 里走**布局阶段**（每帧 measure/placement 两屏）；自驱则走**绘制层**，并且能做到「打断旧
 * 动画、从当前值续接」（[Animatable] 的语义，过渡对象做不到）。
 *
 * 系统「移除动画」在**启动点**兜（[NavSlideAnimations.startAnimation] 里看
 * `ValueAnimator.areAnimatorsEnabled()`）：本函数只**读**进度，不起动画（唯一驱动点）。
 */
@Composable
internal fun NavSlideFrame(
    slide: NavSlideAnimations,
    entryId: String,
    content: @Composable () -> Unit,
) {
    val spec = slide.specOf(entryId)
    val slideSpec = spec?.takeIf { it.style == NavTransitionStyle.Slide }
    // 黑帧取数：本屏帧壳首次组合（`remember` 只在首帧求值一次，早于首帧绘制）。
    // 组合期写日志是刻意的：要的就是这个时刻；换 `LaunchedEffect` 量到的是它之后（会把组合延迟漏掉）。
    // **remember 必须恒在**（判断挪进 lambda）：它若按「有没有滑动规格」出现/消失，本屏首次拿到规格时
    // 帧壳的组合结构还是会变、整棵子树跟着重建——与「帧壳恒包节点」要修的根因同族。
    // 代价：出生时没规格的屏（硬切/起始目的地）首次拿到滑动规格的那一帧不记 compose（`firstDraw` 照记）。
    remember(entryId) {
        if (slideSpec != null) {
            NavTransitionTimeline.mark("compose", onceKey = "compose:" + entryId) {
                "entry=" + entryId + " role=" + slideSpec.role.name + " style=" + slideSpec.style.name +
                    " dur=" + slideSpec.durationMillis + "ms"
            }
        }
        true
    }
    // 动的那一屏（新屏）：只在它上面挂进度动画
    val moving = slideSpec != null && navSlideMoves(slideSpec)
    // 行程 = **这一屏**的宽度：用本层自己的约束，不读 `LocalConfiguration`
    // （多窗口 / 分屏 / 自由窗口下 screenWidthDp 与实际宽度可以不等；「取该容器自己的约束」是本仓既有口径）。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val travelPx = with(LocalDensity.current) {
            maxWidth.toPx() * NavTransitions.SLIDE_TRAVEL_PERCENT / 100f
        }
        // 进度动画由 `observe` 在组合期先建好并点起（只给动的那一屏）；这里只取同一个实例。
        // 进度**只在绘制块里读**：`Animatable.value` 是快照状态，在组合期读的话这一档时长里每帧都会
        // 重组本屏（并重跑 `content()`）；放进 `graphicsLayer` 的 block 只失效图层。
        val progress = if (moving && slideSpec != null) slide.progressOf(entryId, slideSpec.role) else null
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (progress != null && slideSpec != null) {
                        navSlideOffsetX(slideSpec, progress.value, travelPx)
                    } else {
                        0f
                    }
                    // 黑帧取数：本屏**第一帧绘制**（带当时的位移）与**动画第一次真的动**。
                    // 各自「这次过渡只记一次」（绘制块每帧都会被求值）；开关关着时不拼字符串。
                    // 静止侧的 `offX` 恒 0：它整场过渡都在原位被画出来（两屏都在画 ⇒ 取数判据）。
                    if (slideSpec != null) {
                        NavTransitionTimeline.mark("firstDraw", onceKey = "draw:" + entryId) {
                            "entry=" + entryId + " role=" + slideSpec.role.name +
                                " offX=" + translationX.toInt() + " travel=" + travelPx.toInt()
                        }
                        if (progress != null && slideSpec != null && progress.value != initialProgressOf(slideSpec.role)) {
                            NavTransitionTimeline.mark("animStart", onceKey = "anim:" + entryId) {
                                "entry=" + entryId + " role=" + slideSpec.role.name
                            }
                        }
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * 全局页面过渡：**只有进出阅读器有动画（静态背景滑动），其余一律硬切**。
 *
 * - 一次过渡**只有一屏动**（[navSlideMoves]），动的那一屏永远是**新屏**：进阅读器时是阅读页（从**右**滑入，
 *   `+100% → 0`）、出阅读器时是浏览页（从**左**滑入，`−100% → 0`）；旧屏两次都原地静止当背景、当帧挂正文、
 *   过渡全程真的被画出来（见 [navSlideOffsetX]）；
 * - 时长与曲线（**两屏同一份**）：进阅读器 **400ms** + `CubicBezier(0.35, 0.7, 0.7, 1)`、
 *   出阅读器 **300ms** + `CubicBezier(0.4, 0, 1, 1)`；冷启动落进阅读器与普通进档同款（无单独一档）。
 *   时长由 [navTransitionWindowMillis] 一帧算一次、曲线由 [navSlideEasing] 一处给出，都进 [NavSlideSpec]；
 * - **硬切**（[NavTransitionStyle.Cut]：层级导航 / 换书）：不建动画、**也不留重叠窗口**——
 *   两个空壳回 [EnterTransition.None] / [ExitTransition.None]；
 * - **不做亮度交叉**（恒 1）、**不做错开**；驱动方式**自驱**：`NavHost` 的四支过渡退化成**零视觉空壳**
 *   （[holdEnter] / [holdExit]，alpha 恒 1，只用来撑住重叠窗口，长度取**本次过渡自己的时长**），
 *   真正的位移挂在**每一屏自己的根节点**上（[NavSlideFrame] + `Animatable`）。
 *
 * **可测面**：呈现方式、方向、角色、整屏幅度、两条曲线、两档时长、「一次过渡只有一屏动」、
 * 硬切不建动画（不向缓冲表里放东西）、以及「规格是可观察状态」（静止侧能不能重组的根因）——
 * 都是**纯函数 / 常量 / 快照读**，`NavTransitionsTest` 钉住。
 * **仍钉不住**的是接线那一半：① 四支 lambda 是否真的返回零视觉空壳（alpha 1→1、`None` 以外的时长不可观测）、
 * ② 8 个目的地是否真的都包了 [NavSlideFrame]、③ 设备上的逐帧观感与帧时长。
 *
 * 已知代价：
 * - 自驱之后位移走**绘制层**（`graphicsLayer`），不再是 Compose 1.7 的 measure/placement 每帧摆放两屏；
 *   换来的是 AppNav 组合期多读一次 `nav.currentBackStack.value`（栈一变就重组导航壳）与
 *   [NavSlideAnimations] 的一张进度表；掉帧仍以量化数据为准（`NavTransitionProbe`），不过关不阻塞交付；
 * - **不做自动降级**：掉帧时不会自己退化成淡入；
 * - 进出阅读器的过渡存在期间，正在被盖住的那一屏**仍接收点击**（既有机制，选择保持现状）；
 *   层级导航与换书是硬切、没有窗口，这条代价在那两类上随之消失。
 */
internal class NavTransitions {

    /**
     * 一档过渡的两个空壳（进入 / 退出）：只用来撑住「两屏同时在屏上」的窗口，视觉零变化（alpha 恒 1）。
     */
    private data class Shell(val enter: EnterTransition, val exit: ExitTransition)

    private val shellIntoReader = shell(ENTER_READER_DURATION_MILLIS, INTO_READER_EASING)
    private val shellOutOfReader = shell(EXIT_READER_DURATION_MILLIS, OUT_OF_READER_EASING)

    private fun shell(durationMillis: Int, easing: Easing): Shell {
        val spec = tween<Float>(durationMillis, easing = easing)
        return Shell(fadeIn(spec, initialAlpha = 1f), fadeOut(spec, targetAlpha = 1f))
    }

    /**
     * 进入屏的空壳：起点 / 终点 alpha 都是 1 ⇒ **视觉零变化**，唯一作用是让 `NavHost` 在**本次过渡的时长**里
     * 同时保留新旧两屏（真正的位移由每一屏自己的 [NavSlideFrame] 驱动，见 [navSlideOffsetX]）。
     * [NavTransitionStyle.Cut] → [EnterTransition.None]（硬切不留窗口，旧屏当帧不在）。
     */
    fun holdEnter(style: NavTransitionStyle, direction: NavSlideDirection?): EnterTransition =
        if (style == NavTransitionStyle.Cut) EnterTransition.None else shellOf(style, direction).enter

    /** 退场空壳：同上（终点 alpha 仍是 1，不淡出——不做亮度交叉） */
    fun holdExit(style: NavTransitionStyle, direction: NavSlideDirection?): ExitTransition =
        if (style == NavTransitionStyle.Cut) ExitTransition.None else shellOf(style, direction).exit

    private fun shellOf(style: NavTransitionStyle, direction: NavSlideDirection?): Shell = when (style) {
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
        /** 进阅读器（文件夹 → 阅读器，含冷启动落地）的过渡时长（毫秒）：**400ms** */
        const val ENTER_READER_DURATION_MILLIS: Int = 400

        /** 出阅读器（阅读器 → 文件夹）的过渡时长（毫秒）：**300ms**（镜像） */
        const val EXIT_READER_DURATION_MILLIS: Int = 300

        /**
         * 位移比例（整屏宽度的百分数）：**100%**（整屏）。滑动那一屏整个行程、完全出屏（终点不残留影像）。
         */
        const val SLIDE_TRAVEL_PERCENT: Int = 100

        /**
         * "进阅读器"那一档的曲线：`CubicBezier(0.35f, 0.7f, 0.7f, 1f)`——起步快、末尾缓停。
         */
        val INTO_READER_EASING: Easing = CubicBezierEasing(0.35f, 0.7f, 0.7f, 1f)

        /**
         * "出阅读器"那一档的曲线：`CubicBezier(0.4f, 0f, 1f, 1f)`——起步慢、越滑越快、终点不减速。
         */
        val OUT_OF_READER_EASING: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    }
}
