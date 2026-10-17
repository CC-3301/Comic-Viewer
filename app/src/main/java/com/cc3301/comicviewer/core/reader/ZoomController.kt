package com.cc3301.comicviewer.core.reader

import com.cc3301.comicviewer.core.input.ZoomGesture
import com.cc3301.comicviewer.core.input.ZoomGestureEffect
import com.cc3301.comicviewer.core.input.ZoomGestureFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 阅读器放大状态的持有者与写入者（纯逻辑，由 [ZoomControllerTest] 锁定）。
 *
 * 按页的 [ZoomState]、手势逐段基线（[ZoomGesture]）、双指缩放的锚点反解、平移边界钳制
 * （[clampZoomOffset]）、双击过渡的逐帧插值（[zoomTransitionFrame]）、视口变化后的重钳制都在这里。
 * 调用方只做事件翻译：**喂帧**（[onGestureFrame] / [onDoubleTap] / [onViewportChanged]）与
 * **读状态**（[stateOf]）——它不认识 origin / offset / 倍率，也不做锚点与钳制的算式。
 *
 * 帧里带几何（[ZoomGeometry]：视口尺寸 + 作用页的显示矩形），锚点换算与钳制都按它算。
 * 作用页由调用方在按下那一刻锁定（指针位置所在的那一页），本模块不重算命中。
 *
 * 状态每变一次回调一次 [onState]（页号 + 新状态）：调用方把它写进渲染用的快照表，组合层据此重画。
 * 权威状态在本模块内，回调是投影。
 *
 * 双击过渡的帧循环由本模块自己跑：挂在 [scope] 上、跟着 [frameClock]（调用方传渲染节拍
 * `withFrameNanos`）逐帧写状态，到 [ZOOM_ANIMATION_MILLIS] 收工。某页有手势效果落地时，
 * 该页在飞的过渡先取消——手势驱动逐帧跟手，动画让位于手指。
 */
