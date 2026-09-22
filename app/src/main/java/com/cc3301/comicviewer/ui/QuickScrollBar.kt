package com.cc3301.comicviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.input.QuickScrollBarEffect
import com.cc3301.comicviewer.core.input.QuickScrollBarGesture
import com.cc3301.comicviewer.core.input.QuickScrollBarInput
import com.cc3301.comicviewer.core.input.countsAsActivity
import com.cc3301.comicviewer.core.view.quickScrollBarGeometry
import com.cc3301.comicviewer.core.view.quickScrollBarItemScrollFraction
import com.cc3301.comicviewer.core.view.quickScrollBarItemVisibleFraction
import com.cc3301.comicviewer.core.view.quickScrollBarVisibleItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/** 滑条本体宽度（票面建议 3–5dp；真机反馈太细后由 4dp 提到 **6dp**，批次 6 保持 6dp） */
internal val QUICK_SCROLL_BAR_WIDTH = 6.dp

/**
 * 滑条最短长度：1000+ 条目时「视口 / 整份列表」比例算出的长度会小到抓不住（纯函数里夹这个下限）。
 *
 * 真机反馈「滑条太短、不容易碰到」（2026-09-21）：1000 条目时比例长度 ≈7dp，旧下限 24dp 兜出来的那根
 * 就是反馈里的「太短」⇒ 下限提到 **64dp**。比例长度大于 64dp 时行为不变，仍按比例算。
 */
internal val QUICK_SCROLL_BAR_MIN_LENGTH = 64.dp

/** 静止多久后淡出隐藏（票面 1–2 秒；批次 6 定版 1.2s）——计时只由「静止」触发，见 [quickScrollBarTimerArmed] */
internal const val QUICK_SCROLL_BAR_HIDE_DELAY_MS = 1200L

/** 淡入时长（批次 6 定版 **120ms**）：出现即「立即」 */
internal const val QUICK_SCROLL_BAR_FADE_IN_MS = 120

/** 淡出时长（批次 6 定版 **200ms**）：静止后只淡出一次 */
internal const val QUICK_SCROLL_BAR_FADE_OUT_MS = 200

/**
 * 「现在该不该跑隐藏倒计时」——批次 6 定版 D7-A 的 AC15/AC16：**只有没滚动也没按住才计时**。
 *
 * 滚动中/按住期间滑条保持可见、且不重排计时；旧实现每次滚动事件都重启倒计时，同时上一次的 1.2s 计时
 * 仍在跑，两条时间线打架 ⇒ 真机上的明灭/抽搐。判定是纯函数，由 [QuickScrollBarTimingTest] 钉住。
 */
internal fun quickScrollBarTimerArmed(scrolling: Boolean, held: Boolean): Boolean = !scrolling && !held

/**
 * 胶囊**中心**距**屏幕**右缘（票 #60 批次 9 r2）：横向定位的唯一口径。
 *
 * r4–r5 曾按「本体居中于『内容右缘 ↔ 屏幕右缘』的空档」定位（离屏缘 = 空档的一半），真机第二次反馈仍是
 * 「依旧偏左」——空档里的居中把本体推到离屏缘更远处，观感上贴向封面。r6 改成**贴屏幕侧固定**（中心距屏缘 5dp），
 * 真机第三次反馈「偏右」⇒ 批次 9 首轮改为 **7dp**。
 *
 * 批次 9 r2 真机反馈「位置仍偏右」（**网格档最明显**）：网格档挨着滑条的是封面右缘，它正好压在 20dp 右留白线上，
 * 于是眼睛拿「离封面多远」与「离屏缘多远」比——7dp 中心时是 10dp 与 4dp，读作「贴边」
 * ⇒ 统一取 **10dp**：本体占屏缘 7–13dp，与封面留 20 − 13 = **7dp**，与离屏缘的 7dp 相等（视觉正中）；
 * 列表档挨着的是标题文字（没有参照线），同一取值读作「刚刚好」。两档同一位置 ⇒ 切档位时滑条不动。
 * 右缘 inset **不参与定位**；当 inset 大于本体占位（13dp）时，本体会落在 inset 区内（可能被系统栏盖住 /
 * 命中被系统接管），由真机验收判。
 */
internal val QUICK_SCROLL_BAR_CENTER_GAP = 10.dp

