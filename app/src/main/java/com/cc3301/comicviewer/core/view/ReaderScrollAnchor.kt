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
 * 记位置：顶部可见页 + 页内像素偏移折成百分比。百分比落在 0..1 的唯一出处。
 *
 * 页高非正数（占位高还没测到）时没有可折的分母，百分比记 0；
 * 偏移超出页高、负数（布局首帧）都夹到 0..1。
 */
internal fun readerScrollAnchor(page: Int, offsetPx: Int, pageHeightPx: Int): ReaderScrollAnchor {
    val percent = if (pageHeightPx > 0) {
        (offsetPx.toFloat() / pageHeightPx).coerceIn(0f, 1f)
    } else {
        0f
    }
    return ReaderScrollAnchor(page = page, percent = percent)
}

/** 恢复位置：按**恢复那一刻的页高**把百分比折回像素偏移（与 [readerScrollAnchor] 互为逆运算） */
internal fun readerScrollOffsetPx(anchor: ReaderScrollAnchor, pageHeightPx: Int): Int =
    (anchor.percent * pageHeightPx).roundToInt()

/**
 * 随位置一起保存的页比例范围：锚点页**前后各** [READER_SAVED_ASPECT_REACH] 页。
 *
 * 一屏可见的页自锚点页往下排；页高很少矮过半个屏，前后各 3 页已够覆盖一屏，
 * 再多只会在保存态里堆用不到的页。
 */
internal const val READER_SAVED_ASPECT_REACH: Int = 3

/** 一侧锚点的页比例窗口：锚点页前后各 [READER_SAVED_ASPECT_REACH] 页（书首不越到负页号） */
internal fun readerSavedAspectPages(anchorPage: Int): IntRange =
    maxOf(0, anchorPage - READER_SAVED_ASPECT_REACH)..(anchorPage + READER_SAVED_ASPECT_REACH)

/**
 * 这个页号要不要随位置一起保存：落在**位置锚点页**、或**当前页**任一侧的窗口里。
 *
 * 两颗锚都留：一屏装得下四页以上时，顶部可见页（位置锚点页）可能离视口中线页超过一个窗口；
 * 只按当前页取会漏掉顶部页，恢复时它没有比例、退回固定占位高，画面就会位移。
 * 两段之间**不连续**——只取并集，不把中间整段都算进来。
 */
internal fun readerAspectInSavedWindow(index: Int, anchorPage: Int, currentPage: Int): Boolean =
    index in readerSavedAspectPages(anchorPage) || index in readerSavedAspectPages(currentPage)

/** 恢复后的首个可见页：记住的位置优先，没有就落到入口页 */
internal fun readerRestorePage(anchor: ReaderScrollAnchor?, startIndex: Int): Int = anchor?.page ?: startIndex

/**
 * 恢复后的页内偏移：没有记住的位置就是 0。
 *
 * 不沿用 LazyList 自己的像素保存态——那串像素是按离开那一刻的页高记的，没有位置时套在占位页上会越界，
 * 把顶部页整页顶掉。
 */
internal fun readerRestoreOffsetPx(anchor: ReaderScrollAnchor?, pageHeightPx: Int): Int =
    anchor?.let { readerScrollOffsetPx(it, pageHeightPx) } ?: 0
