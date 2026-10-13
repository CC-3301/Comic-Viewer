package com.cc3301.comicviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import com.cc3301.comicviewer.core.input.ReaderTapEffect
import com.cc3301.comicviewer.core.input.ReaderTapGestureState
import com.cc3301.comicviewer.core.input.tapTakenOver
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 阅读页单击 / 双击的手势口径：**单击要马上有反馈，双击又不能闪**。
 *
 * **为什么不用 `detectTapGestures`**：它只要拿到 `onDoubleTap`，单击就必然被推后到双击等待窗口超时之后才
 * 触发（识别器没法知道你会不会点第二下），窗口由**平台**给（`ViewConfiguration.getDoubleTapTimeoutMillis()`
 * = 300ms）。反馈的「点击之后是延时出现」= 300ms 静等 + 出现动画 ≈ 0.4s，账对得上。
 * 缩短这个窗口**只能自己写手势**：`SuspendingPointerInputModifierNodeImpl.getViewConfiguration()` 读的是
 * **LayoutNode 树根**那一份配置（Owner 一次性设下、向整棵子树传播），不是组合局部
 * ⇒ 「在阅读页外面套一层更短的双击超时」不会生效（已查实）。
 *
 * **另一条路已否决**：单击立即响应、双击第二下再撤销。它会让菜单在双击时**闪一下**（已明确否决）。
 * 所以单击**必须等满**窗口——这个常量**就是**单击感知延迟里那段静等，调它等于调「点下去多久才看见菜单」。
 * 它是**界面侧唯一**的产品口径声明：判定全部在 `core/input/ReaderTapGestureState.kt`（有单测），
 * 本对象只留这份值 + 下面那个事件翻译。
 *
 * **输入源分流**：只有「太早」的下限按按下时的指针类型取——触摸用平台下限
 * （`doubleTapMinTimeMillis`，Android 默认 40ms），鼠标用 [MOUSE_DOUBLE_TAP_MIN_INTERVAL_MILLIS]（0）。
 * 平台那 40ms 是防「一次物理点击被拆成两下」的抖动下限，鼠标不需要；
 * 旧的单一 40ms 下限让「点得快的鼠标双击」被整条丢掉、退化成单击。窗口不分流：触摸与鼠标同一个
 * [DOUBLE_TAP_WINDOW_MILLIS]。
 *
 * **接管判据是位移，不是消费**（[tapTakenOver]，判定在 `core`）：放大态的平移/捏合通道接管时会消费指针事件，
 * 鼠标按下时的抖动也会让它接管；若把「事件被消费」当接管，双击就会随机作废。现在的口径是——位移没越过
 * 点击容差（= `viewConfiguration.touchSlop`）就算点按，两指出现立即算接管。
 */
internal object ReaderTapGesture {

    /**
     * 双击等待窗口（毫秒）：第一下抬起后最多等这么久。**它同时就是单击的感知延迟里那段静等**。
     * 触摸与鼠标同一个窗口（不按输入源分流）。
     *
     * **取 150ms**（2026-09-26 明确「保持 150ms」）：已确认「点了基本就弹出了」⇒ 唤起延迟
     * 这一段不再动，只动弹出动画本身（见 `ReaderMenuTransitions` 的现行口径：出现 300ms / 消失 200ms）。
     * 因此「点了到看见」≈ 150 + 300 = **450ms**（沿革：出现支曾取 500ms / 350ms / 120ms，
     * 对应 650ms / 500ms / 270ms；现行值是 2026-09-27 验收后的口径）——注意这是「面板滑到位」的账：
     * 点下去到**面板第一帧进屏幕**是 150 + ≈120 = **≈270ms**，比 450ms 早 ≈180ms
     * （≈120ms 的来历见 `ReaderMenuTransitions` 类 KDoc 的「整幅高 ≠ 面板高」）。
     */
    const val DOUBLE_TAP_WINDOW_MILLIS: Long = 150

    /** 鼠标双击的「太早」下限：平台那 40ms 是防触摸抖动拆分的，鼠标不需要 */
    const val MOUSE_DOUBLE_TAP_MIN_INTERVAL_MILLIS: Long = 0
}

/**
 * 单击 / 双击识别器：窗口统一 150ms（[ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS]），「太早」的下限按
 * 输入源分流（见 [ReaderTapGesture]），接管判据是位移（见 [tapTakenOver]）。
 *
 * 调用点在 `ui/ReaderScreen.kt` 的 `pointerInput`（原先那行 `detectTapGestures` 的直接替代）。
 * **判定在 `core/input/ReaderTapGestureState.kt` 里**（有单测），本函数只做三件事：
 * 把指针事件翻译成状态机的输入、把效果翻成 `onTap` / `onDoubleTap`、按窗口给「等第二下」上闸。
 * 事件 → 输入的对应：按下 → `onDown`、第一下抬起 → `onFirstUp`、第二下按下 → `onSecondDown`、
 * 第二下抬起 → `onSecondUp`、窗口过期 → `onWindowExpired`；
 * **被别的手势接管**（[awaitTapUp] 判到位移越过点击容差，或出现第二个指针）→ `onCancel`。
 *
 * 判据（无 Compose UI 测试依赖，UI 层走手动验收）：单击唤出菜单**不再有明显等待**、双击放大**仍灵**
 * （含鼠标快速双击）；坏掉的表现是「双击不放大」或「单击没反应」。
 */
