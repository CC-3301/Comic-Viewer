package com.cc3301.comicviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.input.PullEffect
import com.cc3301.comicviewer.core.input.PullInput
import com.cc3301.comicviewer.core.input.PullRefreshGesture
import kotlin.math.roundToInt

/**
 * 触发下拉更新的阈值（量的是**指示器位移**，：112dp → 160dp）。
 *
 * 手指实际要拖的距离 = 它 ÷ 阻尼系数（`PullRefreshGesture.DRAG_MULTIPLIER` = 0.5）≈ 320dp，
 * 再加一次 `touchSlop`。`progress` 与刷新期间指示器的停位都由它派生，没有第二份来源；
 * 值由 `ui/PullToRefreshAreaTest.kt` 钉住。
 */
internal val REFRESH_THRESHOLD = 160.dp

/** 指示器直径（与菜单里的其它控件同量级） */
private val REFRESH_INDICATOR_SIZE = 24.dp

/**
 * 下拉更新容器：在列表/网格顶部**按住向下拖动**超过阈值即触发一次更新。
 *
 * **自实现，不用现成下拉组件**（票面硬要求「滚轮不得触发」）：现成组件判定的是嵌套滚动里
 * "子容器滚不动之后剩下的滚动量"，而滚轮在 Compose 里正是以 `NestedScrollSource.UserInput`
 * 的普通滚动增量喂给同一套嵌套滚动的——滚轮滑到顶部继续滚就会被当成下拉（把指示器拽出来，
 * 在更高的 Compose 版本上还会直接触发刷新）。
 *
 * 本实现只认**指针拖拽**：手势在 [PointerEventPass.Initial] 里读取增量（早于内层滚动容器），
 * 越过触摸斜率且列表停在顶部时才开始接管、并 `consume()` 掉拖拽增量（内层因此不会同时滚动）；
 * 滚轮事件（[PointerEventType.Scroll]）只被交给状态机里那个"不改变任何状态"的分支，既不产生位移
 * 也不被消费——内层照常滚动。判定逻辑本身在 `core/input/PullRefreshGesture` 里，有单测。
 *
 * @param atTop 列表是否停在顶部（只有顶部才允许下拉；其余情况整段交回常规滚动）
 * @param refreshing 是否正在刷新（刷新期间**弧线**停在满圈；指示器**位置**停在阈值处，
 *   但刷新期间再往下拖时位置仍跟手指走）
 * @param onRefresh 越过阈值并松手时调用一次（一次拖拽至多一次）
 */
@Composable
internal fun PullToRefreshArea(
    atTop: () -> Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val thresholdPx = with(density) { REFRESH_THRESHOLD.toPx() }
    val gesture = remember(thresholdPx, viewConfiguration.touchSlop) {
        PullRefreshGesture(thresholdPx = thresholdPx, touchSlopPx = viewConfiguration.touchSlop)
    }
    // 手势协程比组合活得久：回调每次重组都要取最新的一份（切换档位/刷新回调变化后不失效）
    val currentAtTop by rememberUpdatedState(atTop)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val indicatorSizePx = with(density) { REFRESH_INDICATOR_SIZE.toPx() }

    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    /** 把状态机的效果落到界面状态上；返回是否消费了本次输入（消费 = 内层不再滚动） */
    fun handle(input: PullInput): Boolean {
        var consumed = false
        gesture.handle(input).forEach { effect ->
            when (effect) {
                is PullEffect.Offset -> {
                    dragOffset = effect.px
                    dragging = true
                    consumed = true
                }
                PullEffect.Reset -> {
                    dragOffset = 0f
                    dragging = false
                }
                PullEffect.Trigger -> currentOnRefresh()
            }
        }
        return consumed
    }

    // 拖动中即时跟随（snap），松手后回弹（spring）；刷新期间位置停在阈值处（再往下拖时仍跟手指走）
    val shown by animateFloatAsState(
        targetValue = when {
            dragging -> dragOffset
            refreshing -> thresholdPx
            else -> 0f
        },
        animationSpec = if (dragging) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "pullOffset",
    )

    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(gesture) {
                awaitPointerEventScope {
                    while (true) {
                        // Initial 传递：先于内层滚动容器看到事件，因此可以决定"这一段归谁"
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var cancelled = false
                        handle(PullInput.Down(down.position.y))
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            // 滚轮/触控板：不产生下拉位移、不消费（内层照常滚动）——票面硬要求
                            if (event.type == PointerEventType.Scroll) {
                                handle(PullInput.Scroll)
                                continue
                            }
                            if (!change.pressed) break
                            val deltaY = change.positionChange().y
                            if (deltaY != 0f && handle(PullInput.Drag(change.position.y, deltaY, currentAtTop()))) {
                                change.consume()
                            }
                        }
                        handle(if (cancelled) PullInput.Cancel else PullInput.Up)
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 内容跟着下拉位移走（回弹时一起回去）
                .offset { IntOffset(0, shown.roundToInt()) },
        ) {
            content()
        }
        if (shown > 0f) {
            CircularProgressIndicator(
                // 进度只由**可观察位移**派生：`shown` 是 `animateFloatAsState` 的状态值——
                // 拖动中它 == `gesture.offsetPx`；在绘制时读它 ⇒ 位移一变这个绘制节点就失效重画。原先读
                // `gesture.progress`（状态机里的**普通字段**，不是 Compose 状态）时什么都不会失效，弧线就
                // 冻在第一次绘制那一刻（维护者报的「像冻住的小点」）。
                // `refreshing` 是**按值传入的组合参数**（它一变，本次组合与这个绘制 lambda 都会带着新值重建），
                // 不是那个普通 getter ⇒ 短路成满圈不依赖「在绘制里读状态」也生效：票面真机 AC
                // 「越过阈值 ⇒ 触发一次刷新、刷新期间停在满圈」。
                progress = { if (refreshing) 1f else (shown / thresholdPx).coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (shown - indicatorSizePx).roundToInt()) }
                    .size(REFRESH_INDICATOR_SIZE),
                strokeWidth = 2.dp,
            )
        }
    }
}
