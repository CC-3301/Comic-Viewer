package com.cc3301.comicviewer.core.input

/**
 * 阅读页单击 / 双击识别器的状态机（票 #129 r4 抽出、r5 收口；纯逻辑，由 [ReaderTapGestureStateTest] 锁定）。
 *
 * **为什么有这一层**：识别器里有判定——等多久算「双击窗口」、多近算「太早」、哪一支发单击 / 双击 / 放弃——
 * 判定不是接线。仓库既有的两处手写手势同形（`MouseDragScrollGesture` / `QuickScrollBarGesture`：判定进
 * `core/input/`，`ui/` 只做事件翻译，对照 `ui/MouseDragScroll.kt` 的类 KDoc）；r4 把这些判定留在
 * `ui/ReaderTapGesture.kt` 的挂起函数里，除常量与谓词外不可测，本文件按同形收口。
 *
 * **两个时间量都是构造参数**，本类不写死任何一个：
 * - [doubleTapWindowMillis]：本票产品口径 200ms（声明在 `ui/ReaderTapGesture.kt`，平台默认是 300ms）；
 * - [doubleTapMinIntervalMillis]：平台量 `viewConfiguration.doubleTapMinTimeMillis`（Android 默认 40ms），
 *   由界面侧读出后传进来 —— 与 `MouseDragScrollGesture(touchSlopPx = viewConfiguration.touchSlop)` 同一做法，
 *   设备/OEM 报不同下限时行为才与文档声明的「同一档」相符。
 *
 * **窗口只有一处声明**：界面给「等第二下」上闸用的就是 [secondDownTimeoutMillis]（本类唯一的窗口出口）；
 * 第二下「越界」的判定也读同一份值，不存在两份可各自漂移的声明。
 *
 * **行为语义与上游 `detectTapGestures` 逐支对齐**（本类是它的子集：无长按、无 `onPress`）：
 * - 第一下抬手前被别的手势接管（拖动、双指缩放消费了事件）⇒ [onCancel] 什么也不发；
 * - 窗口内没有第二下 ⇒ [onWindowExpired] 发单击，坐标取**第一下抬起**处；
 * - 第二下抬起 ⇒ [onSecondUp] 发双击，坐标取**第二下抬起**处，且**不**再发单击（不允许闪）；
 * - 第二下按下之后被别的手势接管 ⇒ [onCancel] 与上游同一支处理，仍算单击。
 *
 * 界面侧的翻译见 `ui/ReaderTapGesture.kt`（`detectReaderTapGestures`）；**移动 / 拖动**不单独喂进来：
 * `waitForUpOrCancellation()` 一旦发现事件被别人消费就返回 null，界面把它翻成 [onCancel]。
 */
