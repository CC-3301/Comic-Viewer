package com.cc3301.comicviewer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.input.QuickScrollBarEffect
import com.cc3301.comicviewer.core.input.QuickScrollBarGesture
import com.cc3301.comicviewer.core.input.QuickScrollBarInput
import com.cc3301.comicviewer.core.view.quickScrollBarGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/**
 * 抓取带宽（票 #60）：**12dp**，落在两档内容各自的右留白内——列表档每行是
 * `padding(horizontal = 16.dp)`（见 `BrowserScreen` 的 `BrowseRow`）、网格档是
 * `GRID_CONTENT_PADDING = 12.dp`（`LazyVerticalGrid` 的 contentPadding），因此抓取带盖住的是留白，
 * **不压封面与名称**，也不占用它们的可用宽度（滑条是叠在内容之上的覆盖层）。
 */
private val QUICK_SCROLL_BAR_STRIP_WIDTH = 12.dp

/** 滑条本体宽度（票面建议 3–5dp） */
private val QUICK_SCROLL_BAR_WIDTH = 4.dp

/** 滑条离屏幕右缘（票面建议 4–8dp）：4dp 边距 + 4dp 本体正好居中在 [QUICK_SCROLL_BAR_STRIP_WIDTH] 里 */
private val QUICK_SCROLL_BAR_INSET = 4.dp

/** 滑条最短长度：1000+ 条目时「视口 / 整份列表」比例算出的长度会小到抓不住（纯函数里夹这个下限） */
private val QUICK_SCROLL_BAR_MIN_LENGTH = 24.dp

/** 静止多久后淡出隐藏（票面 1–2 秒） */
private const val QUICK_SCROLL_BAR_HIDE_DELAY_MS = 1200L

/** 淡入时长：滚动时「立即出现」要够快 */
private const val QUICK_SCROLL_BAR_FADE_IN_MS = 100

/** 淡出时长：比淡入慢一点，避免一闪一闪 */
private const val QUICK_SCROLL_BAR_FADE_OUT_MS = 250

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
    /** 本份列表的条目数（几何的分母；网格档是格子数） */
    val itemCount: () -> Int,
    /** 当前可见条目数（几何的分子；网格档是可见格子数，与可见行数同比例，所以长度比例仍然对） */
    val visibleItemCount: () -> Int,
    /** 首个可见条目索引（滑条位置的来源；拖动期间改用本地索引） */
    val firstVisibleItemIndex: () -> Int,
    /** 是否正在滚动（出现/隐藏的触发源之一） */
    val isScrollInProgress: () -> Boolean,
    /** 定位到条目：拖动中每次移动都调一次 */
    val scrollToItem: (Int) -> Unit,
    /** 原始位移（正 = 向后滚）：带内的滚轮由滑条代列表滚（见 [QUICK_SCROLL_BAR_WHEEL_PIXELS_PER_UNIT]） */
    val scrollByRawDelta: (Float) -> Unit,
)

/**
 * 列表档的读数与动作。定位走 `requestScrollToItem`：非挂起、在下一帧测量时落地，
 * 因此拖动中每个指针事件都能立刻下单、不会在指针协程里排队积压。
 */
internal fun LazyListState.quickScrollBarState(): QuickScrollBarState = QuickScrollBarState(
    itemCount = { layoutInfo.totalItemsCount },
    visibleItemCount = { layoutInfo.visibleItemsInfo.size },
    firstVisibleItemIndex = { firstVisibleItemIndex },
    isScrollInProgress = { isScrollInProgress },
    scrollToItem = { requestScrollToItem(it) },
    scrollByRawDelta = { dispatchRawDelta(it) },
)

/**
 * 网格档的读数与动作：逐条与列表档同义（`visibleItemsInfo.size` = 可见格子数，与可见行数同比例）。
 * 两条扩展函数的函数体逐字相同是**类型所致**（两个类型没有公共父接口给这些读数），不是漏合并。
 */
