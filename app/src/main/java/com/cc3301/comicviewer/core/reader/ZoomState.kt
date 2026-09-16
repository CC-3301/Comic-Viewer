package com.cc3301.comicviewer.core.reader

/**
 * 阅读缩放状态（spec 双击放大 / 双指缩放）：以视图中心为缩放锚点，记录倍率与平移量。
 * offsetX/offsetY 为相对适屏位置的像素位移；scale = 1 时为适屏（无平移）。
 */
data class ZoomState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** 是否处于放大状态；浮点容差避免 1.0000001 被判为已放大 */
    val isZoomed: Boolean get() = scale > 1.0001f
}

/** 双击放大的默认倍率 */
const val DEFAULT_DOUBLE_TAP_SCALE = 2.0f

/** 双击放大倍率下限（设置项可调下限） */
const val MIN_DOUBLE_TAP_SCALE = 1.5f

/** 双击放大倍率上限（设置项可调上限） */
const val MAX_DOUBLE_TAP_SCALE = 4.0f

/** 双指缩放下限：1 即适屏 */
const val MIN_PINCH_SCALE = 1f

/** 双指缩放上限 */
const val MAX_PINCH_SCALE = 8f

/** 双击倍率钳制到 [MIN_DOUBLE_TAP_SCALE, MAX_DOUBLE_TAP_SCALE] */
fun clampDoubleTapScale(v: Float): Float = v.coerceIn(MIN_DOUBLE_TAP_SCALE, MAX_DOUBLE_TAP_SCALE)

/** 双指倍率钳制到 [MIN_PINCH_SCALE, MAX_PINCH_SCALE] */
fun clampPinchScale(v: Float): Float = v.coerceIn(MIN_PINCH_SCALE, MAX_PINCH_SCALE)

/**
 * 双击放大：以视图中心为缩放锚点，使点击点 (posX, posY) 在缩放前后保持不动。
 * 位移 = 点击点相对中心的距离 × (1 - scale)（scale > 1 时向中心反向偏移）。
 */
fun doubleTapZoom(
    posX: Float,
    posY: Float,
    viewportW: Float,
    viewportH: Float,
    scale: Float,
): ZoomState = ZoomState(
    scale = scale,
    offsetX = (posX - viewportW / 2f) * (1f - scale),
    offsetY = (posY - viewportH / 2f) * (1f - scale),
)

/**
 * 将缩放平移钳制在图像边界内。
 *
 * 水平方向最大位移 maxX = viewportW * (scale - 1) / 2（放大后超出视口的一半）。
 * 垂直方向由 [constrainVertical] 决定：
 * - 条漫（webtoon）传 false：只做水平平移，垂直方向继续交给列表滚动，offsetY 归 0；
 * - 单页（paged）传 true：垂直方向同样钳制，画面限制在图片边界内。
 *
 * scale <= 1 时无放大，返回适屏状态（全 0）。
 */
fun clampZoomOffset(
    state: ZoomState,
    viewportW: Float,
    viewportH: Float,
    constrainVertical: Boolean,
): ZoomState {
    if (state.scale <= 1f) return ZoomState()

    val maxX = viewportW * (state.scale - 1f) / 2f
    val maxY = viewportH * (state.scale - 1f) / 2f
    return ZoomState(
        scale = state.scale,
        offsetX = state.offsetX.coerceIn(-maxX, maxX),
        offsetY = if (constrainVertical) state.offsetY.coerceIn(-maxY, maxY) else 0f,
    )
}
