package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 显示盒的字节数（测试共用的一份）：格高走仓库唯一来源 [CoverLayout.gridCellHeight]，
 * 取整口径与生产一致（`roundToInt`，与 `CoverDecode.visibleBand` 的保留高度同口径，不是截断）。
 *
 * 放测试源集（`core.view` 与 `ui` 两个测试包共用），生产侧不需要这个概念——生产算的是
 * [CoverDecode.Plan] 自己的字节数。
 */
internal fun gridBoxByteCount(targetWidthPx: Int): Int =
    targetWidthPx * CoverLayout.gridCellHeight(targetWidthPx.toFloat()).roundToInt() * CoverDecode.BITMAP_BYTES_PER_PIXEL

/**
 * 高频残差 RMS（斑点量尺，`core.view` 与 `ui` 两个测试包共用）：每像素取与右/下两邻的差（高通响应），
 * 对红通道取均方根。印刷网点（细间距规则点阵）混叠成斑点的强度、以及去网点后的剩余，都用它量。
 */
internal fun highFrequencyRms(pixels: IntArray, width: Int, height: Int): Double {
    var sum = 0.0
    var count = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val v = (pixels[y * width + x] ushr 16) and 0xFF
            if (x + 1 < width) {
                val d = v - ((pixels[y * width + x + 1] ushr 16) and 0xFF)
                sum += d.toDouble() * d
                count++
            }
            if (y + 1 < height) {
                val d = v - ((pixels[(y + 1) * width + x] ushr 16) and 0xFF)
                sum += d.toDouble() * d
                count++
            }
        }
    }
    return kotlin.math.sqrt(sum / count)
}