/**
 * 本体**右缘**距**屏幕**右缘（票 #60 批次 9 r2，dp 域纯函数，由 [QuickScrollBarSizeTest] 钉住）：
 * 由 [QUICK_SCROLL_BAR_CENTER_GAP] 与本体宽推出（10dp − 6dp/2 = 7dp）。
 *
 * 定位基准是**屏幕**右缘，不是空档：滑条容器是满屏 Box 且横向不消费任何 `Scaffold` inset，系统右缘 inset
 * （横屏三键导航把导航栏放右侧、挖孔）撑宽的是**内容侧**的空档，本体不跟着往外挪——这正是真机反馈「偏左」的
 * 修法（r4–r5 用空档算离屏缘，inset 越大越贴封面）。
 *
 * [gap] 只当**夹取上界**用（本体的横向位置必须落在空档里，否则会压到封面/名称）：常规档位下上界宽裕，
 * 取值仍是「中心距屏缘 10dp」算出的 7dp；只有留白被改到比本体占位还窄时才向内夹。
 * 界面侧用它算 `Modifier.offset` 的横向分量，用例拿同一函数钉「中心距屏缘 10dp」。
 */
internal fun quickScrollBarEdgeGap(gap: Dp, barWidth: Dp): Dp =
    (QUICK_SCROLL_BAR_CENTER_GAP - barWidth / 2).coerceAtMost((gap - barWidth).coerceAtLeast(0.dp))

/**
 * 抓取带宽（票 #60 批次 9 r2：两档右留白 20dp 下 **13dp**）= 离屏缘 + 本体宽（见 [quickScrollBarEdgeGap]）：本体整个
 * 落在手势区内（带是命中区，带比本体窄的话最内侧那段压在带外，按下去就落到列表内容）。
 *
 * 13dp 仍小于两档右留白 20dp ⇒ 不侵入右留白、不压封面与名称（由 `QuickScrollBarSizeTest` 钉住）。
 */
internal fun quickScrollBarStripWidth(gap: Dp, barWidth: Dp): Dp =
    quickScrollBarEdgeGap(gap = gap, barWidth = barWidth) + barWidth

/**
 * 单一 alpha 动画驱动的两类输入（票 #60 r4）——屏幕侧的 [QuickScrollBar] 只往这台状态机里喂这两样。
 */
internal enum class QuickScrollBarFadeInput {
    /** 一次活动：滚动读数变化、按下、拖动、带内滚轮 */
    Activity,

    /** 静止计时到点（只在「没滚动也没按住」时才允许产生，见 [quickScrollBarTimerArmed]） */
    IdleSettled,
}

/**
 * 单步推进 alpha 目标（票 #60 r4，纯函数，由 [QuickScrollBarTimingTest] 钉住）：活动 ⇒ 1f、静止结算 ⇒ 0f。
 *
 * 两条不动点是有意的，它们就是「只发生一次动画序列」的判据：
 * - 已可见（1f）时再来活动，目标**不变** ⇒ [androidx.compose.animation.core.Animatable.animateTo]
 *   拿到与当前值相同的目标会直接返回，**不重放淡入**；
 * - 已熄灭（0f）时再来一次结算，目标**不变** ⇒ 不反复淡出。
 *
 * 旧实现（r3）用 `active` 布尔 + `animateFloatAsState` 两条线表达可见性，移动中还会被旧计时熄一次，
 * 短列表里手势多（每次都重新走一趟计时）⇒ 真机上的「抽搐/明灭」。
 */
internal fun quickScrollBarAlphaTarget(current: Float, input: QuickScrollBarFadeInput): Float =
    when (input) {
        QuickScrollBarFadeInput.Activity -> if (current >= 1f) current else 1f
        QuickScrollBarFadeInput.IdleSettled -> if (current <= 0f) current else 0f
    }

/**
 * 单一驱动每一步该做什么（票 #60 r5，纯函数，由 [QuickScrollBarTimingTest] 钉住）——r4 的 P0 就是
 * 「活动只抬 `alphaTarget`、不驱动 `Animatable`」：`showing` 为真但 alpha 恒 0，渲染出隐形吞点击带。
 */
internal enum class QuickScrollBarFadeStep {
    /** 抬目标到 1f **并交给 Animatable 淡入**（已在 1f 时 `animateTo` 直接返回 ⇒ 不重放淡入） */
    Show,

    /** 已经亮着、这一刻没有活动：走满静止计时后熄一次 */
    WaitThenHide,

    /** 没亮着也没有活动：没有可熄灭的东西（也就不会组合出抓取带） */
    Idle,
}

/**
 * 这一步是什么（票 #60 r5，纯函数）：该可见 ⇒ [QuickScrollBarFadeStep.Show]；已亮着且无事发生 ⇒ 等静止
 * 计时到点再熄；否则什么都不做。**判据只看入参**，所以「alpha=0 时来活动」一定是 [QuickScrollBarFadeStep.Show]
 * （⇒ 驱动 Animatable 到 1f），不会是「什么都不做」。
 */
internal fun quickScrollBarFadeStep(showing: Boolean, alpha: Float): QuickScrollBarFadeStep = when {
    showing -> QuickScrollBarFadeStep.Show
    alpha > 0f -> QuickScrollBarFadeStep.WaitThenHide
    else -> QuickScrollBarFadeStep.Idle
}

