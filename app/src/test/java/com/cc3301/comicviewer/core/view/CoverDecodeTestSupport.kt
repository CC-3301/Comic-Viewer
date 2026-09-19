package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 显示盒的字节数（票 #81 测试共用的一份）：格高走仓库唯一来源 [CoverLayout.gridCellHeight]，
 * 取整口径与生产一致（`roundToInt`，与 `CoverDecode.visibleBand` 的保留高度同口径，不是截断）。
 *
 * 放测试源集（`core.view` 与 `ui` 两个测试包共用），生产侧不需要这个概念——生产算的是
 * [CoverDecode.Plan] 自己的字节数。
 */
internal fun gridBoxByteCount(targetWidthPx: Int): Int =
    targetWidthPx * CoverLayout.gridCellHeight(targetWidthPx.toFloat()).roundToInt() * CoverDecode.BITMAP_BYTES_PER_PIXEL
