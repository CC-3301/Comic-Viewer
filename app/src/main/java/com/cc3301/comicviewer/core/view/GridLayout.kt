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

/**
 * 网格档**网格项高度上限**（票 #106，纯函数，由 [GridLayoutTest] 锁定）：
 * 可视高度扣掉上下 `contentPadding`——格子（封面 + 格子内间距 + 名字块）最多占这么高。
 *
 * 名字块高与格子内间距**不在这里扣**：它们由布局在权重分配里真量后让出（`BrowserScreen` 的
 * `BrowserGridCell`：非权重的名字块先测，封面拿剩下的高度），因此本函数不依赖「行高 × 行数」这类
 * 字体度量推算（推算偏小就会把名字行挤出可视区）。
 *
 * [visibleHeightDp] 取格子真拿到的纵向约束（`BoxWithConstraints.maxHeight`，已扣掉顶栏与系统栏），
 * 不自己估摸屏幕高度（票 #106 AC3：不得把系统栏/底部导航算进可视高度）。
 */
internal fun gridCellMaxHeight(visibleHeightDp: Float, contentPaddingDp: Float): Float =
    (visibleHeightDp - contentPaddingDp * 2).coerceAtLeast(0f)

/**
 * 网格格子内**名字行的摆位**（票 #106 r2，纯函数，由 [GridLayoutTest] 锁定）：
 * [width] = 名字行宽度、[left] = 名字行左缘（相对格左缘），单位与入参相同（界面按 px 传入）。
 */
internal data class GridNameRow(val width: Float, val left: Float)

/**
 * 名字行摆位（票 #106 批次 6 定版 D6-A，纯函数，由 [GridLayoutTest] 锁定）：
 * 名字行宽度 = 封面宽度、左缘与封面左缘对齐——封面水平居中于格子，名字因此与封面**同宽同中线**
 * （真机未通过的现象正是名字铺满格宽、居左，收缩时与封面不在一条中线上）。
 *
 * 封面未收缩时封面宽 = 格宽（[CoverLayout.gridCellSize]）⇒ 左缘为 0、名字行 = 格宽，
 * 竖屏 2/3/4 格因此与改动前逐像素一致（票 #106 AC8）。
 * 兜底：封面宽超出格宽（理论上不该发生）时左缘夹到 0，不把名字行推出格左缘。
 */
internal fun gridNameRow(cellWidth: Float, coverWidth: Float): GridNameRow {
    val width = coverWidth.coerceAtLeast(0f)
    return GridNameRow(width, ((cellWidth - width) / 2f).coerceAtLeast(0f))
}