/**
 * 这一步要动到的 alpha 目标（票 #60 r5，纯函数）：[QuickScrollBarFadeStep.Show] ⇒ 1f、
 * [QuickScrollBarFadeStep.WaitThenHide] ⇒ 0f、[QuickScrollBarFadeStep.Idle] ⇒ 不变（`animateTo` 空转）。
 *
 * 与 [quickScrollBarAlphaTarget] 的分工（票 #112 第 2 条：本文件里「算 alpha 目标」的函数有两份，
 * 它不是「同一个值来源」——两份各服务一层，本轮把 KDoc 改成实情而不是合并）：
 * - 本函数按**这一步**（[QuickScrollBarFadeStep]）给目标，`Animatable.animateTo` 读它；
 * - [quickScrollBarAlphaTarget] 按**输入**（[QuickScrollBarFadeInput]）推进界面侧记忆的 `alphaTarget`，
 *   它才是 [quickScrollBarVisible] 的 `active` 与「倒计时要不要重启」的判据。
 * 两份共享的只有一张两行取值表（活动/Show ⇒ 1f、静止结算/WaitThenHide ⇒ 0f），实现各自独立；
 * 两张表对齐由 `QuickScrollBarTimingTest` 两组用例分别钉住，改一处要两边一起改。
 */
internal fun quickScrollBarFadeTarget(step: QuickScrollBarFadeStep, current: Float): Float = when (step) {
    QuickScrollBarFadeStep.Show -> 1f
    QuickScrollBarFadeStep.WaitThenHide -> 0f
    QuickScrollBarFadeStep.Idle -> current
}

/**
 * 滑条此刻可不可见（票 #60 批次 6 D7-A 的行为口径，纯函数，由 [QuickScrollBarTimingTest] 钉住）：
 * 静止倒计时还没走完（[active]）**或**正在滚动/按住（此时不计时，见 [quickScrollBarTimerArmed]）。
 *
 * 拆出来是为了让「按住期间不隐藏」「滚动中保持可见」「静止走完才淡出」是**可单测的行为**，而不是只能靠
 * 组合里的表达式推导；界面侧就调这一个函数（拖动中的本地索引 `dragIndex != null` 蕴含 `held == true`——
 * [com.cc3301.comicviewer.core.input.QuickScrollBarEffect.Seek] 只在按下后发出、`Hold(false)` 才清——
 * 因此不需要再列一项）。
 */
internal fun quickScrollBarVisible(active: Boolean, scrolling: Boolean, held: Boolean): Boolean =
    active || !quickScrollBarTimerArmed(scrolling = scrolling, held = held)

/**
 * 一个滚轮单位对应的列表位移（票 #60 r2）：与 foundation 内建滚轮换算里的 64dp 常量同值
 * （`AndroidCompiledScrollable` 的 `AndroidConfig.calculateMouseWheelScroll`：`Σ scrollDelta × -(64.dp)`）。
 * 带内的滚轮事件到不了列表（滑条带是命中路径最上层），由滑条手势按同一常量代列表滚动。
 */
private val QUICK_SCROLL_BAR_WHEEL_PIXELS_PER_UNIT = 64.dp

/**
 * 滑条不透明度（单一来源）：与进度条轨道同一量级（[PROGRESS_TRACK_ALPHA]），色值取主题 `onSurface`
 * 乘本值——跟主题走，深浅主题下都与底色成同一比例。
 */
private const val QUICK_SCROLL_BAR_ALPHA = 0.4f

/**
 * 快速定位滑条要读的滚动状态（票 #60）：`LazyListState`（列表档）与 `LazyGridState`（网格档）的读数与动作
 * 逐条同义，因此**只写一个适配器**、由两档各自的扩展函数（[quickScrollBarState]）传入取值与动作
 * （票 #60 r2 合并了原先两份逐字相同的适配类）。
 *
 * 这一层值得包：滑条本体要跨六项读数 + 两项动作（比 `BrowserScreen` 里那对一行取值的 `isAtTop` 扩展属性重），
 * 包一层才写得出「一份」滑条；两个类型又互不相关（`LazyListState` / `LazyGridState` 没有提供这些读数的公共父类型）。
 */
