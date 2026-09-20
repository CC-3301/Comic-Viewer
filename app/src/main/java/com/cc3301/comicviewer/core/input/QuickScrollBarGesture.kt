package com.cc3301.comicviewer.core.input

import com.cc3301.comicviewer.core.view.quickScrollBarIndexForDrag
import kotlin.math.abs

/**
 * 快速定位滑条的手势判定（票 #60，纯逻辑，由 [QuickScrollBarGestureTest] 锁定）。
 *
 * 与仓库既有的两个手势状态机同形（`MouseDragScrollGesture` / `PullRefreshGesture`）：输入 → 效果一一对应，
 * 界面侧（`ui/QuickScrollBar.kt`）只负责把指针事件翻译成输入、把效果落到界面状态与滚动状态上。
 * 抽出来的理由与那两个一样——「只认主键」「越斜率才算拖动」「滚轮换算」都是判定，不是接线。
 *
 * 判定与换算共四条：
 * 1. **只认主键**：触摸/触控笔一律接；鼠标要求不是右键/中键（与票 #69 的鼠标拖动同口径）；
 * 2. **越过触摸斜率才算拖动**：因此「压一下滑条」不带出定位（票面只要求拖动）；
 * 3. **按下即上报按住状态**（[QuickScrollBarEffect.Hold]）：界面据此在按住期间不隐藏滑条——否则按住超过
 *    淡化窗（约 1.45s）时抓取带被整条移除、手势协程被取消，再拖就完全没反应（票 #60 r1 评审 spec P2-2）；
 * 4. **滚轮换算成列表的原始位移**（[QuickScrollBarEffect.ScrollBy]）：滑条带是命中路径上的最上层
 *    （Compose 命中只取最上层命中的兄弟），**落在这条带里的滚轮事件到不了列表**，因此由这里代它算
 *    （票 #60 r1 评审 spec P2-3；换算与 foundation 内建滚动同一条，见 [wheelScrollPx]）。
 */

/**
 * 滑条手势的输入。
 *
 * 几何（[Drag] 的后三个字段）**随输入一起传**、不存进状态机：条目数/轨道长/滑条长都会随滚动变化，
 * 由界面每次移动取最新一份，状态机因此不会拿着过期的几何算索引（与 `PullInput.Drag` 带 `atTop` 同一路数）。
 */
sealed interface QuickScrollBarInput {
    /** 指针按下：[y] = 按下滑条带内的纵坐标（px）；[secondaryMouse] = 鼠标右键/中键按下（本手势不接） */
    data class Down(val y: Float, val secondaryMouse: Boolean = false) : QuickScrollBarInput

    /**
     * 指针移动。[y] = 当前纵坐标；[totalItems] / [trackLengthPx] / [thumbLengthPx] = 当前滑条几何
     * （与 `core/view/QuickScrollBar.kt` 的 [com.cc3301.comicviewer.core.view.QuickScrollBarGeometry] 同一来源）。
     */
    data class Drag(
        val y: Float,
        val totalItems: Int,
        val trackLengthPx: Float,
        val thumbLengthPx: Float,
    ) : QuickScrollBarInput

    /**
     * 鼠标滚轮：[deltaY] = Compose 的 `PointerInputChange.scrollDelta.y`
     * （约定见 [wheelScrollPx]，界面侧直接把这个值交进来，不做二次换算）。
     */
    data class Scroll(val deltaY: Float) : QuickScrollBarInput

    /** 指针抬起 */
    data object Up : QuickScrollBarInput

    /** 手势被取消（滑条节点被移除、指针输入重启）：就地复位，不产生定位 */
    data object Cancel : QuickScrollBarInput
}

/** 滑条手势对界面发出的效果 */
sealed interface QuickScrollBarEffect {
    /**
     * 按住滑条带（true = 按下、false = 已松手/取消/手势被取消）。
     * 界面据此在按住期间**不隐藏滑条**（票 #60 r2：按住超过淡化窗再拖也不能丢），并清掉拖动中的本地索引。
     */
    data class Hold(val holding: Boolean) : QuickScrollBarEffect

