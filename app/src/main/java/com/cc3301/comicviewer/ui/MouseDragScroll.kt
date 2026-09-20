package com.cc3301.comicviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.cc3301.comicviewer.core.input.MouseDragEffect
import com.cc3301.comicviewer.core.input.MouseDragInput
import com.cc3301.comicviewer.core.input.MouseDragScrollGesture
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 鼠标左键按住拖动 = 纵向滚动（票 #69，spec 故事 22/35 的补口）。
 *
 * **为什么需要它**：Compose 1.7.2 的 `Modifier.scrollable`（`LazyColumn` / `LazyVerticalGrid` 的地基）
 * 明确拒绝鼠标源拖动——`canDrag` 判定就是 `change.type != PointerType.Mouse`，所以滚轮能滚、
 * 鼠标按住拖动在浏览页与阅读器里都不产生滚动。判定逻辑在 `core/input/MouseDragScrollGesture`（有单测），
 * 本修饰符只做三件事：把指针事件翻译成手势输入、把效果落到 [state] 上、松手按速度做惯性减速。
 *
 * 分层（票 #69 验收）：
 * - 只认 [PointerType.Mouse] 且不是右键/中键（票面 Out of Scope：右键拖动与中键不属本票，
 *   不报按键信息时（buttonState 为空）仍当作左键，不至于在个别鼠标上整段失效）；
 *   触摸/触控笔整段不介入（内建滚动照旧）。
 * - 在 [PointerEventPass.Initial] 里读事件，因此**外层的下拉更新先看到、先消费**（`ui/PullToRefreshArea`
 *   在列表顶部向下拖动时消费掉增量），本修饰符看到已消费的增量即整段让位——不会「又刷新又滚动」。
 * - 越过触摸斜率才算拖动（只看纵向累计位移，与内建滚动同一口径）；因此阅读器的左键单击仍走触摸区域。
 *
 * @param state 该容器自己的滚动状态（`LazyListState` / `LazyGridState` 都是 [ScrollableState]）；
 *   滚动量与内建滚动同口径（正 = 向后滚）。
 */
internal fun Modifier.mouseDragScroll(state: ScrollableState): Modifier = pointerInput(state) {
    val gesture = MouseDragScrollGesture(touchSlopPx = viewConfiguration.touchSlop)
    val tracker = VelocityTracker()
    val decay = splineBasedDecay<Float>(this)
    coroutineScope {
        var flingJob: Job? = null
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // 按下即「抓住」内容：上一次松手留下的惯性就地停住（与内建滚动同一手感）
                flingJob?.cancel()
                gesture.handle(
                    MouseDragInput.Down(
                        y = down.position.y,
                        fromMousePrimary = down.type == PointerType.Mouse &&
                            !currentEvent.buttons.isSecondaryPressed &&
                            !currentEvent.buttons.isTertiaryPressed,
                    ),
                )
                tracker.resetTracking()
                tracker.addPosition(down.uptimeMillis, down.position)
                var cancelled = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    if (!change.pressed) break
                    // 滚轮/触控板：不属本手势，也不得被本修饰符消费（内层照常滚动）
                    if (event.type == PointerEventType.Scroll) continue
                    tracker.addPosition(change.uptimeMillis, change.position)
                    var takenOver = false
                    gesture.handle(
                        MouseDragInput.Drag(
                            y = change.position.y,
                            consumed = change.isConsumed,
                        ),
                    ).forEach { effect ->
                        when (effect) {
                            is MouseDragEffect.ScrollBy -> {
                                if (effect.deltaPx != 0f) {
                                    state.dispatchRawDelta(effect.deltaPx)
                                    takenOver = true
                                }
                            }
                            // 惯性只在抬手时给出
                            is MouseDragEffect.Fling -> Unit
                        }
                    }
                    if (takenOver) change.consume()
                }
                val velocityY = if (cancelled) 0f else tracker.calculateVelocity().y
                gesture.handle(if (cancelled) MouseDragInput.Cancel else MouseDragInput.Up(velocityY))
                    .forEach { effect ->
                        if (effect is MouseDragEffect.Fling) {
                            flingJob?.cancel()
                            flingJob = launch { flingBy(state, effect.velocityPxPerSec, decay) }
                        }
                    }
            }
        }
    }
}

/** 松手惯性：按 [velocityPxPerSec]（正 = 向后滚）减速滚动；撞到边界后容器吃不掉增量，动画自然收尾 */
private suspend fun flingBy(
    state: ScrollableState,
    velocityPxPerSec: Float,
    decay: DecayAnimationSpec<Float>,
) {
    var last = 0f
    Animatable(0f).animateDecay(velocityPxPerSec, decay) {
        val delta = value - last
        last = value
        state.dispatchRawDelta(delta)
    }
}
