package com.cc3301.comicviewer.core.reader

/**
 * 放大状态（票 08，spec 故事 31-34）。
 *
 * 缩放锚点用「页内比例」[originX]/[originY] 表示，渲染时交给 `graphicsLayer` 的 `transformOrigin`，
 * 因此「以双击位置为中心」不依赖节点尺寸与视口尺寸的关系——条漫的长图节点（高度远大于视口）同样成立。
 * [offsetX]/[offsetY] 是锚点缩放之后的平移量（像素），由拖动/捏合累加。
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
 * 平移边界钳制（spec 故事 34）：以锚点缩放后，把内容限制在视口内、不露出页外空白。
 *
 * [constrainVertical] = false（条漫）时垂直偏移归零，垂直方向交给列表滚动；
 * 单页模式传 true，双向限制在图片显示区域内。
 */
fun clampZoomOffset(
    state: ZoomState,
    viewportW: Float,
    viewportH: Float,
    pageW: Float,
    pageH: Float,
    constrainVertical: Boolean,
): ZoomState {
    if (!state.isZoomed) return ZoomState()
    return state.copy(
        offsetX = clampAxis(state.offsetX, viewportW, pageW, state.scale, state.originX),
        offsetY = if (constrainVertical) {
            clampAxis(state.offsetY, viewportH, pageH, state.scale, state.originY)
        } else {
            0f
        },
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
 * 单轴平移钳制。以锚点（页内比例 [origin]）缩放时，未缩放页长 [page] 变为 `page * scale`：
 * 左边缘位置 = `origin*page*(1-scale) + offset`，右边缘 = 左边缘 + `page*scale`。
 * 令左边缘 ≤ 0、右边缘 ≥ [viewport]，即得可平移区间；内容比视口小时居中。
 */
private fun clampAxis(offset: Float, viewport: Float, page: Float, scale: Float, origin: Float): Float {
    val content = page * scale
    if (content <= viewport || page <= 0f) return (viewport - content) / 2f
    val maxPositive = origin * page * (scale - 1f)
    val minNegative = viewport - content + maxPositive
    return offset.coerceIn(minNegative, maxPositive)
}
