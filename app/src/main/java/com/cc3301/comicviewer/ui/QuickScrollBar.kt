package com.cc3301.comicviewer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.view.quickScrollBarGeometry
import com.cc3301.comicviewer.core.view.quickScrollBarIndexForDrag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 抓取带宽（票 #60）：**12dp**，正好是两档内容自己的右内边距（列表档每行 `padding(horizontal = 16.dp)`、
 * 网格档 `GRID_CONTENT_PADDING = 12.dp`），因此抓取带盖住的是留白，**不压封面与名称**，
 * 也不占用它们的可用宽度（滑条是叠在内容之上的覆盖层）。
 */
internal val QUICK_SCROLL_BAR_STRIP_WIDTH = 12.dp

/** 滑条本体宽度（票面建议 3–5dp） */
internal val QUICK_SCROLL_BAR_WIDTH = 4.dp

/** 滑条离屏幕右缘（票面建议 4–8dp）：4dp 边距 + 4dp 本体正好居中在 [QUICK_SCROLL_BAR_STRIP_WIDTH] 里 */
internal val QUICK_SCROLL_BAR_INSET = 4.dp

/** 滑条最短长度：1000+ 条目时「视口 / 整份列表」比例算出的长度会小到抓不住（纯函数里夹这个下限） */
internal val QUICK_SCROLL_BAR_MIN_LENGTH = 24.dp

/** 静止多久后淡出隐藏（票面 1–2 秒） */
internal const val QUICK_SCROLL_BAR_HIDE_DELAY_MS = 1200L

/** 淡入时长：滚动时「立即出现」要够快 */
internal const val QUICK_SCROLL_BAR_FADE_IN_MS = 100

/** 淡出时长：比淡入慢一点，避免一闪一闪 */
internal const val QUICK_SCROLL_BAR_FADE_OUT_MS = 250

/**
 * 滑条不透明度（单一来源）：与进度条轨道同一量级（[PROGRESS_TRACK_ALPHA]），色值取主题 `onSurface`
 * 乘本值——跟主题走，深浅主题下都与底色成同一比例。
 */
internal const val QUICK_SCROLL_BAR_ALPHA = 0.4f

/**
 * 快速定位滑条要读的滚动状态（票 #60）：`LazyListState`（列表档）与 `LazyGridState`（网格档）暴露同一组
 * 读数与同一个定位动作，这里给两档各一份薄适配（[ListQuickScrollBarState] / [GridQuickScrollBarState]），
 * 因此滑条本体只写一份。与 `BrowserScreen` 里 `isAtTop` 那对扩展属性同一路数（把两个类型的同一份取值语义接到一处）。
 */
internal interface QuickScrollBarState {
    /** 本份列表的条目数（几何的分母；网格档是格子数） */
    val totalItems: Int

    /** 当前可见条目数（几何的分子；网格档是可见格子数，与可见行数同比例，所以长度比例仍然对） */
    val visibleItems: Int

    /** 首个可见条目索引（滑条位置的来源；拖动期间改用本地索引） */
    val firstVisibleItemIndex: Int

    /** 是否正在滚动（出现/隐藏的触发源之一） */
    val isScrollInProgress: Boolean

    /** 定位到条目：拖动中每次移动都调一次 */
    fun scrollToItem(index: Int)
}

/**
 * 列表档的适配。定位走 `requestScrollToItem`：非挂起、在下一帧测量时落地，
 * 因此拖动中每个指针事件都能立刻下单、不会在指针协程里排队积压（也不会被上一个定位卡住）。
 */
internal class ListQuickScrollBarState(private val state: LazyListState) : QuickScrollBarState {
    override val totalItems: Int get() = state.layoutInfo.totalItemsCount
    override val visibleItems: Int get() = state.layoutInfo.visibleItemsInfo.size
    override val firstVisibleItemIndex: Int get() = state.firstVisibleItemIndex
    override val isScrollInProgress: Boolean get() = state.isScrollInProgress
    override fun scrollToItem(index: Int) = state.requestScrollToItem(index)
}

