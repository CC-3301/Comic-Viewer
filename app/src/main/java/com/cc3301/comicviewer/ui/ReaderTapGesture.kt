package com.cc3301.comicviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
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
 * **输入源分流**：窗口与「太早」的下限都按按下时的指针类型取——
 * 触摸用 [DOUBLE_TAP_WINDOW_MILLIS] + 平台下限（`doubleTapMinTimeMillis`，Android 默认 40ms）；
 * 鼠标用 [MOUSE_DOUBLE_TAP_WINDOW_MILLIS] + 下限 0。平台那 40ms 是防「一次物理点击被拆成两下」的抖动下限，
 * 鼠标不需要；鼠标两击的生理间隔也比手指长，窗口因此放到 300ms。
 *
 * **接管判据是本段新落下的第二指与位移，不是消费**（[tapTakenOver]，判定在 `core`）：放大态的平移/捏合通道
 * 接管时会消费指针事件，鼠标按下时的抖动也会让它接管；若把「事件被消费」当接管，双击就会随机作废。
 * 现在的口径是——**本段期间新落下**的第二指立即算接管，位移越过点击容差（= `viewConfiguration.touchSlop`）
 * 也算接管，两者都没有就算点按；按下那一刻**就已经按着**的指针不算接管（系统没认到松手留下的那个指针
 * 会让之后每一次点按都被它作废，连触摸双击也一起死）。
 */
internal object ReaderTapGesture {

    /**
     * 触摸的双击等待窗口（毫秒）：第一下抬起后最多等这么久。**它同时就是单击的感知延迟里那段静等**。
     * 鼠标走 [MOUSE_DOUBLE_TAP_WINDOW_MILLIS]（300ms），两者都是界面侧的产品口径声明。
     *
     * **取 150ms**（2026-09-26 明确「保持 150ms」）：已确认「点了基本就弹出了」⇒ 唤起延迟
     * 这一段不再动，只动弹出动画本身（见 `ReaderMenuTransitions` 的现行口径：出现 300ms / 消失 200ms）。
     * 因此「点了到看见」≈ 150 + 300 = **450ms**（沿革：出现支曾取 500ms / 350ms / 120ms，
     * 对应 650ms / 500ms / 270ms；现行值是 2026-09-27 验收后的口径）——注意这是「面板滑到位」的账：
     * 点下去到**面板第一帧进屏幕**是 150 + ≈120 = **≈270ms**，比 450ms 早 ≈180ms
     * （≈120ms 的来历见 `ReaderMenuTransitions` 类 KDoc 的「整幅高 ≠ 面板高」）。
     */
    const val DOUBLE_TAP_WINDOW_MILLIS: Long = 150

    /** 鼠标的双击等待窗口（毫秒）：鼠标两击的生理间隔比手指长（单击唤出菜单的延迟账也只对触摸算） */
    const val MOUSE_DOUBLE_TAP_WINDOW_MILLIS: Long = 300

    /** 鼠标双击的「太早」下限：平台那 40ms 是防触摸抖动拆分的，鼠标不需要 */
    const val MOUSE_DOUBLE_TAP_MIN_INTERVAL_MILLIS: Long = 0
}