internal fun LazyGridState.quickScrollBarState(): QuickScrollBarState = QuickScrollBarState(
    itemCount = { layoutInfo.totalItemsCount },
    visibleItemCount = { layoutInfo.visibleItemsInfo.size },
    firstVisibleItemIndex = { firstVisibleItemIndex },
    isScrollInProgress = { isScrollInProgress },
    scrollToItem = { requestScrollToItem(it) },
    scrollByRawDelta = { dispatchRawDelta(it) },
)

/**
 * 浏览页右缘的快速定位滑条（票 #60，spec 故事 22 的补口）：
 * 1000+ 条目的目录里，反复拖动/滚轮移到列表中部与末尾太慢，滑条给一个一步到位的入口。
 *
 * 几何与「拖动 → 索引」是 `core/view/QuickScrollBar.kt` 里的纯函数，手势判定（只认主键、越斜率才算拖动、
 * 滚轮换算）是 `core/input/QuickScrollBarGesture.kt` 里的状态机（都有单测）；本文件只做接线：
 * 把指针事件翻译成输入、把效果落到界面状态与滚动状态上、管出现与隐藏。
 *
 * **出现/隐藏**：任一滚动读数变化（[QuickScrollBarState.isScrollInProgress] 翻转、首个可见条目索引变化——
 * 滚轮、触摸拖动、鼠标拖动都算）即出现；最后一次动作后静止 [QUICK_SCROLL_BAR_HIDE_DELAY_MS] 淡出；
 * **按住或拖动期间不计时**（松手后重新开始计时，票 #60 r2），列表不足一屏时不显示（纯函数返回 null）。
 *
 * **手势分层（票面 AC「拖动滑条期间不触发下拉更新、不打开条目、不改变排序与视图档位」）**：
 * 本滑条由 `BrowserScreen` 挂在 [PullToRefreshArea] **之外的兄弟层**上（同一个 Box 里更靠后的子件）。
 * Compose 的命中选择最上层命中的子件（`InnerNodeCoordinator.hitTestChild` 在 `sharePointerInputWithSiblings`
 * 为 false 时不再往下找），因此按下滑条时事件根本到不了下拉更新与条目点击——这条 AC 是结构性保证，不是
 * 靠优先级调参；排序与视图档位在顶栏菜单里，更不在命中路径上。
 *
 * **带内手势的取舍（有意，不是缺陷）**：
 * - 抓取带内起手的上下拖动 = **跳到该处**（不是平滑滚动列表）：带子只有 12dp，落在内容自己的右留白内，
 *   这才是滑条该有的语义；票面要求的「拖动滑条即连续快速定位」正是它。
 * - 带内的**鼠标滚轮照常滚动列表**：带子是命中路径最上层，列表收不到落在这里的滚轮，因此由滑条手势
 *   按内建换算代列表滚（票 #60 r2）。
 * - 带内起手的**点击**归滑条：压一下不带出定位（仍要越过触摸斜率），也不传给下面的条目——可见期间
 *   （滚动中与停止后 [QUICK_SCROLL_BAR_HIDE_DELAY_MS] 内）这条 12dp 不传点击；完全隐藏即整条移除，
 *   静止期间右缘照常可点。
 *
 * @param state 当前档位的滚动状态适配（列表档 / 网格档），由 `BrowserScreen` 按视图档位选一份
 */
