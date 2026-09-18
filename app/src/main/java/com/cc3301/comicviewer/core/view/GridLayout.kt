package com.cc3301.comicviewer.core.view

/**
 * 网格档的格子宽度（票 #50，纯函数，由 [GridLayoutTest] 锁定）。
 *
 * 口径（维护者验收原文「网格列表要尽量像 UI-PV.jpg 那样，多利用空间」）：网格档按**设置的固定列数**
 * 排版（不再用"最小列宽自适应"推导），封面宽度 = 格子宽度，因此格子宽度必须与
 * `LazyVerticalGrid` 的 `contentPadding`/`horizontalArrangement` 用同一份常量算出来，
 * 不能两处各写一份（否则封面会与格子差几个 dp、两侧露白）。
 */
internal fun gridCellWidth(
    availableDp: Float,
    columns: Int,
    contentPaddingDp: Float,
    spacingDp: Float,
): Float {
    if (columns <= 0) return 0f
    val usable = availableDp - contentPaddingDp * 2 - spacingDp * (columns - 1)
    return (usable / columns).coerceAtLeast(0f)
}
