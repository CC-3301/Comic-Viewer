package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 条漫的阅读位置：**页号 + 该页内已滚过的百分比**（spec §重建后的位置恢复）。
 *
 * 不记像素偏移：重建后页还没解码，页高先按记住的页比例摆，与记录那一刻的页高不必相同；
 * 像素偏移会在页高变小时越出这一页，把滚动锚点顶到下一页。百分比按页折算，页高怎么变都还落在这一页里。
 */
internal data class ReaderScrollAnchor(
    /** 顶部可见页 */
    val page: Int,
    /** 该页内已滚过的比例：0 = 页首，1 = 页尾 */
    val percent: Float,
)

/**
 * 记位置：顶部可见页 + 页内像素偏移折成百分比（唯一出处）。
 *
 * 页高非正数 / 非有限（占位高还没测到）时没有可折的分母，百分比记 0；
 * 偏移超出页高、负数（布局首帧）都夹到 0..1。
 */
internal fun readerScrollAnchor(page: Int, offsetPx: Int, pageHeightPx: Int): ReaderScrollAnchor {
    val percent = if (pageHeightPx > 0) {
        (offsetPx.toFloat() / pageHeightPx).coerceIn(0f, 1f)
    } else {
        0f
    }
    return ReaderScrollAnchor(page = page.coerceAtLeast(0), percent = percent)
}

/** 恢复位置：按**恢复那一刻的页高**把百分比折回像素偏移（与 [readerScrollAnchor] 互为逆运算） */
internal fun readerScrollOffsetPx(anchor: ReaderScrollAnchor, pageHeightPx: Int): Int {
    if (pageHeightPx <= 0) return 0
    if (!anchor.percent.isFinite()) return 0
    return (anchor.percent.coerceIn(0f, 1f) * pageHeightPx).roundToInt().coerceIn(0, pageHeightPx)
}