@Composable
internal fun QuickScrollBar(state: QuickScrollBarState, modifier: Modifier = Modifier) {
    // 拖动中：本地索引（跟手用，不等滚动状态回读）；null = 没在拖
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    // 正按住滑条带（按下到松手之间）：按住期间不隐藏滑条，否则抓取带会被整条移除、拖动丢失（票 #60 r2）
    var held by remember { mutableStateOf(false) }
    // 滚动/拖动刚发生过（进入隐藏倒计时前为 true）
    var active by remember { mutableStateOf(false) }
    // 动作计数：只作「最后一次动作之后静止了多久」那个倒计时的重启键（每次滚动 +1）
    var activityCount by remember { mutableIntStateOf(0) }
    // 轨道长度（px）：由下面的空盒子量出来（没量到之前不画滑条）
    var trackPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val minLengthPx = with(density) { QUICK_SCROLL_BAR_MIN_LENGTH.toPx() }
    val insetPx = with(density) { QUICK_SCROLL_BAR_INSET.toPx() }
    val wheelPx = with(density) { QUICK_SCROLL_BAR_WHEEL_PIXELS_PER_UNIT.toPx() }
    // 手势状态机只依赖平台配置（斜率、滚轮换算），与组合生命周期无关，因此建一次即可
    val gesture = remember(viewConfiguration.touchSlop, wheelPx) {
        QuickScrollBarGesture(touchSlopPx = viewConfiguration.touchSlop, wheelPixelsPerUnit = wheelPx)
    }

    /** 效果 → 界面状态 / 滚动状态（判定在 [QuickScrollBarGesture] 里，这里只落地） */
    fun apply(effects: List<QuickScrollBarEffect>) {
        effects.forEach { effect ->
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
                active = true
                activityCount++
            }
    }
    // 最后一次动作后静止即淡出；按住/拖动期间不计时（松手后本效果重启，倒计时从头开始）
    LaunchedEffect(activityCount, held, dragIndex) {
        if (held || dragIndex != null) return@LaunchedEffect
        val token = activityCount
        delay(QUICK_SCROLL_BAR_HIDE_DELAY_MS)
        // 只在本趟倒计时仍有效时置隐藏：同帧新滚动会把 activityCount 推高、本效果随之重启，
        // 旧协程的恢复若晚于新效果的副作用，直接置 false 会吞掉那一次淡入（票 #60 r2）
        if (activityCount == token) active = false
    }

    val showing = active || held || dragIndex != null
    val alpha by animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (showing) QUICK_SCROLL_BAR_FADE_IN_MS else QUICK_SCROLL_BAR_FADE_OUT_MS,
        ),
        label = "quickScrollBarAlpha",
    )

    // 拖动期间几何用本地索引：滑条跟着手指走，不等滚动状态回读，跟手无滞后
    val itemCount = state.itemCount()
    val bar = quickScrollBarGeometry(
        totalItems = itemCount,
        visibleItems = state.visibleItemCount(),
        firstVisibleItemIndex = dragIndex ?: state.firstVisibleItemIndex(),
        trackLengthPx = trackPx,
        minThumbLengthPx = minLengthPx,
    )
    // 手势协程比组合活得久（只按 [state] 重启），因此几何每次都取最新一份：轨道长度首帧才量到、
    // 条目数与滑条长度也会随滚动变化，闭包直接捕获会用到过期值
    val currentFrame by rememberUpdatedState(
        newValue = QuickScrollBarFrame(
            totalItems = itemCount,
            trackLengthPx = trackPx,
            thumbLengthPx = bar?.thumbLengthPx ?: 0f,
        ),
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(QUICK_SCROLL_BAR_STRIP_WIDTH)
            // 只量尺寸、不参与命中：没有 pointerInput 的空盒子不吞事件，因此隐藏期间右缘照常可点
            .onSizeChanged { trackPx = it.height.toFloat() },
    ) {
        val current = bar
        // 完全不可见（alpha 到 0）时整条移除：静止期间右缘 12dp 不吞点击、也不吞滚轮
        if (current != null && alpha > 0f) {
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
                        // 右缘 4dp + 本体 4dp：正好居中在 12dp 抓取带里（不压内容，也不贴死在屏边）
                        .offset {
                            IntOffset(-insetPx.roundToInt(), current.thumbOffsetPx.roundToInt())
                        }
                        .width(QUICK_SCROLL_BAR_WIDTH)
                        .height(with(density) { current.thumbLengthPx.toDp() })
                        .background(
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = QUICK_SCROLL_BAR_ALPHA * alpha),
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
