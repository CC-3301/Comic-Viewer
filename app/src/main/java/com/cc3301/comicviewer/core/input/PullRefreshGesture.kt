package com.cc3301.comicviewer.core.input

/**
 * 下拉更新的手势状态机（票 #53，纯逻辑，由 [PullRefreshGestureTest] 锁定）。
 *
 * **为什么不用现成组件**：M3 的 `PullToRefreshBox` 走嵌套滚动，判定的是「子容器滚不动之后剩下的滚动量」，
 * 而滚轮在 Compose 里正是以 `NestedScrollSource.UserInput` 的普通滚动增量喂给同一套嵌套滚动的
 * （`ScrollableNode.processMouseWheelEvent` → `scrollBy(source = UserInput)`）。也就是说：滚轮滑到列表顶部
 * 继续滚，会被那种实现当成"下拉"，把指示器拽出来（且没有 release 事件让它复位），在更高的 Compose 版本上
 * 还会直接触发一次刷新。本状态机只接受**指针拖拽**（[PullInput.Drag]），[PullInput.Scroll] 是被显式忽略的
 * 一类输入——「滚轮不得触发」因此是结构性保证，而不是靠调参。
 *
 * 输入 → 效果一一对应，便于单测；Compose 侧（`ui/PullToRefreshArea.kt`）只负责把指针事件翻译成 [PullInput]、
 * 把 [PullEffect] 落到界面状态上。
 */

/** 下拉手势的输入：只有 [Drag] 能改变下拉位移；[Scroll]（滚轮/触控板）永远不改变它 */
sealed interface PullInput {
    /** 指针按下（手指或鼠标左键）；[y] 为按下点的纵坐标 */
    data class Down(val y: Float) : PullInput

    /**
     * 指针拖动增量。[deltaY] 为正 = 向下拉；[atTop] = 当前列表是否停在顶部
     * （不在顶部时的向下拖拽属于常规滚动，本状态机不介入）。
     */
    data class Drag(val y: Float, val deltaY: Float, val atTop: Boolean) : PullInput

    /**
     * 滚轮/触控板滚动（`PointerEventType.Scroll`）：**不得**改变下拉状态（票 #53 硬约束）。
     * 不带载荷——状态机不看滚动量本身，只看「这是滚动而不是拖拽」。
     */
    data object Scroll : PullInput

    /** 指针抬起 */
    data object Up : PullInput

    /** 手势被取消（滚动容器抢走、窗口失去焦点等）：就地复位，不触发 */
    data object Cancel : PullInput
}

/** 下拉手势对界面发出的效果 */
sealed interface PullEffect {
    /** 指示器滑出的像素（已乘阻尼系数） */
    data class Offset(val px: Float) : PullEffect

    /** 越过阈值并松手：发起一次刷新（一次拖拽至多一次） */
    data object Trigger : PullEffect

    /** 回弹复位（松手未达阈值 / 手势取消 / 触发之后） */
    data object Reset : PullEffect
}

/**
 * 下拉更新的位移模型与判定（票 #53）。
 *
 * 阻尼系数与现成组件同量级（手指移动 1px，指示器滑出 0.5px）：手感一致，且阈值只需按视觉距离设。
 */
class PullRefreshGesture(
    private val thresholdPx: Float,
    private val touchSlopPx: Float,
    private val dragMultiplier: Float = DRAG_MULTIPLIER,
) {

    /** 累计拖拽位移（px，向下为正） */
    private var distancePulled: Float = 0f

    /** 已进入下拉（越过斜率门槛且列表在顶部） */
    private var pulling: Boolean = false

    private var downY: Float? = null

    /** 指示器当前应滑出的像素 */
    val offsetPx: Float get() = distancePulled * dragMultiplier

    /** 0..1 的进度（画环形指示器用） */
    val progress: Float get() = (offsetPx / thresholdPx).coerceIn(0f, 1f)

    /** 是否已达触发阈值（严格大于：与现成组件同口径） */
    val reachedThreshold: Boolean get() = offsetPx > thresholdPx

    /** 处理一个输入，返回要施加到界面的效果（可能为空 = 本次输入与下拉无关） */
    fun handle(input: PullInput): List<PullEffect> = when (input) {
        is PullInput.Down -> {
            downY = input.y
            pulling = false
            distancePulled = 0f
            emptyList()
        }
        // 硬约束（票 #53）：滚轮滚动不改变下拉状态，也不产生任何效果
        is PullInput.Scroll -> emptyList()
        is PullInput.Drag -> onDrag(input)
        PullInput.Up -> onUp()
        PullInput.Cancel -> onCancel()
    }

    private fun onDrag(drag: PullInput.Drag): List<PullEffect> {
        val start = downY ?: return emptyList()
        if (!pulling) {
            // 未进入下拉：要求「列表在顶部」且「向下拉超过触摸斜率」，否则整段交回给常规滚动
            if (!drag.atTop || drag.y - start <= touchSlopPx) return emptyList()
            pulling = true
        }
        val before = distancePulled
        distancePulled = (distancePulled + drag.deltaY).coerceAtLeast(0f)
        if (distancePulled == 0f && before == 0f && drag.deltaY < 0f) {
            // 向上推且没有位移可回：交回常规滚动（本次按下不再介入）
            pulling = false
            return emptyList()
        }
        return listOf(PullEffect.Offset(offsetPx))
    }

    private fun onUp(): List<PullEffect> {
        if (!pulling) {
            downY = null
            return emptyList()
        }
        val triggered = reachedThreshold
        downY = null
        pulling = false
        distancePulled = 0f
        return if (triggered) listOf(PullEffect.Trigger, PullEffect.Reset) else listOf(PullEffect.Reset)
    }

    private fun onCancel(): List<PullEffect> {
        downY = null
        pulling = false
        distancePulled = 0f
        return listOf(PullEffect.Reset)
    }

    companion object {
        /** 阻尼：拖拽 1px，指示器滑出 0.5px（与现成下拉组件同量级） */
        const val DRAG_MULTIPLIER: Float = 0.5f
    }
}
