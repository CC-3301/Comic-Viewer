package com.cc3301.comicviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import com.cc3301.comicviewer.core.input.ReaderTapEffect
import com.cc3301.comicviewer.core.input.ReaderTapGestureState
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 阅读页单击 / 双击的手势口径（票 #129 r4）：**单击要马上有反馈，双击又不能闪**。
 *
 * **为什么不用 `detectTapGestures`**：它只要拿到 `onDoubleTap`，单击就必然被推后到双击等待窗口超时之后才
 * 触发（识别器没法知道你会不会点第二下），窗口由**平台**给（`ViewConfiguration.getDoubleTapTimeoutMillis()`
 * = 300ms）。真机反馈的「点击之后是延时出现」= 300ms 静等 + 出现动画 ≈ 0.4s，账对得上。
 * 缩短这个窗口**只能自己写手势**：`SuspendingPointerInputModifierNodeImpl.getViewConfiguration()` 读的是
 * **LayoutNode 树根**那一份配置（Owner 一次性设下、向整棵子树传播），不是组合局部
 * ⇒ 「在阅读页外面套一层更短的双击超时」不会生效（已查实，别白试）。
 *
 * **另一条路已否决**：单击立即响应、双击第二下再撤销。它会让菜单在双击时**闪一下**（维护者明确否决）。
 * 所以单击**必须等满** [DOUBLE_TAP_WINDOW_MILLIS]——这个常量**就是**单击感知延迟里那段静等，
 * 调它等于调「点下去多久才看见菜单」。它是本票在**界面侧唯一**的产品口径声明：判定全部在
 * `core/input/ReaderTapGestureState.kt`（有单测），本对象只留这一份值 + 下面那个事件翻译。
 *
 * **平台量不写死**：第二下「太早」的下限（`viewConfiguration.doubleTapMinTimeMillis`，Android 默认 40ms）
 * 与窗口一起由 [detectReaderTapGestures] 读出来、传进状态机（与 `ui/MouseDragScroll` 取 `viewConfiguration.touchSlop`
 * 同一做法）。
 */
internal object ReaderTapGesture {

    /** 双击等待窗口（毫秒）：第一下抬起后最多等这么久。**它同时就是单击的感知延迟里那段静等** */
    const val DOUBLE_TAP_WINDOW_MILLIS: Long = 200
}

/**
 * 单击 / 双击识别器：双击等待窗口用 [ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS]（200ms），不用平台默认的 300ms。
 *
 * 调用点在 `ui/ReaderScreen.kt` 的 `pointerInput`（原先那行 `detectTapGestures` 的直接替代）。
 * **判定在 `core/input/ReaderTapGestureState.kt` 里**（有单测），本函数只做三件事：
 * 把指针事件翻译成状态机的输入、把效果翻成 `onTap` / `onDoubleTap`、按窗口给「等第二下」上闸。
 * 事件 → 输入的对应：按下 → `onDown`、第一下抬起 → `onFirstUp`、第二下按下 → `onSecondDown`、
 * 第二下抬起 → `onSecondUp`、窗口过期 → `onWindowExpired`；
 * **被别的手势接管**（`waitForUpOrCancellation()` 返回 null：拖动、双指缩放消费了事件）→ `onCancel`。
 *
 * 真机判据（本机无 Compose UI 测试依赖，UI 层走手动验收）：单击唤出菜单**不再有明显等待**、双击放大**仍灵**；
 * 坏掉的表现是「双击不放大」或「单击没反应」——那就一行切回 `detectTapGestures`。
 */
internal suspend fun PointerInputScope.detectReaderTapGestures(
    onDoubleTap: (Offset) -> Unit,
    onTap: (Offset) -> Unit,
) {
    // 两个时间量在这里取：窗口是本票产品口径，最小间隔是平台量（core 不写死任何一个）
    val gesture = ReaderTapGestureState(
        doubleTapWindowMillis = ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS,
        doubleTapMinIntervalMillis = viewConfiguration.doubleTapMinTimeMillis,
    )

    /** 效果 → 动作（判定在 [ReaderTapGestureState] 里，这里只落地） */
    fun apply(effect: ReaderTapEffect) {
        when (effect) {
            ReaderTapEffect.None, ReaderTapEffect.SecondDownAccepted, ReaderTapEffect.WaitForAnotherDown -> Unit
            is ReaderTapEffect.SingleTap -> onTap(Offset(effect.x, effect.y))
            is ReaderTapEffect.DoubleTap -> onDoubleTap(Offset(effect.x, effect.y))
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        apply(gesture.onDown())
        // ① 等第一下抬起：抬手前被别的手势接管（拖动、双指缩放消费了事件）⇒ 本次不算点按
        val firstUp = waitForUpOrCancellation()
        if (firstUp == null) {
            apply(gesture.onCancel())
            return@awaitEachGesture
        }
        firstUp.consume()
        apply(gesture.onFirstUp(firstUp.position.x, firstUp.position.y, firstUp.uptimeMillis))
        // ② 等第二下：窗口由状态机给（单一出处），超时即判单击；「太早」的按下丢掉这一下、继续等
        // 受理（[ReaderTapEffect.SecondDownAccepted]）才转去等抬手；其余（单击 / 无动作）就地落地并结束本次手势
        val decision = awaitSecondDown(gesture)
        if (decision != ReaderTapEffect.SecondDownAccepted) {
            apply(decision)
            return@awaitEachGesture
        }
        // ③ 等第二下抬起：抬起即双击；被别的手势接管 ⇒ 与上游同一支处理，仍算单击
        val secondUp = waitForUpOrCancellation()
        if (secondUp == null) {
            apply(gesture.onCancel())
        } else {
            secondUp.consume()
            apply(gesture.onSecondUp(secondUp.position.x, secondUp.position.y))
        }
    }
}

/**
 * 等第二下按下：窗口长度取状态机的 [ReaderTapGestureState.secondDownTimeoutMillis]（单一出处），
 * 超时即返回「窗口过期」的效果；「太早」的按下由状态机判为 [ReaderTapEffect.WaitForAnotherDown] ——
 * 丢掉这一下、窗口不重置、继续等（`awaitFirstDown()` 用默认的 `requireUnconsumed = true`，与上游
 * `detectTapGestures` 的 `awaitSecondDown` 同一支）。
 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    gesture: ReaderTapGestureState,
): ReaderTapEffect = withTimeoutOrNull(gesture.secondDownTimeoutMillis) {
    var effect = gesture.onSecondDown(awaitFirstDown().uptimeMillis)
    while (effect == ReaderTapEffect.WaitForAnotherDown) {
        effect = gesture.onSecondDown(awaitFirstDown().uptimeMillis)
    }
    effect
} ?: gesture.onWindowExpired()
