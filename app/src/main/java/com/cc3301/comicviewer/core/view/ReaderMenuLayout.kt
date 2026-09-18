package com.cc3301.comicviewer.core.view

/**
 * 阅读菜单的预览格尺寸（票 #42，纯函数，由 [ReaderMenuLayoutTest] 锁定）。
 *
 * 背景（维护者验收原文：「预览图太小…可以参照 Perfect-viewer 去修改」）：预览格原来写死 42×58dp，
 * 5 格 + 4 个间隙共 234dp，而 360dp 屏的面板内宽约 320dp —— 右边空着近 90dp。
 * 现在按面板内宽**等分**：格宽 = (内宽 − 间隙 × 4) / 5，高 = 格宽 × 既有高宽比，
 * 越界格仍不渲染，首页/末页只有 2–3 格时整排居中。
 */
object ReaderMenuLayout {

    /** 预览格数（仍是当前页 ±2 共 5 格，票面 Out of scope 不改格数） */
    const val PREVIEW_CELLS: Int = 5

    /** 预览格间隙（与原实现一致） */
    const val PREVIEW_GAP_DP: Float = 6f

    /** 预览格高宽比（沿用原 42:58，票 #42 只把宽度变成入参） */
    const val PREVIEW_ASPECT: Float = 58f / 42f

    /** 单格宽度：面板内宽等分（宽度不足时不为负） */
    fun previewCellWidth(panelInnerWidthDp: Float, gapDp: Float = PREVIEW_GAP_DP): Float {
        val usable = panelInnerWidthDp - gapDp * (PREVIEW_CELLS - 1)
        return (usable / PREVIEW_CELLS).coerceAtLeast(0f)
    }

    /** 单格高度：宽度 × 既有高宽比 */
    fun previewCellHeight(cellWidthDp: Float): Float = cellWidthDp * PREVIEW_ASPECT
}