class ReaderTapGestureState(
    private val doubleTapWindowMillis: Long,
    private val doubleTapMinIntervalMillis: Long,
) {

    private enum class Phase {
        /** 未按下（等第一下） */
        Idle,

        /** 第一下已按下，等它抬起 */
        WaitingFirstUp,

        /** 第一下已抬起，窗口内等第二下按下 */
        WaitingSecondDown,

        /** 第二下已按下（已判定为第二下），等它抬起 */
        WaitingSecondUp,
    }

    private var phase = Phase.Idle
    private var firstUpMillis = 0L
    private var firstUpX = 0f
    private var firstUpY = 0f

    /** 等第二下的上闸长度（毫秒）：界面把它交给 `withTimeoutOrNull` —— 窗口只有这一处出口 */
    val secondDownTimeoutMillis: Long get() = doubleTapWindowMillis

    /** 第一下按下：开始新的一次点按（不背上一段的时刻与位置） */
    fun onDown(): ReaderTapEffect {
        phase = Phase.WaitingFirstUp
        return ReaderTapEffect.None
    }

    /** 第一下抬起：记下位置与时刻（窗口从这一刻起算），进入「等第二下」 */
    fun onFirstUp(x: Float, y: Float, uptimeMillis: Long): ReaderTapEffect {
        if (phase != Phase.WaitingFirstUp) return ReaderTapEffect.None
        firstUpX = x
        firstUpY = y
        firstUpMillis = uptimeMillis
        phase = Phase.WaitingSecondDown
        return ReaderTapEffect.None
    }

    /**
     * 第二下按下。**一条规则、一个下界**：从第一下抬起算起，落在
     * `[doubleTapMinIntervalMillis, doubleTapWindowMillis]` 内才算「就是第二下」。
     *
     * - 早于最小间隔 ⇒ [ReaderTapEffect.WaitForAnotherDown]（丢掉这一下、窗口不重置、继续等）；
     * - 晚于窗口 ⇒ [ReaderTapEffect.SingleTap]（事件晚到一帧：按输入时钟判，不按协程时钟判）；
     * - 落在窗口内 ⇒ [ReaderTapEffect.None]（等它抬起即双击）。
     */
    fun onSecondDown(uptimeMillis: Long): ReaderTapEffect {
        if (phase != Phase.WaitingSecondDown) return ReaderTapEffect.None
        val elapsed = uptimeMillis - firstUpMillis
        return when {
            elapsed < doubleTapMinIntervalMillis -> ReaderTapEffect.WaitForAnotherDown
            elapsed > doubleTapWindowMillis -> {
                phase = Phase.Idle
                singleTap()
            }
            else -> {
                phase = Phase.WaitingSecondUp
                ReaderTapEffect.None
            }
        }
    }

    /** 第二下抬起 ⇒ 双击（坐标取第二下抬起处）；本支**不**发单击 */
    fun onSecondUp(x: Float, y: Float): ReaderTapEffect {
        if (phase != Phase.WaitingSecondUp) return ReaderTapEffect.None
        phase = Phase.Idle
        return ReaderTapEffect.DoubleTap(x = x, y = y)
    }

    /** 窗口内没有第二下 ⇒ 单击（坐标取第一下抬起处） */
    fun onWindowExpired(): ReaderTapEffect {
        if (phase != Phase.WaitingSecondDown) return ReaderTapEffect.None
        phase = Phase.Idle
        return singleTap()
    }

    /**
     * 本次点按被别的手势接管（拖动 / 双指缩放消费了事件）：
     * - 第一下抬手前 ⇒ 什么也不发（不是点按）；
     * - 第二下按下之后 ⇒ 与上游 `detectTapGestures` 同一支处理，仍算**单击**（坐标取第一下抬起处）。
     *
     * 相位在两种情况下都归 [Phase.Idle] ⇒ 后续的窗口过期 / 抬手不会重复发动作。
     */
    fun onCancel(): ReaderTapEffect {
        val effect = if (phase == Phase.WaitingSecondUp) singleTap() else ReaderTapEffect.None
        phase = Phase.Idle
        return effect
    }

    private fun singleTap() = ReaderTapEffect.SingleTap(x = firstUpX, y = firstUpY)
}

/** 识别器对界面发出的效果（坐标是**容器本地**的 px，界面翻成 `Offset` 后交给动作） */
sealed interface ReaderTapEffect {

    /** 无动作：继续等下一个事件（含「第二下已判定，等它抬起」） */
    data object None : ReaderTapEffect

    /**
     * 第二下「太早」（与第一下抬起几乎同一时刻的按下：同一帧里的多指 / 合成事件）：
     * 丢掉这一下、**窗口不重置**、仍在窗口内继续等下一个按下。
     */
    data object WaitForAnotherDown : ReaderTapEffect

    /** 单击：坐标 = **第一下抬起**处 */
    data class SingleTap(val x: Float, val y: Float) : ReaderTapEffect

    /** 双击：坐标 = **第二下抬起**处 */
    data class DoubleTap(val x: Float, val y: Float) : ReaderTapEffect
}