internal class QuickScrollBarState(
    /** 本份列表的条目数（长度比例的分母；网格档是格子数。位置与拖动按**行**算，见 [itemsPerRow]） */
    val itemCount: () -> Int,
    /**
     * **连续**可见条目数（几何的分子；网格档是可见格子数，与可见行数同比例，所以长度比例仍然对）：
     * 由 `layoutInfo.visibleItemsInfo` 的露出比例求和得到
     * （[com.cc3301.comicviewer.core.view.quickScrollBarVisibleItems]），**不是** `visibleItemsInfo.size`
     * 那个整数计数——整数计数滚动中会 ±1，滑条长度因此抖（票 #60 r6）。
     */
    val visibleItemCount: () -> Float,
    /** 首个可见条目索引（**行首**那个，见 [itemsPerRow]；滑条位置的来源；拖动期间改用本地索引） */
    val firstVisibleItemIndex: () -> Int,
    /**
     * 首个可见条目**内部**已滚过的比例（0..1，网格档下就是行内比例）：让滑条位置在两条之间也连续推进
     * （见 [com.cc3301.comicviewer.core.view.quickScrollBarItemScrollFraction]）
     */
    val firstVisibleItemScrollFraction: () -> Float,
    /** 是否正在滚动（出现/隐藏的触发源之一） */
    val isScrollInProgress: () -> Boolean,
    /**
     * 本档每行的条目数（网格档 = 档位列数、列表档 = 1）：滑条进度与拖动定位都按**行**算，
     * 见 [com.cc3301.comicviewer.core.view.quickScrollBarProgress]（票 #60 批次 9 r2 的跳格修法）。
     * 列数随视图档位变化，因此与这里其它读数一样取 lambda、每次现取当前值。
     */
    val itemsPerRow: () -> Int,
    /** 定位到条目：拖动中每次移动都调一次 */
    val scrollToItem: (Int) -> Unit,
    /** 原始位移（正 = 向后滚）：带内的滚轮由滑条代列表滚（见 [QUICK_SCROLL_BAR_WHEEL_PIXELS_PER_UNIT]） */
    val scrollByRawDelta: (Float) -> Unit,
)

/**
 * 列表档的读数与动作。定位走 `requestScrollToItem`：非挂起、在下一帧测量时落地，
 * 因此拖动中每个指针事件都能立刻下单、不会在指针协程里排队积压。
 */
internal fun LazyListState.quickScrollBarState(
    /**
     * 分母（本份列表的条目数）。默认取 `layoutInfo.totalItemsCount`（= 这一屏 Lazy 列表的行数）；
     * **按需加载**的浏览列表传入 [com.cc3301.comicviewer.ui.BrowsePageLoader.sliderItemCount]
     * （票 #119 修复轮口径：已加载条数 + 截断提示/尾部触发件那两行），与列表行坐标保持同一套。
     */
    itemCount: (() -> Int)? = null,
): QuickScrollBarState = QuickScrollBarState(
    itemCount = itemCount ?: { layoutInfo.totalItemsCount },
    visibleItemCount = { layoutInfo.continuousVisibleItemCount() },
    firstVisibleItemIndex = { firstVisibleItemIndex },
    firstVisibleItemScrollFraction = {
        quickScrollBarItemScrollFraction(
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            firstVisibleItemExtentPx = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0,
        )
    },
    isScrollInProgress = { isScrollInProgress },
    // 列表档一行 = 一条
    itemsPerRow = { 1 },
    scrollToItem = { requestScrollToItem(it) },
    scrollByRawDelta = { dispatchRawDelta(it) },
)

/**
 * 网格档的读数与动作：逐条与列表档同义（[continuousVisibleItemCount] 按可见格子的露出比例求和，与可见行数同比例）。
 * 两条扩展函数的函数体逐字相同是**类型所致**（两个类型没有公共父接口给这些读数），不是漏合并。
 */
/**
 * 网格档的读数与动作：逐条与列表档同义（[continuousVisibleItemCount] 按可见格子的露出比例求和，与可见行数同比例）。
 * 两条扩展函数的函数体逐字相同是**类型所致**（两个类型没有公共父接口给这些读数），不是漏合并。
 *
 * [itemsPerRow] 取当前档位列数（`GridCells.Fixed(columns)`）：网格档的进度按**行**算，见
 * [com.cc3301.comicviewer.core.view.quickScrollBarProgress]。
 */
internal fun LazyGridState.quickScrollBarState(
    itemsPerRow: () -> Int,
    /** 同列表档：默认 `layoutInfo.totalItemsCount`，按需加载的层传已加载条数 + 附加行 */
    itemCount: (() -> Int)? = null,
): QuickScrollBarState = QuickScrollBarState(
    itemCount = itemCount ?: { layoutInfo.totalItemsCount },
    visibleItemCount = { layoutInfo.continuousVisibleItemCount() },
    firstVisibleItemIndex = { firstVisibleItemIndex },
    firstVisibleItemScrollFraction = {
        quickScrollBarItemScrollFraction(
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            // 网格档的格子同宽同高（一行的行高 = 格子高）⇒ 这个比例就是**行内**已滚过的比例
            firstVisibleItemExtentPx = layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 0,
        )
    },
    isScrollInProgress = { isScrollInProgress },
    itemsPerRow = itemsPerRow,
    scrollToItem = { requestScrollToItem(it) },
    scrollByRawDelta = { dispatchRawDelta(it) },
)

