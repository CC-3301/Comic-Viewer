package com.cc3301.comicviewer.core.reader

/**
 * 双击缩放过渡（票 #59，spec 故事 31/32；纯函数，由 [ZoomTransitionTest] 锁定）。
 *
 * 维护者反馈「放大镜的动画太生硬」：双击放大与再次双击恢复适屏原来都是一帧内瞬变。
 * 现在它们**缩放与位移一起平滑过渡**（约 200–300ms、缓入缓出），而**手势驱动**（双指缩放、
 * 放大后单指平移）继续逐帧跟手。渲染侧的按页缩放状态因此只有两条写入口：
 * - **目标值变化**（双击）→ 逐帧写入 [zoomTransitionFrame] 的插值（动画）；
 * - **手势驱动**（双指/平移）→ 直接写入实时状态，并取消该页在飞的动画（动画让位于手指）。
 *
 * 这里只放纯计算（端点、插值、缓动、双击目标）；帧循环、取消时机与「哪条路径要动画」的接线留在
 * `ReaderScreen`（仓库没有 Compose UI 测试基建，组合层按 SPEC 的 Testing Decisions 走手动验收，
 * 先例见 `ReaderNeighborsWarmupTest` 的同类声明）。
 *
 * ## 锚点为什么不参与插值
 *
 * 映射是 `x = origin·extent·(1−scale) + scale·content + offset`（[mapContentToScreen]），`origin` 项带
 * `(1−scale)` 系数：在 `scale = 1` 那一端它恒为 0（适屏时 origin 取什么值都不影响画面）。
 * 若把 origin 也逐帧插值，不动点会**先漂出去、到终点再回来**（适屏端的 origin 与缩放端的锚点不同），
 * 正是要避免的生硬感。把两端锚点都钉在「放大那一端」（双击位置）后，整段过渡里双击点的屏幕位置恒定
 * （[ZoomTransitionTest] 有不变式用例）；适屏端的 origin 换成它视觉上不可见。
 */

/** 双击放大/恢复适屏的过渡时长（ms）：票面要求的 200–300ms 缓入缓出 */
const val ZOOM_ANIMATION_MILLIS = 240

/**
 * 一次双击缩放过渡的两个端点（锚点已钉死，见文件头）：
 * [from] = 起点的 scale/offset + 放大端的锚点，[to] = 目标的 scale/offset + 同一个锚点。
 */
data class ZoomTransition(val from: ZoomState, val to: ZoomState)

/**
 * 双击过渡的端点：锚点取两端里「放大那一端」（[ZoomState.isZoomed]）；两端都不放大时（双击路径产生不了，
 * 只有手势路径的实时状态可能如此）取目标锚点。
 */
fun zoomTransition(display: ZoomState, target: ZoomState): ZoomTransition {
    val anchor = if (target.isZoomed) target else display
    return ZoomTransition(
        from = display.copy(originX = anchor.originX, originY = anchor.originY),
        to = target.copy(originX = anchor.originX, originY = anchor.originY),
    )
}

/**
 * 过渡到已过时间 [elapsedNanos] 时的那一帧：进度 = 已过时间 / [ZOOM_ANIMATION_MILLIS]，钳在 [0, 1] 后走缓入缓出。
 * 因此首帧即起点、到时长即终点、超时（或时钟回退）仍是端点——不越界、不反弹。
 */
fun zoomTransitionFrame(transition: ZoomTransition, elapsedNanos: Long): ZoomState =
    lerpZoom(transition.from, transition.to, easedProgress(elapsedNanos))

/**
 * 双击的**目标**状态（spec 故事 31/32）：已是放大态 → 恢复适屏（[ZoomState] 默认值）；否则以双击点
 * （页内坐标 localX/localY，页显示尺寸 pageW×pageH）为锚放大（[doubleTapZoom]）。
 *
 * 判定读的是**当前**状态，因此动画进行中或手势中途再次双击都以最新状态为准（放大未完再双击 → 复位）。
 */
fun doubleTapZoomTarget(
    current: ZoomState,
    localX: Float,
    localY: Float,
    pageW: Float,
    pageH: Float,
    scale: Float,
): ZoomState = if (current.isZoomed) ZoomState() else doubleTapZoom(localX, localY, pageW, pageH, scale)

/** 过渡进度：已过时间折算成 [0, 1] 的系数，再走缓入缓出 */
private fun easedProgress(elapsedNanos: Long): Float {
    val t = (elapsedNanos / (ZOOM_ANIMATION_MILLIS * 1_000_000f)).coerceIn(0f, 1f)
    // smoothstep（两端导数为 0＝缓入缓出，中点为半程）
    return t * t * (3f - 2f * t)
}

/** 逐字段插值（两端 origin 已被 [zoomTransition] 钉成同一个值，这里照常逐字段算）；两端按值返回端点（浮点插值在 t = 1 处不保证逐位相等） */
private fun lerpZoom(from: ZoomState, to: ZoomState, t: Float): ZoomState = when {
    t <= 0f -> from
    t >= 1f -> to
    else -> ZoomState(
        scale = lerp(from.scale, to.scale, t),
        offsetX = lerp(from.offsetX, to.offsetX, t),
        offsetY = lerp(from.offsetY, to.offsetY, t),
        originX = lerp(from.originX, to.originX, t),
        originY = lerp(from.originY, to.originY, t),
    )
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
