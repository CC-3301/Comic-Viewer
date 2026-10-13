package com.cc3301.comicviewer.core.input

import kotlin.math.sqrt

/**
 * 阅读器放大态的「平移 + 双指捏合」手势状态机（纯逻辑，由 [ZoomGestureTest] 锁定）。
 *
 * **为什么自实现**：原来这两件事交给 `Modifier.transformable`，两处走不通——
 * ① 它挂在列表的**外层**，而 Main 阶段的派发由内向外：列表内建滚动先过自己的斜率就 `consume()` 掉整个
 *    change（含横轴），`transformable` 再看到时已是被消费的 change、整段直接退出 ⇒ 条漫里的拖动从来不
 *    到它手上（横向纵向都拖不动）；
 * ② 它不按设备类型分流（鼠标也要求越过手指级斜率），且**只要过了斜率就消费、不看 `canPan`**，
 *    于是鼠标按下时的抖动会顺手作废外层点按识别器的双击。
 * 因此按 `MouseDragScrollGesture` 的既有形状自实现：判定在 core（本文件）、`ui/ReaderScreen` 只做事件
 * 翻译（读 `PointerEventPass.Initial`，抢在列表之前消费）。
 *
 * 手势归属（spec 故事 33/34）：
 * - **双指**：立即接管（列表内建滚动不得再看到这段拖动），输出质心与指距的变化；
 * - **单指**：只在按下点所在那页**处于放大态**时接管（[ZoomGestureFrame.panEnabled]，由界面在按下时
 *   锁定那一页后逐帧给出），且要越过 [panSlopPx] 才开始平移；该页未放大时整段让位——触摸与鼠标的单指
 *   拖动照旧滚列表；
 * - 捏合中抬走一根手指：就地重基线、继续平移（不再过一次斜率，避免跳变）；
 * - 松手不给惯性：放大态平移不需要惯性。
 *
 * [ZoomGestureStep.consume] 只表达「本帧要不要消费指针事件」，**不参与点按判定**——点按的接管判据是位移
 * 与指针数（见 `tapTakenOver`），所以这里消费掉事件不会作废双击。
 */
class ZoomGesture(private val panSlopPx: Float) {

    /** 上一帧按下的指针数（0 = 上一帧没有按下，本帧即一段手势的第一帧） */
    private var lastPointerCount = 0

    /** 上一帧的质心（容器本地 px），平移增量由相邻两帧相减得到 */
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f

    /** 上一帧质心到各指针的平均距离（捏合幅度）：相邻两帧相除即倍率 */
    private var lastSpanPx = 0f

    /** 单指平移的起点（越过 [panSlopPx] 的判据），以及「本段已在平移」 */
    private var panOriginX = 0f
    private var panOriginY = 0f
    private var panning = false