/**
 * 连续可见条目数（票 #60 r6，两档共用同一份换算）：把每个可见条目的露出比例加起来
 * （[com.cc3301.comicviewer.core.view.quickScrollBarItemVisibleFraction]）。条目高为 0（首帧未布局）时
 * 各加数为 0 ⇒ 可见数 0；首帧轨道也还没量到长度（`trackLengthPx <= 0`）⇒ 几何返回 null，与旧行为一致。
 */
private fun LazyListLayoutInfo.continuousVisibleItemCount(): Float = quickScrollBarVisibleItems(
    visibleItemsInfo.map { item ->
        quickScrollBarItemVisibleFraction(
            itemOffsetPx = item.offset.toFloat(),
            // 列表档的 `LazyListItemInfo.size` 就是主轴尺寸（px），本页主轴 = 纵向 ⇒ 恰好是条目高
            itemExtentPx = item.size.toFloat(),
            viewportStartPx = viewportStartOffset.toFloat(),
            viewportEndPx = viewportEndOffset.toFloat(),
        )
    },
)

/** 网格档同上（字段同义，类型不同，因此写两份） */
private fun LazyGridLayoutInfo.continuousVisibleItemCount(): Float = quickScrollBarVisibleItems(
    visibleItemsInfo.map { item ->
        quickScrollBarItemVisibleFraction(
            itemOffsetPx = item.offset.y.toFloat(),
            itemExtentPx = item.size.height.toFloat(),
            viewportStartPx = viewportStartOffset.toFloat(),
            viewportEndPx = viewportEndOffset.toFloat(),
        )
    },
)

/**
 * 浏览页右缘的快速定位滑条（票 #60，spec 故事 22 的补口）：
 * 1000+ 条目的目录里，反复拖动/滚轮移到列表中部与末尾太慢，滑条给一个一步到位的入口。
 *
 * 几何与「拖动 → 索引」是 `core/view/QuickScrollBar.kt` 里的纯函数，手势判定（只认主键、越斜率才算拖动、
 * 滚轮换算）是 `core/input/QuickScrollBarGesture.kt` 里的状态机（都有单测）；本文件只做接线：
 * 把指针事件翻译成输入、把效果落到界面状态与滚动状态上、管出现与隐藏。
 *
 * **出现/隐藏（批次 6 D7-A + r4 的单一驱动）**：任一滚动读数变化（[QuickScrollBarState.isScrollInProgress]
 * 翻转、首个可见条目索引变化——滚轮、触摸拖动、鼠标拖动都算）即出现，淡入 [QUICK_SCROLL_BAR_FADE_IN_MS]；
 * **滚动中与按住/拖动期间一直可见且不重排计时、也不重放淡入**（[quickScrollBarTimerArmed] 为 false 的那段时间，
 * 目标恒为 1f ⇒ [Animatable.animateTo] 拿到相同目标直接返回）；静止
 * [QUICK_SCROLL_BAR_HIDE_DELAY_MS] 后**只淡出一次** [QUICK_SCROLL_BAR_FADE_OUT_MS]；
 * 列表不足一屏时不显示（纯函数返回 null）。
 *
 * **横向位置（批次 9 r2）**：本体**贴屏幕侧固定**——胶囊中心距**屏幕**右缘
 * [QUICK_SCROLL_BAR_CENTER_GAP]（10dp），本体右缘因此离屏缘 [quickScrollBarEdgeGap]（两档右留白
 * 20dp 下 7dp）。不再拿空档（内容右缘↔屏幕右缘）做定位：r4–r5 的「居中于空档」在空档被系统右缘 inset 撑宽时会把本体
 * 推到离屏缘更远处（离封面更近），真机上仍读作「偏左」。空档里除了本体剩下的都是不压内容的空隙：
 * 本体占屏缘 7–13dp、右留白 20dp ⇒ 与封面之间留出 7dp（网格档下「离封面 7dp / 离屏缘 7dp」视觉正中）。
 * 系统右缘 inset **不参与定位**，但它大于本体占位
 * （13dp）时本体会落在 inset 区内（可能被系统栏盖住 / 命中被系统接管），由真机验收判。
 *
 * **手势分层（票面 AC「拖动滑条期间不触发下拉更新、不打开条目、不改变排序与视图档位」）**：
 * 本滑条由 `BrowserScreen` 挂在 [PullToRefreshArea] **之外的兄弟层**上（同一个 Box 里更靠后的子件）。
 * Compose 的命中选择最上层命中的子件（`InnerNodeCoordinator.hitTestChild` 在 `sharePointerInputWithSiblings`
 * 为 false 时不再往下找），因此按下滑条时事件根本到不了下拉更新与条目点击——这条 AC 是结构性保证，不是
 * 靠优先级调参；排序与视图档位在顶栏菜单里，更不在命中路径上。
 *
 * **带内手势的取舍（有意，不是缺陷）**：
 * - 抓取带内起手的上下拖动 = **跳到该处**（不是平滑滚动列表）：带子只有 [quickScrollBarStripWidth]（默认 13dp），
 *   落在内容自己的右留白内，这才是滑条该有的语义；票面要求的「拖动滑条即连续快速定位」正是它。
 * - 带内的**鼠标滚轮照常滚动列表**：带子是命中路径最上层，列表收不到落在这里的滚轮，因此由滑条手势
 *   按内建换算代列表滚（票 #60 r2）。
 * - 带内起手的**点击**归滑条：压一下不带出定位（仍要越过触摸斜率），也不传给下面的条目——可见期间
 *   （滚动中与停止后 [QUICK_SCROLL_BAR_HIDE_DELAY_MS] 内）这条 13dp 不传点击；完全隐藏即整条移除，
 *   静止期间右缘照常可点。
 *
 * @param state 当前档位的滚动状态适配（列表档 / 网格档），由 `BrowserScreen` 按视图档位选一份
 * @param endGap 内容右缘到**屏幕**右缘的横向空档（= `Scaffold` 右缘 inset + 内容右留白）：只当本体位置的
 *   **夹取上界**（本体必须落在空档里，不压内容），定位基准仍是屏幕右缘，见 [quickScrollBarEdgeGap]
 */