    /** 拖动中：定位到该条目（跟手；每次移动都发一次） */
    data class Seek(val index: Int) : QuickScrollBarEffect

    /** 滚轮：把列表滚 [deltaPx]（滑条带吃掉了列表自己的滚轮事件，由本效果代它滚） */
    data class ScrollBy(val deltaPx: Float) : QuickScrollBarEffect
}

/**
 * 滑条手势状态机（票 #60）。
 *
 * @param touchSlopPx 触摸斜率（px）：拖动要越过的门槛（与内建滚动、票 #69 同一来源 `viewConfiguration.touchSlop`）
 * @param wheelPixelsPerUnit 一个滚轮单位对应的列表位移（px）：界面传 `64.dp.toPx()`
 *   （foundation 内建滚轮换算里的那个 64dp 常量）
 */
class QuickScrollBarGesture(
    private val touchSlopPx: Float,
    private val wheelPixelsPerUnit: Float,
) {

    private var downY: Float? = null
    private var dragging: Boolean = false

    /** 是否正按在滑条带上（未松手）：界面据此让隐藏倒计时让位（见 [QuickScrollBarEffect.Hold]） */
    val holding: Boolean get() = downY != null

    /** 处理一个输入，返回要施加到界面的效果（可能为空 = 本次输入与滑条手势无关） */
    fun handle(input: QuickScrollBarInput): List<QuickScrollBarEffect> = when (input) {
        is QuickScrollBarInput.Down -> onDown(input)
        is QuickScrollBarInput.Drag -> onDrag(input)
        is QuickScrollBarInput.Scroll -> listOf(QuickScrollBarEffect.ScrollBy(wheelScrollPx(input.deltaY)))
        QuickScrollBarInput.Up, QuickScrollBarInput.Cancel -> onEnd()
    }

    /**
     * 滚轮位移（列表的原始滚动量，正 = 向后滚）：与 foundation 内建滚动同一条换算
     * （`AndroidConfig.calculateMouseWheelScroll` = `Σ scrollDelta × -(64.dp)`），因此**带内与带外**滚轮的
     * 方向与手感一致（带内的滚轮由本手势代列表执行）。
     *
     * 符号来路：ui 侧 `MotionEventAdapter` 把 `AXIS_VSCROLL` 取反后放进 `scrollDelta`（滚轮向下 = 负），
     * 内建换算再乘 `-64dp`，于是滚轮向下 → 正位移 → 列表向后滚。
     */
    fun wheelScrollPx(deltaY: Float): Float = deltaY * -wheelPixelsPerUnit

    private fun onDown(input: QuickScrollBarInput.Down): List<QuickScrollBarEffect> {
        // 鼠标右键/中键按下：不属本手势（与票 #69 同口径），本次按下整段不接
        if (input.secondaryMouse) {
            downY = null
            dragging = false
            return emptyList()
        }
        downY = input.y
        dragging = false
        // 按下即上报按住：界面据此在按住期间不隐藏滑条（票 #60 r2）
        return listOf(QuickScrollBarEffect.Hold(true))
    }

    private fun onDrag(input: QuickScrollBarInput.Drag): List<QuickScrollBarEffect> {
        val start = downY ?: return emptyList()
        if (!dragging) {
            // 未越过触摸斜率：本次按下还不是拖动（「压一下滑条」不带出定位）
            if (abs(input.y - start) <= touchSlopPx) return emptyList()
            dragging = true
        }
        // 「行程 → 目标索引」复用几何侧的纯函数（[quickScrollBarIndexForDrag]）：拖动与滑条位置互为逆映射
        return listOf(
            QuickScrollBarEffect.Seek(
                quickScrollBarIndexForDrag(
                    positionPx = input.y,
                    totalItems = input.totalItems,
                    trackLengthPx = input.trackLengthPx,
                    thumbLengthPx = input.thumbLengthPx,
                ),
            ),
        )
    }

    private fun onEnd(): List<QuickScrollBarEffect> {
        // 没在按（右键按下那次、或重复抬手）：不产生效果，也不打扰界面状态
        if (downY == null) return emptyList()
        downY = null
        dragging = false
        return listOf(QuickScrollBarEffect.Hold(false))
    }
}