    /** 处理一帧：返回本帧要施加的变换与是否消费 */
    fun handle(frame: ZoomGestureFrame): ZoomGestureStep {
        if (frame.pointerCount <= 0) {
            reset()
            return ZoomGestureStep.EMPTY
        }

        val firstFrame = lastPointerCount == 0
        val continuingPinch = lastPointerCount >= 2 && frame.pointerCount >= 2
        val pinchJustEnded = lastPointerCount >= 2 && frame.pointerCount < 2

        if (frame.pointerCount >= 2) {
            // 双指：立即接管。基线不齐（本段第一帧）时只记基线、不产生变换，避免倍率与位移各跳一下
            val effects = if (continuingPinch && lastSpanPx > 0f) {
                listOf(
                    ZoomGestureEffect.Pinch(
                        zoomChange = frame.spanPx / lastSpanPx,
                        panChangeX = frame.centroidX - lastCentroidX,
                        panChangeY = frame.centroidY - lastCentroidY,
                        centroidX = frame.centroidX,
                        centroidY = frame.centroidY,
                    ),
                )
            } else {
                emptyList()
            }
            remember(frame)
            panning = false
            return ZoomGestureStep(effects = effects, consume = true)
        }

        // 单指
        if (pinchJustEnded) {
            remember(frame)
            // 未放大的页：剩下那根手指照旧让位给列表（本帧消费掉的话，内建滚动一见 isConsumed 就整段放弃）
            if (!frame.panEnabled) {
                panning = false
                return ZoomGestureStep.EMPTY
            }
            // 已放大：就地重基线继续平移，不再过一次斜率（手指已经移过很远，过斜率会跳）
            panOriginX = frame.centroidX
            panOriginY = frame.centroidY
            panning = true
            return ZoomGestureStep(emptyList(), consume = true)
        }
        if (firstFrame) {
            // 按下那一帧：只记起点，等越过斜率——斜率内的位移算点按，不该挪画面。
            // 本帧无条件记起点（该页此刻未放大也记）：不然后来该页变放大时，斜率会拿一个陈旧的原点
            // 去比，第一帧就被当成「越过斜率」而跳一下。
            remember(frame)
            panOriginX = frame.centroidX
            panOriginY = frame.centroidY
            panning = false
            return ZoomGestureStep.EMPTY
        }
        if (!frame.panEnabled) {
            // 该页未放大：整段让位（触摸/鼠标的单指拖动照旧滚列表）
            remember(frame)
            panning = false
            return ZoomGestureStep.EMPTY
        }
        if (!panning) {
            if (distance(frame.centroidX, frame.centroidY, panOriginX, panOriginY) <= panSlopPx) {
                remember(frame)
                return ZoomGestureStep.EMPTY
            }
            panning = true
        }
        // 平移增量取相邻两帧的差（与 MouseDragScrollGesture 同一口径）：过斜率那一帧只带上最后一步的位移，起手不跳
        val step = ZoomGestureStep(
            effects = listOf(
                ZoomGestureEffect.Pan(
                    deltaX = frame.centroidX - lastCentroidX,
                    deltaY = frame.centroidY - lastCentroidY,
                ),
            ),
            consume = true,
        )
        remember(frame)
        return step
    }

    private fun remember(frame: ZoomGestureFrame) {
        lastPointerCount = frame.pointerCount
        lastCentroidX = frame.centroidX
        lastCentroidY = frame.centroidY
        lastSpanPx = frame.spanPx
    }

    private fun reset() {
        lastPointerCount = 0
        lastCentroidX = 0f
        lastCentroidY = 0f
        lastSpanPx = 0f
        panning = false
        panOriginX = 0f
        panOriginY = 0f
    }
}

/**
 * 手势的一帧输入（由界面从指针事件翻译而来；`core` 不认识 Compose 的指针类型）。
 * [pointerCount] = 按下的指针数（0 = 手势结束，其余字段无意义）；[centroidX]/[centroidY] = 质心（容器本地 px）；
 * [spanPx] = 质心到各指针的平均距离（捏合幅度）；[panEnabled] = 本次手势锁定的那一页此刻是否处于放大态
 * （未放大时单指拖动整段让位给列表）。
 */
data class ZoomGestureFrame(
    val pointerCount: Int,
    val centroidX: Float,
    val centroidY: Float,
    val spanPx: Float,
    val panEnabled: Boolean,
)

/** 手势一帧的输出 */
data class ZoomGestureStep(
    val effects: List<ZoomGestureEffect>,
    /** 本帧是否消费指针事件（= 本手势已接管）：列表内建滚动与鼠标拖动滚动据此让位 */
    val consume: Boolean,
) {
    companion object {
        /** 本帧无变换、也不接管（未放大时单指拖动的常态） */
        val EMPTY = ZoomGestureStep(emptyList(), consume = false)
    }
}

/** 手势对手势源发出的变换（单位都是容器本地 px / 倍率） */
sealed interface ZoomGestureEffect {

    /** 单指平移增量（正 = 内容向右/下移动） */
    data class Pan(val deltaX: Float, val deltaY: Float) : ZoomGestureEffect

    /**
     * 双指捏合：相对上一帧的倍率 [zoomChange]、质心位移 [panChangeX]/[panChangeY]，
     * 以及质心位置 [centroidX]/[centroidY]——界面据此把它折成该页的页内锚点。
     */
    data class Pinch(
        val zoomChange: Float,
        val panChangeX: Float,
        val panChangeY: Float,
        val centroidX: Float,
        val centroidY: Float,
    ) : ZoomGestureEffect
}

/** 两点距离（斜率判据用） */
private fun distance(x: Float, y: Float, originX: Float, originY: Float): Float {
    val dx = x - originX
    val dy = y - originY
    return sqrt(dx * dx + dy * dy)
}