@Composable
internal fun QuickScrollBar(state: QuickScrollBarState, endGap: Dp, modifier: Modifier = Modifier) {
    // 拖动中：本地索引（跟手用，不等滚动状态回读）；null = 没在拖
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    // 正按住滑条带（按下到松手之间）：按住期间不隐藏滑条，否则抓取带会被整条移除、拖动丢失（票 #60 r2）
    var held by remember { mutableStateOf(false) }
    // alpha 目标（0f / 1f）：单一驱动里唯一被推进的量，见 [quickScrollBarAlphaTarget]
    var alphaTarget by remember { mutableFloatStateOf(0f) }
    // 动作计数：只作「最后一次动作之后静止了多久」那个倒计时的重启键（每次滚动 +1）
    var activityCount by remember { mutableIntStateOf(0) }
    // 轨道长度（px）：由下面的空盒子量出来（没量到之前不画滑条）
    var trackPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val minLengthPx = with(density) { QUICK_SCROLL_BAR_MIN_LENGTH.toPx() }
    // 本体右缘离屏缘（批次 9 r2：贴屏幕侧固定 7dp；空档只当夹取上界）、抓取带 = 离屏缘 + 本体宽
    // （dp 域函数直接算，不做 px 往返）
    val edgeGapPx = with(density) {
        quickScrollBarEdgeGap(gap = endGap, barWidth = QUICK_SCROLL_BAR_WIDTH).toPx()
    }
    val stripWidth = quickScrollBarStripWidth(gap = endGap, barWidth = QUICK_SCROLL_BAR_WIDTH)
    val wheelPx = with(density) { QUICK_SCROLL_BAR_WHEEL_PIXELS_PER_UNIT.toPx() }
    // 手势状态机只依赖平台配置（斜率、滚轮换算），与组合生命周期无关，因此建一次即可
    val gesture = remember(viewConfiguration.touchSlop, wheelPx) {
        QuickScrollBarGesture(touchSlopPx = viewConfiguration.touchSlop, wheelPixelsPerUnit = wheelPx)
    }

    /** 效果 → 界面状态 / 滚动状态（判定在 [QuickScrollBarGesture] 里，这里只落地） */
    fun apply(effects: List<QuickScrollBarEffect>) {
        effects.forEach { effect ->
            // 每次「动作」都重启出现/隐藏倒计时。带内滚轮（[QuickScrollBarEffect.ScrollBy]）必须走这一支：
            // 它不一定改变上方订阅的读取值（首个可见条目索引），否则连续带内滚轮时滑条会在滚动中淡出
            // （票 #60 r2 评审 spec P2-2；判定在 [countsAsActivity] 里、由单测钉住）
            if (effect.countsAsActivity()) {
                alphaTarget = quickScrollBarAlphaTarget(alphaTarget, QuickScrollBarFadeInput.Activity)
                activityCount++
            }
            when (effect) {
                is QuickScrollBarEffect.Hold -> {
                    held = effect.holding
                    if (!effect.holding) dragIndex = null
                }
                is QuickScrollBarEffect.Seek -> {
                    dragIndex = effect.index
                    state.scrollToItem(effect.index)
                }
                is QuickScrollBarEffect.ScrollBy -> state.scrollByRawDelta(effect.deltaPx)
            }
        }
    }

    // 滚动即出现：snapshotFlow 只在值变化时发射，因此每次发射都是一次真实动作；
    // drop(1) 丢掉订阅时立刻发的初值——进屏没滚动时滑条不该闪一下
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress() to state.firstVisibleItemIndex() }
            .drop(1)
            .collect {
                alphaTarget = quickScrollBarAlphaTarget(alphaTarget, QuickScrollBarFadeInput.Activity)
                activityCount++
            }
    }
    // 滚动中（滚轮/触摸拖动/鼠标拖动都算）：读的就是滚动状态本身，不另存一份
    val scrolling = state.isScrollInProgress()
    // 滚动中或按住中：这两段不排静止计时（见 [quickScrollBarTimerArmed]），且进 key——一翻转就重算一步
    val busy = !quickScrollBarTimerArmed(scrolling = scrolling, held = held)
    // **单一 alpha 动画驱动**（票 #60 r4–r5）：一个 Animatable。每一步先由 [quickScrollBarFadeStep] 判定
    // （该可见 ⇒ [QuickScrollBarFadeStep.Show]、已亮着且没事发生 ⇒ 等静止计时、否则什么都不做），
    // 再由 [quickScrollBarFadeTarget] 给出目标交给 `animateTo`——「抬起来」与「熄灭」是**同一台状态机的两支**：
    // 只要目标被活动抬到 1f 就必须驱动 Animatable（r4 的 P0 是抬目标的那两支不驱动，alpha 恒 0，
    // `showing` 为真却渲染出隐形 10dp 吞点击带）。已在 1f 时 `animateTo` 空转 ⇒ 不重放淡入、不反复淡出。
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(busy, activityCount) {
        val step = quickScrollBarFadeStep(
            showing = quickScrollBarVisible(
                active = alphaTarget > 0f,
                scrolling = state.isScrollInProgress(),
                held = held,
            ),
            alpha = alpha.value,
        )
        if (step == QuickScrollBarFadeStep.Idle) return@LaunchedEffect
        if (step == QuickScrollBarFadeStep.Show) {
            alphaTarget = quickScrollBarAlphaTarget(alphaTarget, QuickScrollBarFadeInput.Activity)
            alpha.animateTo(
                quickScrollBarFadeTarget(step = step, current = alpha.value),
                tween(QUICK_SCROLL_BAR_FADE_IN_MS),
            )
        }
        // 静止计时（Show 之后也走这里）：滚动/按住期间不排；期间任何一个新动作都会重启本趟（key 含二者）
        val token = activityCount
        delay(QUICK_SCROLL_BAR_HIDE_DELAY_MS)
        if (activityCount != token) return@LaunchedEffect
        // 竞态兜底：held 与滚动读数在协程里现取，不是组合期快照
        if (!quickScrollBarTimerArmed(scrolling = state.isScrollInProgress(), held = held)) {
            return@LaunchedEffect
        }
        alphaTarget = quickScrollBarAlphaTarget(alphaTarget, QuickScrollBarFadeInput.IdleSettled)
        alpha.animateTo(
            quickScrollBarFadeTarget(step = QuickScrollBarFadeStep.WaitThenHide, current = alpha.value),
            tween(QUICK_SCROLL_BAR_FADE_OUT_MS),
        )
    }

    // 拖动期间几何用本地索引：滑条跟着手指走，不等滚动状态回读，跟手无滞后
    val itemCount = state.itemCount()
    val bar = quickScrollBarGeometry(
        totalItems = itemCount,
        // 连续可见条目数（r6）：整数计数在滚动中会 ±1，胶囊长度因此抖（见 [quickScrollBarVisibleItems]）
        visibleItems = state.visibleItemCount(),
        firstVisibleItemIndex = dragIndex ?: state.firstVisibleItemIndex(),
        // 屏内已滚过比例（r6）：让位置在两条之间也连续；拖动期间位置由本地索引给出，不叠这个比例
        firstVisibleItemScrollFraction = if (dragIndex != null) 0f else state.firstVisibleItemScrollFraction(),
        trackLengthPx = trackPx,
        minThumbLengthPx = minLengthPx,
        // 进度按**行**算（批次 9 r2）：网格档一档多格，按条目算会让行内速度减半、每行边界补跳一格
        itemsPerRow = state.itemsPerRow(),
    )
    // 手势协程比组合活得久（只按 [state] 重启），因此几何每次都取最新一份：轨道长度首帧才量到、
    // 条目数与滑条长度也会随滚动变化，闭包直接捕获会用到过期值
    val currentFrame by rememberUpdatedState(
        newValue = QuickScrollBarFrame(
            totalItems = itemCount,
            trackLengthPx = trackPx,
            thumbLengthPx = bar?.thumbLengthPx ?: 0f,
            itemsPerRow = state.itemsPerRow(),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(stripWidth)
            // 只量尺寸、不参与命中：没有 pointerInput 的空盒子不吞事件，因此隐藏期间右缘照常可点
            .onSizeChanged { trackPx = it.height.toFloat() },
    ) {
        val current = bar
        // 完全不可见（alpha 到 0）时整条移除：不可见就不组合抓取带（免得留下看不见却吞点击的右缘带子），
        // 静止期间右缘照常可点、可滚（r5：r4 把 `showing` 也当挂载条件，正是那个隐形带的来源）
        if (current != null && alpha.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state, gesture) {
                        quickScrollBarGestures(
                            gesture = gesture,
                            frame = { currentFrame },
                            onEffects = { apply(it) },
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // 贴屏幕侧固定（批次 9 r2）：本体右缘离屏缘 7dp（胶囊中心因此离屏缘 10dp）；空档只当夹取上界
                        .offset {
                            IntOffset(-edgeGapPx.roundToInt(), current.thumbOffsetPx.roundToInt())
                        }
                        .width(QUICK_SCROLL_BAR_WIDTH)
                        .height(with(density) { current.thumbLengthPx.toDp() })
                        .background(
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = QUICK_SCROLL_BAR_ALPHA * alpha.value),
                            shape = RoundedCornerShape(QUICK_SCROLL_BAR_WIDTH / 2),
                        ),
                )
            }
        }
    }
}

