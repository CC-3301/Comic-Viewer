package com.cc3301.comicviewer.core.view

/** 未解码页在比例未知时的固定占位高（dp） */
const val PAGE_PLACEHOLDER_HEIGHT_DP: Float = 400f

/**
 * 条漫未解码页的占位高（dp，由 [PagePlaceholderHeightTest] 锁定）：**唯一出处**。
 *
 * 未解码页没有位图、也就没有真实页高；用同一本书里已知的页比例（宽 / 高）和页容器宽度推一个，
 * 图到货时高度才不用换，视口内容不会整体位移一次。
 *
 * [pageAspect] 为 null = 这本书还没有任何一个已画出来的页可比照（刚进书的第一屏），
 * 退回固定占位高 [PAGE_PLACEHOLDER_HEIGHT_DP]。
 * 宽度非正数（布局首帧可能给 0）、比例非正数 / 非有限、或除出来的高度不是有限正数时，同样退回固定占位高。
 */
fun pagePlaceholderHeightDp(containerWidthDp: Float, pageAspect: Float?): Float {
    if (!containerWidthDp.isFinite() || containerWidthDp <= 0f) return PAGE_PLACEHOLDER_HEIGHT_DP
    if (pageAspect == null || !pageAspect.isFinite() || pageAspect <= 0f) return PAGE_PLACEHOLDER_HEIGHT_DP
    val heightDp = containerWidthDp / pageAspect
    return if (heightDp.isFinite() && heightDp > 0f) heightDp else PAGE_PLACEHOLDER_HEIGHT_DP
}
