package com.cc3301.comicviewer.core.view

/**
 * 阅读菜单的预览格尺寸（票 #42 + 票 #62，纯函数，由 [ReaderMenuLayoutTest] 锁定）。
 *
 * 背景（维护者验收原文：「预览图太小…看 10.jpg，参考 APP-Viewer-GUI-Perfect-Viewer.jpg 来做」）：
 * 预览格原来写死 42×58dp，5 格 + 4 个间隙共 234dp，而 360dp 屏的面板内宽约 320dp —— 右边空着近 90dp。
 * 票 #42 先把宽度改成**按面板内宽等分**：格宽 = (内宽 − 间隙 × 4) / 5，越界格仍不渲染，整排居中。
 * 票 #62 再把**格比例从 58:42（≈1.38）改成 7:4**：1.38 比漫画页面本身（约 1.4–1.6）还扁，`Fit`（票 #34）下
 * 缩略图被格子高度卡住、左边留白；改竖后常见页面按**格宽**铺满，360dp 屏上格高同时满足票 #62 AC1
 * （59.2 × 7/4 = 103.6dp ≥ 改动前 81.8dp 的 1.25 倍）。
 *
 * 另含预览窗口的页码判定（票 #87，[previewWindow]）与缩略图解码宽度（票 #62，[previewDecodeWidthPx]）。
 */
object ReaderMenuLayout {

    /** 预览格数（仍是当前页 ±2 共 5 格，票面 Out of scope 不改格数） */
    const val PREVIEW_CELLS: Int = 5

    /** 预览格间隙（与原实现一致） */
    const val PREVIEW_GAP_DP: Float = 6f

    /**
     * 预览格高宽比（票 #62）：7:4 = 1.75，竖版页面/封面的量级。
     * 取这个值的依据：①比页面本身（1.4–1.6）竖，`Fit` 下缩略图因此由**格宽**决定、把格子填满；
     * ②360dp 屏（内宽约 320dp）格高 = 59.2 × 7/4 = 103.6dp，正好 ≥ 改动前 81.8dp 的 1.25 倍（AC1）。
     */
    const val PREVIEW_ASPECT: Float = 7f / 4f

    /**
     * 格内页码字号随格宽的比例（sp/dp，票 #62）：360dp 屏格宽 59.2dp → 14.8sp，
     * 是原 `labelSmall`(11sp) 的 1.35 倍（票 #66 的口径：明显放大、≥ 现值 1.3 倍）。
     */
    const val PREVIEW_LABEL_SP_RATIO: Float = 0.25f

    /** 格内页码字号下限（sp）：原 `labelSmall` 的 11sp——格子更窄时页码保持原大小，只放大不缩小 */
    const val PREVIEW_LABEL_MIN_SP: Float = 11f

    /**
     * 格内页码字号上限（sp）：面板标题 `titleMedium` 的 16sp。
     * 面板是 `fillMaxWidth()`，横屏/宽屏下格宽随全屏宽走（873dp 宽 → 不夹就是 40.5sp），
     * 页码不得比面板标题还大；与 [CoverLayout.displayAspect] 一样两头都夹。
     */
    const val PREVIEW_LABEL_MAX_SP: Float = 16f

    /** 格内页码行高比例（× 字号）：字号大于 `labelSmall` 的 16sp 行高时数字不被压，留 20% 余量 */
    const val PREVIEW_LABEL_LINE_HEIGHT_RATIO: Float = 1.2f

    /** 单格宽度：面板内宽等分（宽度不足时不为负） */
    fun previewCellWidth(panelInnerWidthDp: Float, gapDp: Float = PREVIEW_GAP_DP): Float {
        val usable = panelInnerWidthDp - gapDp * (PREVIEW_CELLS - 1)
        return (usable / PREVIEW_CELLS).coerceAtLeast(0f)
    }

    /** 单格高度：宽度 × [PREVIEW_ASPECT] */
    fun previewCellHeight(cellWidthDp: Float): Float = cellWidthDp * PREVIEW_ASPECT

    /**
     * 缩略图解码目标宽度（px，票 #62）：与封面同一把尺子——[CoverDecode.targetWidthPx] 只上取到 32px 的整数倍。
     * 因此解码宽度恒 ≥ 格宽像素（格子变大后不会拿旧宽度的位图拉伸变糊），过冲 < 32px、±1px 的布局抖动也只解一次。
     */
    fun previewDecodeWidthPx(cellWidthPx: Float): Int = CoverDecode.targetWidthPx(cellWidthPx)

    /** 格内页码字号（sp，票 #62）：随格宽一起放大，夹在 [PREVIEW_LABEL_MIN_SP]..[PREVIEW_LABEL_MAX_SP] 之间 */
    fun previewPageLabelSp(cellWidthDp: Float): Float =
        (cellWidthDp * PREVIEW_LABEL_SP_RATIO).coerceIn(PREVIEW_LABEL_MIN_SP, PREVIEW_LABEL_MAX_SP)

    /**
     * 页位 → 预览窗口里画哪几格（0-based 页码，升序，越界格不渲染）：
     * 目标页 ±2，即最多 [PREVIEW_CELLS] 格；书首页/书末页只有 2–3 格。
     *
     * 高亮格是**目标页自己**（`index == target`）：末页的窗口因此到末页为止、
     * 高亮格必是末页（票 #87：页位差一页时末页永不可高亮）。
     */
    fun previewWindow(target: Int, pageCount: Int): List<Int> =
        (-2..2).map { target + it }.filter { it in 0 until pageCount }
}