/** 网格档的适配：逐条与列表档同义（`visibleItemsInfo.size` = 可见格子数，与可见行数同比例） */
internal class GridQuickScrollBarState(private val state: LazyGridState) : QuickScrollBarState {
    override val totalItems: Int get() = state.layoutInfo.totalItemsCount
    override val visibleItems: Int get() = state.layoutInfo.visibleItemsInfo.size
    override val firstVisibleItemIndex: Int get() = state.firstVisibleItemIndex
    override val isScrollInProgress: Boolean get() = state.isScrollInProgress
    override fun scrollToItem(index: Int) = state.requestScrollToItem(index)
}

/**
 * 浏览页右缘的快速定位滑条（票 #60，spec 故事 22 的补口）：
 * 1000+ 条目的目录里，反复拖动/滚轮移到列表中部与末尾太慢，滑条给一个一步到位的入口。
 *
 * 几何与「拖动 → 索引」是 `core/view/QuickScrollBar.kt` 里的纯函数（有单测），本文件只做三件事：
 * 把滚动状态翻译成那两个纯函数的输入、把拖动结果落到滚动状态上、管出现与隐藏。
 *
 * **出现/隐藏**：任一滚动读数变化（[QuickScrollBarState.isScrollInProgress] 翻转、首个可见条目索引变化——
 * 滚轮、触摸拖动、鼠标拖动都算）即出现；最后一次动作后静止 [QUICK_SCROLL_BAR_HIDE_DELAY_MS] 淡出；
 * 拖动期间不计时，松手后重新开始计时。列表不足一屏时不显示（纯函数返回 null）：没有可快速定位的余量。
 *
 * **手势分层（票面 AC「拖动滑条期间不触发下拉更新、不打开条目、不改变排序与视图档位」）**：
 * 本滑条由 `BrowserScreen` 挂在 [PullToRefreshArea] **之外的兄弟层**上（同一个 Box 里更靠后的子件）。
 * Compose 的命中选择最上层命中的子件，因此按下滑条时事件根本到不了下拉更新与条目点击——这条 AC
 * 是结构性保证，不是靠优先级调参；排序与视图档位在顶栏菜单里，更不在命中路径上。
 *
 * **已知取舍**：滑条只在可见时占位（透明即整条移除），因此静止隐藏期间右缘 12dp **不吞**点击；
 * 但可见期间（滚动中与滚动停止后的 [QUICK_SCROLL_BAR_HIDE_DELAY_MS] 内）落在这条带子上的点击不会
 * 传到下面的条目上。带子取 12dp = 两档自己的右内边距，所以吞掉的是留白、不是内容。
 * 另外「点一下就跳到该处」不在本票范围内（票面只要求拖动）：按下后要越过触摸斜率才算拖动。
 *
 * @param state 当前档位的滚动状态适配（列表档 / 网格档），由 [BrowserScreen] 按视图档位选一份
 */
