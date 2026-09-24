package com.cc3301.comicviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
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
 * 调它等于调「点下去多久才看见菜单」。
 *
 * **行为语义与 `detectTapGestures` 逐支对齐**（本文件是它的子集：无长按、无 `onPress`）：
 * - 抬手前被别的手势接管（拖动、双指缩放消费了事件）⇒ 本次不算点按；
 * - 窗口内没有第二下 ⇒ 单击，位置取**第一下抬起**处；
 * - 窗口内有第二下 ⇒ 双击，位置取**第二下抬起**处，且**不**再发单击；
 * - 第二下被别的手势接管（如中途变成双指缩放）⇒ 按上游同一支处理，仍算单击。
 *
 * **本机没有自动化测试能跑手势本身**（仓库无 Compose UI 测试依赖，SPEC 的 Testing Decisions 把 UI 层交给
 * 手动验收）；本文件钉得住的是下面三个常量与纯函数，真机判据写在 `ReaderTapGestureTest` 的类 KDoc 里。
 */
internal object ReaderTapGesture {

    /** 双击等待窗口（毫秒）：第一下抬起后最多等这么久。**它同时就是单击的感知延迟里那段静等** */
    const val DOUBLE_TAP_WINDOW_MILLIS: Long = 200

    /** 第二下「太早」的下限（毫秒）：与第一下抬起几乎同一时刻的按下不算第二下（平台默认 `doubleTapMinTimeMillis`） */
    const val DOUBLE_TAP_MIN_INTERVAL_MILLIS: Long = 40

    /** 第二下按下（[secondDownMillis]）是否落在窗口内（**含**边界）：从第一下**抬起**时刻算起 */
    fun isSecondDownWithinWindow(firstUpMillis: Long, secondDownMillis: Long): Boolean =
        secondDownMillis - firstUpMillis in 0..DOUBLE_TAP_WINDOW_MILLIS

    /** 第二下是否**太早**（同一帧里的多指 / 合成事件）：早于下限就丢掉这一下、继续等 */
    fun isSecondDownTooEarly(firstUpMillis: Long, secondDownMillis: Long): Boolean =
        secondDownMillis < firstUpMillis + DOUBLE_TAP_MIN_INTERVAL_MILLIS
}

/**
 * 单击 / 双击识别器：双击等待窗口用 [ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS]（200ms），不用平台默认的 300ms。
 *
 * 调用点在 `ui/ReaderScreen.kt` 的 `pointerInput`（原先那行 `detectTapGestures` 的直接替代）。
 * 语义见 [ReaderTapGesture] 的类 KDoc；本函数**只**负责识别，动作（唤出菜单 / 双击缩放）由调用方给。
 */
internal suspend fun PointerInputScope.detectReaderTapGestures(
    onDoubleTap: (Offset) -> Unit,
    onTap: (Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        // 抬手前被别的手势接管（拖动、双指缩放消费了事件）⇒ 本次不算点按
        val firstUp = waitForUpOrCancellation() ?: return@awaitEachGesture
        firstUp.consume()
        val secondDown = awaitSecondDown(firstUp)
        if (secondDown == null || !ReaderTapGesture.isSecondDownWithinWindow(firstUp.uptimeMillis, secondDown.uptimeMillis)) {
            // 窗口内没有第二下 ⇒ 单击（位置取第一下抬起处）
            onTap(firstUp.position)
            return@awaitEachGesture
        }
        val secondUp = waitForUpOrCancellation()
        if (secondUp == null) {
            // 第二下被别的手势接管：与上游 `detectTapGestures` 同一支处理，仍算单击
            onTap(firstUp.position)
        } else {
            secondUp.consume()
            onDoubleTap(secondUp.position)
        }
    }
}

/**
 * 等第二下按下，最多等 [ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS]；超时返回 `null`。
 *
 * 超时只是**唤醒条件**（协程恢复的调度抖动可能让事件晚到一帧），真正判定双击的是
 * [ReaderTapGesture.isSecondDownWithinWindow] 读事件自带的 `uptimeMillis` —— 两条都看，
 * 「窗口」才是输入时钟上的窗口，而不是协程调度上的窗口。
 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    firstUp: PointerInputChange,
): PointerInputChange? = withTimeoutOrNull(ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS) {
    var change = awaitFirstDown(requireUnconsumed = false)
    // 太早的按下（同一帧里的多指 / 合成事件）不算第二下，继续等
    while (ReaderTapGesture.isSecondDownTooEarly(firstUp.uptimeMillis, change.uptimeMillis)) {
        change = awaitFirstDown(requireUnconsumed = false)
    }
    change
}