internal class ZoomController(
    /** 过渡帧循环挂在这里：调用方离开组合即随作用域一起取消 */
    private val scope: CoroutineScope,
    /** 渲染节拍（单调纳秒）：过渡逐帧跟着它走 */
    private val frameClock: suspend () -> Long,
    /** 某页状态变化（调用方据此重画） */
    private val onState: (page: Int, state: ZoomState) -> Unit,
) {

    /** 按页的放大状态（权威）：翻页/回翻不复位，退出阅读器随本对象丢弃 */
    private val states = mutableMapOf<Int, ZoomState>()

    /** 在飞的过渡，按页一份 */
    private val transitions = mutableMapOf<Int, Job>()

    /** 本段手势的判定状态机（每段一份，段首由 [ZoomFrame.segmentStart] 重开基线） */
    private var gesture: ZoomGesture? = null

    /** 读状态：没有记录过的页就是适屏 */
    fun stateOf(page: Int): ZoomState = states[page] ?: ZoomState()

    /**
     * 喂一帧手势。返回本帧**是否消费指针事件**——该页未放大时单指拖动整段让位给列表，此时为 false。
     */
    fun onGestureFrame(frame: ZoomFrame): Boolean {
        if (frame.segmentStart) gesture = ZoomGesture(frame.panSlopPx)
        val machine = gesture ?: return false
        val page = frame.geometry.page
        val step = machine.handle(
            ZoomGestureFrame(
                pointerCount = frame.pointerCount,
                centroidX = frame.centroidX,
                centroidY = frame.centroidY,
                spanPx = frame.spanPx,
                // 该页此刻是否放大决定单指拖动归谁：放大 → 平移；未放大 → 整段让位给列表
                panEnabled = stateOf(page).isZoomed,
            ),
        )
        if (frame.pointerCount <= 0) gesture = null
        step.effects.forEach { applyEffect(page, frame.geometry, it) }
        return step.consume
    }

    /**
     * 双击（spec 故事 31/32）：已是放大态 → 恢复适屏；否则以双击点（[x]/[y]，视口本地 px）为中心放大。
     * 两条路都走 [ZOOM_ANIMATION_MILLIS] 的过渡；判定读的是**当前**状态，动画中或手势中途再次双击都以最新状态为准。
     */
    fun onDoubleTap(geometry: ZoomGeometry, x: Float, y: Float, scale: Float) {
        val page = geometry.page
        // 锚点用页内坐标：条漫长图节点中心 ≠ 视口中心，否则双击点会飞走
        val localX = if (geometry.measured) x - geometry.pageLeft else x
        val localY = if (geometry.measured) y - geometry.pageTop else y
        animateTo(
            page,
            geometry,
            doubleTapZoomTarget(stateOf(page), localX, localY, geometry.width, geometry.height, scale),
        )
    }

    /**
     * 视口变化（旋转 / 多窗口）后按新页框重新钳制已有放大状态。
     * 页框由调用方按页现取（[geometryOf]）——布局不在本模块手里。
     */
    fun onViewportChanged(geometryOf: (page: Int) -> ZoomGeometry) {
        states.keys.toList().forEach { page ->
            write(page, geometryOf(page), stateOf(page))
        }
    }

    /**
     * 手势效果落地：单指平移直接累加；双指缩放以**双手指质心**为锚点折算到该页——质心下的内容在
     * 缩放前后不动，同时带上质心自身的位移。映射语义同 [mapContentToScreen]：页内内容点 p 的屏幕位置 =
     * `origin·页长·(1−scale) + scale·p + offset`，让「当前在质心下的那个内容点」缩放后仍落在质心
     * （再叠加质心位移），即得新的 offset。
     */
    private fun applyEffect(page: Int, geometry: ZoomGeometry, effect: ZoomGestureEffect) {
        // 手势驱动逐帧跟手：该页在飞的过渡先让位
        cancelTransition(page)
        val current = stateOf(page)
        when (effect) {
            is ZoomGestureEffect.Pan -> write(
                page,
                geometry,
                current.copy(
                    offsetX = current.offsetX + effect.deltaX,
                    offsetY = current.offsetY + effect.deltaY,
                ),
            )

            is ZoomGestureEffect.Pinch -> {
                val newScale = clampPinchScale(current.scale * effect.zoomChange)
                if (!geometry.measured || newScale == current.scale) {
                    // 页尚未测量或倍率没变（已到钳制上限）：只把质心位移当平移
                    write(
                        page,
                        geometry,
                        current.copy(
                            scale = newScale,
                            offsetX = current.offsetX + effect.panChangeX,
                            offsetY = current.offsetY + effect.panChangeY,
                        ),
                    )
                } else {
                    val pageW = geometry.width
                    val pageH = geometry.height
                    // 质心在视口坐标里，先折算成该页的页内坐标；再反解「此刻在质心下的内容点」
                    val centroidX = effect.centroidX - geometry.pageLeft
                    val centroidY = effect.centroidY - geometry.pageTop
                    val contentX =
                        (centroidX - current.originX * pageW * (1f - current.scale) - current.offsetX) / current.scale
                    val contentY =
                        (centroidY - current.originY * pageH * (1f - current.scale) - current.offsetY) / current.scale
                    write(
                        page,
                        geometry,
                        ZoomState(
                            scale = newScale,
                            originX = (contentX / pageW).coerceIn(0f, 1f),
                            originY = (contentY / pageH).coerceIn(0f, 1f),
                            offsetX = centroidX + effect.panChangeX - contentX,
                            offsetY = centroidY + effect.panChangeY - contentY,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 双击过渡：逐帧把插值写进状态，跟着 [frameClock] 走渲染节拍，到 [ZOOM_ANIMATION_MILLIS] 收工。
     * 起点就是**当前显示状态**——动画中再次双击或手势接管都从眼下这一帧接上，不回跳、不错位。
     * 钳制参考几何取**双击那一刻的 [geometry]**，整段过渡不变（过渡中该页显示尺寸变化也不改判据）。
     * 同页在飞的过渡先取消，本函数是唯一的过渡起点。
     */
    private fun animateTo(page: Int, geometry: ZoomGeometry, target: ZoomState) {
        cancelTransition(page)
        val transition = zoomTransition(stateOf(page), target)
        transitions[page] = scope.launch {
            val startNanos = frameClock()
            var elapsedNanos = 0L
            while (elapsedNanos < ZOOM_ANIMATION_MILLIS * 1_000_000L) {
                elapsedNanos = frameClock() - startNanos
                write(page, geometry, zoomTransitionFrame(transition, elapsedNanos))
            }
            transitions.remove(page)
        }
    }

    /** 唯一写入口：所有路径（过渡每帧 / 双指缩放 / 平移 / 视口变化）都过边界钳制，不存越界状态 */
    private fun write(page: Int, geometry: ZoomGeometry, state: ZoomState) {
        val clamped = clampZoomOffset(state, geometry.width, geometry.height)
        states[page] = clamped
        onState(page, clamped)
    }

    /** 取消某页在飞的过渡（手势接管、再次双击重算时调） */
    private fun cancelTransition(page: Int) {
        transitions.remove(page)?.cancel()
    }
}

/**
 * 一帧的几何（界面从布局回调拿）：视口尺寸 + 作用页的显示矩形。
 * [pageLeft]/[pageTop] 是页显示矩形相对视口的左上角，[pageW]/[pageH] 是页显示尺寸（未测量时给 0）。
 */
internal data class ZoomGeometry(
    /** 视口宽 */
    val viewW: Float,
    /** 视口高 */
    val viewH: Float,
    /** 作用页（指针位置所在的那一页，手势期间锁定） */
    val page: Int,
    /** 页显示矩形相对视口的左上角 */
    val pageLeft: Float,
    val pageTop: Float,
    /** 页显示尺寸（未测量时给 0） */
    val pageW: Float,
    val pageH: Float,
) {
    /** 页已测量（显示矩形可用） */
    val measured: Boolean get() = pageW > 0f && pageH > 0f

    /** 页宽（未测量时回退视口宽） */
    val width: Float get() = if (measured) pageW else viewW

    /** 页高（未测量时回退视口高） */
    val height: Float get() = if (measured) pageH else viewH
}

/**
 * 一帧手势输入（界面从指针事件与布局回调翻译而来；`core` 不认识 Compose 的指针类型）。
 *
 * [segmentStart] = 本帧是本段手势的第一帧（界面每次进入手势段给 true）：模块据此重开基线，
 * 段被中途打断（取消）也不会拿旧基线起手。
 * [pointerCount] = 按下的指针数（0 = 本段结束，其余指针字段无意义）；[centroidX]/[centroidY] = 质心
 * （视口本地 px）；[spanPx] = 质心到各指针的平均距离（双指缩放的幅度）；
 * [panSlopPx] = 本段的起手斜率（界面按输入源给：鼠标用指针设备斜率、触摸用触摸斜率）。
 */
internal data class ZoomFrame(
    val geometry: ZoomGeometry,
    val segmentStart: Boolean,
    val pointerCount: Int,
    val centroidX: Float,
    val centroidY: Float,
    val spanPx: Float,
    val panSlopPx: Float,
)