internal suspend fun PointerInputScope.detectReaderTapGestures(
    onDoubleTap: (Offset) -> Unit,
    onTap: (Offset) -> Unit,
) {
    // 两个平台量在这里取一次：点击容差（触摸斜率）与「太早」下限
    val tapSlopPx = viewConfiguration.touchSlop
    val platformMinIntervalMillis = viewConfiguration.doubleTapMinTimeMillis

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
        // 输入源分流：只有「太早」的下限不同（状态机每次按下重建，窗口与下限都是构造参数）
        val fromMouse = down.type == PointerType.Mouse
        val gesture = ReaderTapGestureState(
            doubleTapWindowMillis = ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS,
            doubleTapMinIntervalMillis =
                if (fromMouse) ReaderTapGesture.MOUSE_DOUBLE_TAP_MIN_INTERVAL_MILLIS else platformMinIntervalMillis,
        )
        apply(gesture.onDown())
        // ① 等第一下抬起：位移越过点击容差（或出现第二个指针）才算被别的手势接管
        val firstUp = awaitTapUp(down.id, down.position, tapSlopPx)
        if (firstUp == null) {
            apply(gesture.onCancel())
            return@awaitEachGesture
        }
        firstUp.consume()
        apply(gesture.onFirstUp(firstUp.position.x, firstUp.position.y, firstUp.uptimeMillis))
        // ② 等第二下：窗口由状态机给（单一出处），超时即判单击；「太早」的按下丢掉这一下、继续等
        // 受理（[ReaderTapEffect.SecondDownAccepted]）才转去等抬手；其余（单击 / 无动作）就地落地并结束本次手势
        val secondDown = awaitSecondDown(gesture) { apply(it) } ?: return@awaitEachGesture
        // ③ 等第二下抬起：抬起即双击；被别的手势接管 ⇒ 与上游同一支处理，仍算单击
        val secondUp = awaitTapUp(secondDown.id, secondDown.position, tapSlopPx)
        if (secondUp == null) {
            apply(gesture.onCancel())
        } else {
            secondUp.consume()
            apply(gesture.onSecondUp(secondUp.position.x, secondUp.position.y))
        }
    }
}

/**
 * 等这一次按下抬起。返回 null = 本次点按被别的手势接管：判据见 [tapTakenOver]（出现第二个指针，或位移越过
 * [slopPx]）——**下层手势消费了事件但位移仍在容差内时不算接管**（放大态的平移通道与鼠标抖动都会消费事件，
 * 那不是「想拖动」）。
 */
private suspend fun AwaitPointerEventScope.awaitTapUp(
    pointerId: PointerId,
    downPosition: Offset,
    slopPx: Float,
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val pressedCount = event.changes.count { it.pressed }
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
        if (!change.pressed) return change
        val takenOver = tapTakenOver(
            x = change.position.x,
            y = change.position.y,
            downX = downPosition.x,
            downY = downPosition.y,
            pointerCount = pressedCount,
            slopPx = slopPx,
        )
        if (takenOver) return null
    }
}

/**
 * 等第二下按下：窗口长度取状态机的 `secondDownTimeoutMillis`（单一出处），超时即判单击；
 * 返回受理那一击的指针（null = 本次手势已就地结束：窗口过期判了单击，或第二下被判成单击）。
 * 「太早」的按下由状态机判为 [ReaderTapEffect.WaitForAnotherDown]——丢掉这一下、窗口不重置、继续等
 * （`awaitFirstDown()` 用默认的 `requireUnconsumed = true`，与上游 `detectTapGestures` 同一支）。
 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    gesture: ReaderTapGestureState,
    onEffect: (ReaderTapEffect) -> Unit,
): PointerInputChange? {
    var accepted: PointerInputChange? = null
    var ended = false
    val timedOut = withTimeoutOrNull(gesture.secondDownTimeoutMillis) {
        while (!ended && accepted == null) {
            val down = awaitFirstDown()
            when (val effect = gesture.onSecondDown(down.uptimeMillis)) {
                ReaderTapEffect.WaitForAnotherDown -> Unit
                ReaderTapEffect.SecondDownAccepted -> accepted = down
                else -> {
                    onEffect(effect)
                    ended = true
                }
            }
        }
    } == null
    if (timedOut) onEffect(gesture.onWindowExpired())
    return accepted
}
