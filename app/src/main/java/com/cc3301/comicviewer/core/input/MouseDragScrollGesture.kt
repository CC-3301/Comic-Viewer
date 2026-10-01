package com.cc3301.comicviewer.core.input

import kotlin.math.abs

/**
 * 鼠标左键拖动 = 纵向滚动的手势状态机（票 #69，纯逻辑，由 [MouseDragScrollGestureTest] 锁定）。
 *
 * **为什么必须自实现**：Compose 的滚动地基 `Modifier.scrollable`（`LazyColumn` / `LazyVerticalGrid` /
 * `verticalScroll` 都由它承载）在 1.7.2 里**明确拒绝鼠标源拖动**——它的 `canDrag` 判定就是
 * `change.type != PointerType.Mouse`（`ScrollableKt.CanDragCalculation`）。滚轮不受影响（滚轮走的是
 * `ScrollableNode.processMouseWheelEvent`，与 canDrag 无关），所以现象正是维护者报的「滚轮能滚、鼠标按住拖动不滚」，
 * 且浏览页与阅读器一起失效（同一处内置判定）。本状态机补上「哪一段归鼠标拖动、滚动多少、松手惯性多大」，
 * Compose 侧只做事件翻译与落状态（`ui/MouseDragScroll`）。
 *
 * 与相邻手势的分层（票 #69 验收）：
 * - **下拉更新（[PullRefreshGesture]）先消费**：它在 `PointerEventPass.Initial` 里、且在本修饰符外层，
 *   列表在顶部向下拖动时先吃掉拖拽增量。本状态机看到 [MouseDragInput.Drag.consumed] 为真即**整段让位**
 *   （本次按下不再滚动）——与内建滚动同一口径（`awaitPointerSlopOrCancellation` 一旦发现增量被别人消费
 *   就放弃整段手势），因此「顶部下拉」不会变成「又刷新又滚动」，鼠标与触摸在同一次按下里的归属也一致。
 * - **触摸/触控笔/右键/中键一律不介入**（[MouseDragInput.Down.fromMousePrimary] 为假时整段空转）：内建滚动照旧，
 *   触摸拖动行为与改动前逐字一致。
 * - **点击**：累计纵向位移没越过触摸斜率（[touchSlopPx]）之前不产生任何效果、也不消费增量，
 *   因此阅读器的左键单击仍按触摸区域执行（拖动结束也不翻页/弹菜单）。
 * - **只看纵向累计位移**（与内建滚动、下拉更新同一口径：斜率卡在主轴，不做横纵主次判定），
 *   因此纯横向拖动（放大后的平移）不会被本手势抢走。
 *
 * 滚动量符号沿用容器口径：正 = 向后滚（内容上移），与指针位移反向。
 */
class MouseDragScrollGesture(private val touchSlopPx: Float) {

    /** 按下点的纵坐标（鼠标拖动原点；null = 本段未按下或已让位/复位） */
    private var downY: Float? = null

    /** 上一次事件的纵坐标（增量由相邻两次相减得到） */
    private var lastY: Float = 0f

    /** 本段是否由鼠标左键发起（触摸/触控笔与右键/中键为假 → 整段空转） */
    private var fromMousePrimary: Boolean = false

    /** 已越过触摸斜率、本段归鼠标拖动 */
    private var scrolling: Boolean = false

    /** 处理一个输入，返回要施加到滚动容器上的效果（空 = 本次输入与鼠标拖动无关，不得消费） */
    fun handle(input: MouseDragInput): List<MouseDragEffect> = when (input) {
        is MouseDragInput.Down -> {
            downY = input.y
            lastY = input.y
            fromMousePrimary = input.fromMousePrimary
            scrolling = false
            emptyList()
        }
        is MouseDragInput.Drag -> onDrag(input)
        is MouseDragInput.Up -> onUp(input.velocityY)
        MouseDragInput.Cancel -> {
            reset()
            emptyList()
        }
    }

    private fun onDrag(drag: MouseDragInput.Drag): List<MouseDragEffect> {
        val downY = downY ?: return emptyList()
        val deltaY = drag.y - lastY
        lastY = drag.y
        // 触摸/触控笔/右键/中键：内建滚动负责，本状态机不介入
        if (!fromMousePrimary) return emptyList()
        // 外层（下拉更新）已吃掉这一段：整段让位，本次按下不再介入
        if (drag.consumed) {
            reset()
            return emptyList()
        }
        if (!scrolling) {
            // 未越过触摸斜率 = 点击
            if (abs(drag.y - downY) <= touchSlopPx) return emptyList()
            scrolling = true
        }
        return listOf(MouseDragEffect.ScrollBy(deltaPx = -deltaY))
    }

    private fun onUp(velocityY: Float): List<MouseDragEffect> {
        val fling = if (fromMousePrimary && scrolling) -velocityY else null
        reset()
        // 0 速度不产生效果（没有惯性可言）
        return if (fling == null || fling == 0f) emptyList() else listOf(MouseDragEffect.Fling(velocityPxPerSec = fling))
    }

    private fun reset() {
        downY = null
        fromMousePrimary = false
        scrolling = false
    }
}

/** 鼠标拖动的手势输入（由 `ui/MouseDragScroll` 从指针事件翻译而来） */
sealed interface MouseDragInput {

    /**
     * 指针按下。[fromMousePrimary] 为假 = 触摸/触控笔或右键/中键（本状态机整段不介入，内建滚动照旧）。
     * [y] 为容器本地纵坐标，只用差值。
     */
    data class Down(val y: Float, val fromMousePrimary: Boolean) : MouseDragInput

    /**
     * 指针移动。[consumed] = 本次增量已被外层手势（下拉更新）消费——本状态机据此整段让位。
     * [y] 为容器本地纵坐标（被消费只影响增量口径，不影响坐标）。
     */
    data class Drag(val y: Float, val consumed: Boolean) : MouseDragInput

    /** 指针抬起。[velocityY] = 指针纵向速度（px/s，正 = 向下），用来算松手惯性 */
    data class Up(val velocityY: Float) : MouseDragInput

    /** 手势被取消（指针消失、窗口失焦等）：就地复位，不给惯性 */
    data object Cancel : MouseDragInput
}

/** 鼠标拖动对滚动容器发出的效果 */
sealed interface MouseDragEffect {

    /** 直接滚动 [deltaPx]（正 = 向后滚 / 内容上移）；产生它的那次输入应被消费掉 */
    data class ScrollBy(val deltaPx: Float) : MouseDragEffect

    /** 松手惯性：按 [velocityPxPerSec]（正 = 向后滚）减速滚动 */
    data class Fling(val velocityPxPerSec: Float) : MouseDragEffect
}