/**
 * 单击 / 双击识别器：窗口与「太早」的下限都按输入源分流（触摸 150ms / 鼠标 300ms，见 [ReaderTapGesture]），
 * 接管判据是本段新落下的第二指与位移（见 [tapTakenOver]）。入口用 [awaitFreshPointerDown]（不用
 * `awaitFirstDown()`：卡住的指针会让它永远不返回）。
 *
 * 调用点在 `ui/ReaderScreen.kt` 的 `pointerInput`（原先那行 `detectTapGestures` 的直接替代）。
 * **判定在 `core/input/ReaderTapGestureState.kt` 里**（有单测），本函数只做三件事：
 * 把指针事件翻译成状态机的输入、把效果翻成 `onTap` / `onDoubleTap`、按窗口给「等第二下」上闸。
 * 事件 → 输入的对应：按下 → `onDown`、第一下抬起 → `onFirstUp`、第二下按下 → `onSecondDown`、
 * 第二下抬起 → `onSecondUp`、窗口过期 → `onWindowExpired`；
 * **同一帧里一起落下的多指**（双指同落）在两处入口都就地结束本段、不发任何动作（那是捏合，不是点按）；
 * **被别的手势接管**（[awaitTapUp] 判到位移越过点击容差，或**本段期间新落下**第二个指针）→ `onCancel`。
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
        val down = awaitFreshPointerDown()
        down.consume()
        // 输入源分流：窗口与下限都按指针类型取（状态机每次按下重建，两者都是构造参数）
        val fromMouse = down.type == PointerType.Mouse
        val gesture = ReaderTapGestureState(
            doubleTapWindowMillis = if (fromMouse) {
                ReaderTapGesture.MOUSE_DOUBLE_TAP_WINDOW_MILLIS
            } else {
                ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS
            },
            doubleTapMinIntervalMillis =
                if (fromMouse) ReaderTapGesture.MOUSE_DOUBLE_TAP_MIN_INTERVAL_MILLIS else platformMinIntervalMillis,
        )
        apply(gesture.onDown())
        // 本段开始时就按着的指针 id：**它们不算接管**（见 [tapTakenOver]），只有本段期间新落下的第二指才算。
        // 同一帧里一起落下的多指（双指同落）本段从一开始就不是点按 ⇒ 直接作废
        if (currentEvent.freshDownIds().size > 1) return@awaitEachGesture
        val downIds = pressedIds()
        // ① 等第一下抬起：位移越过点击容差（或本段新落下第二指）才算被别的手势接管
        val firstUp = awaitTapUp(down.id, down.position, downIds, tapSlopPx)
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
        val secondUp = awaitTapUp(secondDown.change.id, secondDown.change.position, secondDown.pressedIds, tapSlopPx)
        if (secondUp == null) {
            apply(gesture.onCancel())
        } else {
            secondUp.consume()
            apply(gesture.onSecondUp(secondUp.position.x, secondUp.position.y))
        }
    }
}

/**
 * 等这一次按下抬起。返回 null = 本次点按被别的手势接管：判据见 [tapTakenOver]——**本段期间新落下的第二指**
 * （id 不在按下那一刻的 [downIds] 里），或位移越过 [slopPx]。
 * 下层手势消费了事件、或「按下那一刻就已经按着别的指针」都不算接管：前者是放大态的平移通道与鼠标抖动
 * （那不是「想拖动」），后者是系统没认到松手留下的指针（把它算成第二指会让之后每次点按都作废）。
 */
private suspend fun AwaitPointerEventScope.awaitTapUp(
    pointerId: PointerId,
    downPosition: Offset,
    downIds: Set<PointerId>,
    slopPx: Float,
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
        if (!change.pressed) return change
        val takenOver = tapTakenOver(
            x = change.position.x,
            y = change.position.y,
            downX = downPosition.x,
            downY = downPosition.y,
            secondPointerDown = event.changes.any { it.changedToDownIgnoreConsumed() && it.id !in downIds },
            slopPx = slopPx,
        )
        if (takenOver) return null
    }
}

/** 本刻按着的指针 id 集合（刚拿到按下那一帧就是本段的起点快照） */
private fun AwaitPointerEventScope.pressedIds(): Set<PointerId> =
    currentEvent.changes.filter { it.pressed }.map { it.id }.toSet()

/** [awaitSecondDown] 受理的那一击：指针本身 + 那一刻按着的指针 id（第二步接管的起点快照） */
private class AcceptedSecondDown(val change: PointerInputChange, val pressedIds: Set<PointerId>)

/**
 * 等第二下按下：窗口长度取状态机的 `secondDownTimeoutMillis`（单一出处），超时即判单击；
 * 返回受理那一击（null = 本次手势已就地结束：窗口过期判了单击，或第二下被判成单击）。
 * 「太早」的按下由状态机判为 [ReaderTapEffect.WaitForAnotherDown]——丢掉这一下、窗口不重置、继续等
 * （入口是 [awaitFreshPointerDown]，与上游 `detectTapGestures` 的「太早」支同一形状）。
 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(
    gesture: ReaderTapGestureState,
    onEffect: (ReaderTapEffect) -> Unit,
): AcceptedSecondDown? {
    var accepted: AcceptedSecondDown? = null
    var ended = false
    val timedOut = withTimeoutOrNull(gesture.secondDownTimeoutMillis) {
        while (!ended && accepted == null) {
            val down = awaitFreshPointerDown()
            // 同一帧里一起落下的多指：本段不是点按（捏合），就地结束、不发单击
            // （在判「第二下」之前拦：先受理再取消会白算一次双击）
            if (currentEvent.freshDownIds().size > 1) {
                onEffect(gesture.onCancel())
                ended = true
                continue
            }
            when (val effect = gesture.onSecondDown(down.uptimeMillis)) {
                ReaderTapEffect.WaitForAnotherDown -> Unit
                ReaderTapEffect.SecondDownAccepted -> accepted = AcceptedSecondDown(down, pressedIds())
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
