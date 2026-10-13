package com.cc3301.comicviewer.core.reader

/**
 * 放大状态（spec 故事 31-34）。
 *
 * 缩放锚点用「页内比例」[originX]/[originY] 表示，渲染时交给 `graphicsLayer` 的 `transformOrigin`，
 * 因此「以双击位置为中心」不依赖节点尺寸与视口尺寸的关系——条漫的长图节点（高度远大于视口）同样成立。
 * [offsetX]/[offsetY] 是锚点缩放之后的平移量（像素），由拖动/捏合累加，钳制见 [clampZoomOffset]。
 */
data class ZoomState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val originX: Float = 0.5f,
    val originY: Float = 0.5f,
) {
    /** 浮点容差判定是否处于放大状态 */
    val isZoomed: Boolean get() = scale > 1.0001f
}

/** 双击放大的默认倍率（spec 故事 31） */
const val DEFAULT_DOUBLE_TAP_SCALE = 2.0f

/** 双击放大倍率下限（设置项可调范围） */
const val MIN_DOUBLE_TAP_SCALE = 1.5f

/** 双击放大倍率上限（设置项可调范围） */
const val MAX_DOUBLE_TAP_SCALE = 4.0f

/** 双指缩放下限：1 即适屏（spec 故事 33） */
const val MIN_PINCH_SCALE = 1f

/** 双指缩放上限（spec 故事 33） */
const val MAX_PINCH_SCALE = 8f

/** 双击倍率钳制到 [MIN_DOUBLE_TAP_SCALE, MAX_DOUBLE_TAP_SCALE] */
fun clampDoubleTapScale(v: Float): Float = v.coerceIn(MIN_DOUBLE_TAP_SCALE, MAX_DOUBLE_TAP_SCALE)

/** 双指倍率钳制到 [MIN_PINCH_SCALE, MAX_PINCH_SCALE] */
fun clampPinchScale(v: Float): Float = v.coerceIn(MIN_PINCH_SCALE, MAX_PINCH_SCALE)

/**
 * 双击放大（spec 故事 31）：把锚点设在双击点处（页内坐标 localX/localY，页显示尺寸 pageW×pageH）。
 * 锚点由渲染层的 `transformOrigin` 承载 → 放大后该点自动保持不动，无需平移量。
 */
fun doubleTapZoom(
    localX: Float,
    localY: Float,
    pageW: Float,
    pageH: Float,
    scale: Float,
): ZoomState = ZoomState(
    scale = clampDoubleTapScale(scale),
    originX = ratioOf(localX, pageW),
    originY = ratioOf(localY, pageH),
)

/**
 * 平移边界钳制（spec 故事 34）：以锚点缩放后，把内容限制在该页的显示矩形内、不露出页外空白。
 *
 * 参考矩形就是该页的显示矩形本身（也就是渲染层的裁剪框）——条漫与单页同一口径：
 * 条漫一屏多页，因此放大后的画面不会盖住相邻页；单页一屏一页，放大后的内容也不会侵入 Fit 留下的黑边。
 * 两个方向都钳：放大态下条漫也能纵向平移。
 */
fun clampZoomOffset(state: ZoomState, pageW: Float, pageH: Float): ZoomState {
    if (!state.isZoomed) return ZoomState()
    return state.copy(
        offsetX = clampAxis(state.offsetX, pageW, state.scale, state.originX),
        offsetY = clampAxis(state.offsetY, pageH, state.scale, state.originY),
    )
}

/**
 * 页内布局坐标 [content] 在屏幕上的位置（相对缩放层左上角）—— graphicsLayer + transformOrigin 的
 * 映射语义唯一实现，供不变式测试用（锚点在缩放前后必须不动）。
 * 推导：映射 x = origin·extent·(1−scale) + scale·content + offset（extent = 未缩放页长）。
 */
fun mapContentToScreen(content: Float, origin: Float, extent: Float, scale: Float, offset: Float): Float =
    origin * extent * (1f - scale) + scale * content + offset

/** 页内比例（尺寸非法时回退页中心） */
private fun ratioOf(value: Float, extent: Float): Float =
    if (extent > 0f) (value / extent).coerceIn(0f, 1f) else 0.5f

/**
 * 单轴平移钳制。以锚点（页内比例 [origin]）缩放时，页长 [page] 变为 `page * scale`：
 * 左边缘位置 = `origin*page*(1-scale) + offset`，右边缘 = 左边缘 + `page*scale`。
 * 令左边缘 ≤ 0、右边缘 ≥ [page]（即放大后内容始终盖满该页显示矩形），即得可平移区间。
 */
private fun clampAxis(offset: Float, page: Float, scale: Float, origin: Float): Float {
    if (page <= 0f) return 0f
    val content = page * scale
    val maxPositive = origin * page * (scale - 1f)
    val minNegative = page - content + maxPositive
    return offset.coerceIn(minNegative, maxPositive)
}