@Composable
internal fun QuickScrollBar(state: QuickScrollBarState, modifier: Modifier = Modifier) {
    // 拖动中：本地索引（跟手用，不等滚动状态回读）；null = 没在拖
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    // 滚动/拖动刚发生过（进入隐藏倒计时前为 true）
    var active by remember { mutableStateOf(false) }
    // 动作计数：只作「最后一次动作之后静止了多久」那个倒计时的重启键
    var activity by remember { mutableIntStateOf(0) }
    // 轨道长度（px）：由下面的空盒子量出来（没量到之前不画滑条）
    var trackPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val minLengthPx = with(density) { QUICK_SCROLL_BAR_MIN_LENGTH.toPx() }
    val insetPx = with(density) { QUICK_SCROLL_BAR_INSET.toPx() }

    // 滚动即出现：snapshotFlow 只在值变化时发射，因此每次发射都是一次真实动作；
    // drop(1) 丢掉订阅时立刻发的初值——进屏没滚动时滑条不该闪一下
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .drop(1)
            .collect {
                active = true
                activity++
            }
    }
    // 最后一次动作后静止即淡出；拖动期间不计时（松手后本效果重启，倒计时从头开始）
    LaunchedEffect(activity, dragIndex) {
        if (dragIndex != null) return@LaunchedEffect
        delay(QUICK_SCROLL_BAR_HIDE_DELAY_MS)
        active = false
    }

    val showing = active || dragIndex != null
    val alpha by animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (showing) QUICK_SCROLL_BAR_FADE_IN_MS else QUICK_SCROLL_BAR_FADE_OUT_MS,
        ),
        label = "quickScrollBarAlpha",
    )

    // 拖动期间几何用本地索引：滑条跟着手指走，不等滚动状态回读，跟手无滞后
    val bar = quickScrollBarGeometry(
        totalItems = state.totalItems,
        visibleItems = state.visibleItems,
        firstVisibleItemIndex = dragIndex ?: state.firstVisibleItemIndex,
        trackLengthPx = trackPx,
        minThumbLengthPx = minLengthPx,
    )
    // 手势协程比组合活得久（只按 [state] 重启），因此读数每次都取最新一份：轨道长度首帧才量到、
    // 条目数与滑条长度也会随滚动变化，闭包直接捕获会用到过期值
    val currentIndexAt by rememberUpdatedState(
        newValue = { y: Float ->
            quickScrollBarIndexForDrag(
                positionPx = y,
                totalItems = state.totalItems,
                trackLengthPx = trackPx,
                thumbLengthPx = bar?.thumbLengthPx ?: 0f,
            )
        },
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(QUICK_SCROLL_BAR_STRIP_WIDTH)
            // 只量尺寸、不参与命中：没有 pointerInput 的空盒子不吞事件，因此隐藏期间右缘照常可点
            .onSizeChanged { trackPx = it.height.toFloat() },
    ) {
        val current = bar
        // 完全不可见（alpha 到 0）时整条移除：静止期间右缘 12dp 不吞点击
        if (current != null && alpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state) {
                        quickScrollBarDrag(
                            state = state,
                            indexAt = { y -> currentIndexAt(y) },
                            onDragIndex = { dragIndex = it },
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

/**
 * 滑条拖动（票 #60）：越过触摸斜率后跟手定位，移动中逐次重定位，松手停在原处。
 *
 * 口径：
 * - 越过触摸斜率才算拖动（与内建滚动、票 #69 的鼠标拖动同一口径）：因此「压一下滑条」不带出定位，
 *   本票只要求拖动（点一下跳到该处不在票面范围内）；
 * - 只认主键（同票 #69：鼠标右键/中键拖动不属本手势）；触摸与鼠标左键都走这一条通路；
 * - 拖动期间把本地索引交给 [onDragIndex]（非空 = 正在拖）：滑条因此不隐藏、几何用本地索引；
 * - 每次移动都 `consume()`：本次拖拽已由滑条接管，不留给命中路径上的其它节点；
 * - 拖动状态在 `finally` 里清（手势协程被取消——切档位、排序复位重建了滑条节点——也一定清掉）。
 *
 * @param indexAt 抓手点（容器内 y）→ 目标条目索引，由组合层用当前几何算好（见 `QuickScrollBar`）
 * @param onDragIndex 拖动状态上报（null = 拖动结束）
 */
private suspend fun PointerInputScope.quickScrollBarDrag(
    state: QuickScrollBarState,
    indexAt: (Float) -> Int,
    onDragIndex: (Int?) -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val secondaryMouse = down.type == PointerType.Mouse &&
                (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed)
            if (secondaryMouse) continue
            val downY = down.position.y
            var dragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!dragging) {
                        if (abs(change.position.y - downY) <= touchSlop) continue
                        dragging = true
                    }
                    val index = indexAt(change.position.y)
                    onDragIndex(index)
                    state.scrollToItem(index)
                    change.consume()
                }
            } finally {
                if (dragging) onDragIndex(null)
            }
        }
    }
}