/** 拖动判定要的当前几何：每次移动取最新一份交给手势状态机（见 [QuickScrollBarInput.Drag]） */
private class QuickScrollBarFrame(
    val totalItems: Int,
    val trackLengthPx: Float,
    val thumbLengthPx: Float,
    /** 本档每行的条目数：拖动定位按**行**算（网格档 = 档位列数、列表档 = 1） */
    val itemsPerRow: Int,
)

/**
 * 指针事件 → [QuickScrollBarGesture] 输入、效果 → 界面状态（票 #60；判定全在 `core/input`，这里只接线）。
 *
 * 外层循环用 [AwaitPointerEventScope.awaitPointerEvent] 而不是 `awaitFirstDown`：**滚轮事件不带按下**
 * （鼠标只是悬停在带内），只有「等任意事件」才收得到；带内的滚轮必须由本手势代列表算位移
 * （滑条带是命中路径最上层，列表收不到落在这条带里的滚轮），否则带内滚轮会整个失效（票 #60 r2）。
 *
 * @param frame 当前几何（每次移动取一次，避免用过期几何算索引）
 * @param onEffects 落地回调（界面侧写界面状态与滚动状态）
 */
private suspend fun PointerInputScope.quickScrollBarGestures(
    gesture: QuickScrollBarGesture,
    frame: () -> QuickScrollBarFrame,
    onEffects: (List<QuickScrollBarEffect>) -> Unit,
) {
    awaitPointerEventScope {
        try {
            while (true) {
                val event = awaitPointerEvent()
                // 滚轮：不带按下也能来（悬停在带内），因此在外层循环就得处理
                if (handleWheel(event, gesture, onEffects)) continue
                // 只认「新按下」的指针：悬停移动、已按下指针的移动都不算
                val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                val secondaryMouse = down.type == PointerType.Mouse &&
                    (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed)
                onEffects(gesture.handle(QuickScrollBarInput.Down(down.position.y, secondaryMouse)))
                var cancelled = false
                while (true) {
                    val next = awaitPointerEvent()
                    // 拖动中滚轮照样成立（照常滚动列表）
                    if (handleWheel(next, gesture, onEffects)) continue
                    val change = next.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    if (!change.pressed) break
                    val geometry = frame()
                    onEffects(
                        gesture.handle(
                            QuickScrollBarInput.Drag(
                                y = change.position.y,
                                totalItems = geometry.totalItems,
                                trackLengthPx = geometry.trackLengthPx,
                                thumbLengthPx = geometry.thumbLengthPx,
                                itemsPerRow = geometry.itemsPerRow,
                            ),
                        ),
                    )
                    // 本次拖拽已由滑条接管：标记已处理，不留给命中路径上的其它节点
                    change.consume()
                }
                onEffects(gesture.handle(if (cancelled) QuickScrollBarInput.Cancel else QuickScrollBarInput.Up))
            }
        } finally {
            // 手势协程被取消（切档位、排序复位重建了滑条节点）也要清掉按住/拖动状态，
            // 否则滑条会一直显示、或卡在拖动位置（票 #60 r2）
            onEffects(listOf(QuickScrollBarEffect.Hold(false)))
        }
    }
}

/**
 * 带内滚轮（票 #60 r2）：把 `scrollDelta` 交给手势状态机换算成列表的原始位移（界面侧据此滚动列表），
 * 并标记本次事件已处理（与内建滚动同口径：内建也是消费掉滚轮事件）。
 *
 * @return 是否是一次滚轮事件（调用方据此 continue）
 */
private fun AwaitPointerEventScope.handleWheel(
    event: PointerEvent,
    gesture: QuickScrollBarGesture,
    onEffects: (List<QuickScrollBarEffect>) -> Unit,
): Boolean {
    if (event.type != PointerEventType.Scroll) return false
    event.changes.forEach { change ->
        onEffects(gesture.handle(QuickScrollBarInput.Scroll(change.scrollDelta.y)))
        change.consume()
    }
    return true
}
