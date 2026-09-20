package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

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
 * 另含预览窗口的页码判定（票 #87 起改由票 #65 的 [previewWindowStart] 平移凑满 5 格，[previewWindow]）、
 * 缩略图解码宽度（票 #62，[previewDecodeWidthPx]）、
 * 滑块值 → 跳页目标的换算（票 #63，[seekTargetPage]）、格内显示页码的换算（票 #64，[previewPageLabel]），
 * 以及面板内三档字号的层级：菜单标题 [panelTitleSp]、面板底部页码 [panelPageLabelSp]、格内页码 [previewPageLabel]
 * （票 #66 定页码、票 #67 定标题与层级）。
 *
 * ## 面板内的字号层级（票 #67 一次定清）
 *
 * | 层 | 口径 | 取值范围 |
 * | --- | --- | --- |
 * | 菜单标题 [panelTitleSp] | 最大 | 22–32sp |
 * | 面板底部页码 [panelPageLabelSp] | 中间 | 18.2–28sp |
 * | 格内页码 [previewPageLabelSp] | 最小 | 11–16sp |
 *
 * 三层都随面板内宽放大（面板是 `fillMaxWidth()`），且比值与上下限**逐层收窄**
 * （标题比例 0.07 > 页码比例 0.06；标题上限 32 > 页码上限 28 > 格内页码上限 16；
 * 标题下限 22 > 页码下限 18.2 > 格内页码上限 16），因此任意内宽下恒有 `标题 > 页码 > 格内页码`。
 * 票 #66 之后这里曾两句打架——[PREVIEW_LABEL_MAX_SP] 的说明写着「页码不得比面板标题还大」，
 * 而面板页码已能到 28sp；现在格内页码的上限只由它自己的层级位置（三层里最小）给出，
 * 不再引用「面板标题」当依据。
 */
object ReaderMenuLayout {

    /** 预览格数（5 格，票面 Out of scope 不改格数）；总页数 ≥ 它时窗口永远凑满它 */
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
     * 格内页码字号上限（sp）：三层字号里最小的一层，上限 16sp（票 #62 起；票 #67 换了依据）。
     * 面板是 `fillMaxWidth()`，横屏/宽屏下格宽随全屏宽走（873dp 宽 → 不夹就是 40.5sp），
     * 因此两头都夹（与 [CoverLayout.displayAspect] 同一套做法）。
     * 16sp = 原 `labelSmall`(11sp) 的 1.45 倍，且**小于面板底部页码的下限** [PANEL_PAGE_LABEL_MIN_SP]
     * （18.2sp）——票 #66 时写的「不得比面板标题还大」已不再成立（标题现在最小 [PANEL_TITLE_MIN_SP] 22sp），
     * 格内页码的约束改为「恒为面板内三档字号里最小的一档」（层级表见本对象 KDoc）。
     */
    const val PREVIEW_LABEL_MAX_SP: Float = 16f

    /**
     * 放大字号的行高比例（× 字号，票 #66 起由面板底部页码、格内页码与菜单标题共用）：
     * 字号大于原行高时数字/文字不被压，留 20% 余量，三处行高口径只有这一份。
     * 三者的原行高都不够用了——格内页码原样式 `labelSmall` 行高 16sp（票 #62 起字号可到 16sp）、
     * 面板页码原样式 `bodyMedium` 行高 20sp（票 #66 起字号 18.2–28sp）、
     * 菜单标题原样式 `titleMedium` 行高 24sp（票 #67 起字号 22–32sp）。
     */
    const val PANEL_TEXT_LINE_HEIGHT_RATIO: Float = 1.2f

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
     * 面板底部页码的字号比例（sp/dp，票 #66）：面板是 `fillMaxWidth()`，字号随面板内宽放大。
     *
     * 背景（维护者原文：「页数的字码显示也要放大（平板10.jpg页数字码太小，手机10-2.jpg页数字码还算正常）」）：
     * 页码原来固定 `bodyMedium`(14sp)，平板上看着偏小。比例 0.06 → 360dp 屏（内宽 320dp）19.2sp，
     * 已是现值 14sp 的 1.37 倍；平板（约 920dp 内宽）不夹的话是 55.2sp，因此上限 [PANEL_PAGE_LABEL_MAX_SP]。
     */
    const val PANEL_PAGE_LABEL_SP_RATIO: Float = 0.06f

    /**
     * 面板底部页码字号下限（sp）：现值 `bodyMedium` 的 14sp 的 1.3 倍（票 #66 AC2 的口径），
     * 极窄面板（分屏/小窗）也保持「明显放大」。
     */
    const val PANEL_PAGE_LABEL_MIN_SP: Float = 18.2f

    /**
     * 面板底部页码字号上限（sp）：现值 14sp 的 2 倍。
     * 再不夹，870dp 以上的宽面板会给出 50sp 量级的页码，把同行的「上一本/下一本」压成两边的窄条。
     * 上限小于菜单标题上限 [PANEL_TITLE_MAX_SP]（32sp）——宽面板上页码不得反超标题（层级表见本对象 KDoc）。
     */
    const val PANEL_PAGE_LABEL_MAX_SP: Float = 28f

    /**
     * 菜单标题字号比例（sp/dp，票 #67）：与面板页码、格内页码一样随面板内宽放大。
     *
     * 背景（维护者原文：「预览菜单的标题字体太小 上方留白太多 参考 APP-Viewer-GUI-Perfect-Viewer.jpg 来做」）：
     * 标题原来固定 `titleMedium`(16sp)，与正文同量级。比例 0.07 →
     * 360dp 屏（内宽 320dp）22.4sp，是现值 16sp 的 1.4 倍（票 #67 AC1 要求 ≥ 1.2 倍）、也是 `titleLarge`(22sp) 的量级；
     * 平板（约 920dp 内宽）不夹的话是 64.4sp，因此上限 [PANEL_TITLE_MAX_SP]。
     */
    const val PANEL_TITLE_SP_RATIO: Float = 0.07f

    /** 菜单标题字号下限（sp）：`titleLarge` 的 22sp——最窄面板也守住 AC1（≥ 现值 16sp 的 1.2 倍）与「整行大字」的观感 */
    const val PANEL_TITLE_MIN_SP: Float = 22f

    /**
     * 菜单标题字号上限（sp）：32sp。上限必须**大于**面板页码上限 [PANEL_PAGE_LABEL_MAX_SP]（28sp），
     * 否则宽面板上页码会反超标题（层级表见本对象 KDoc）。
     */
    const val PANEL_TITLE_MAX_SP: Float = 32f

    /**
     * 标题上方留白（dp，票 #67 AC2）：面板顶边 → 标题第一行行顶，由标题自己带（面板不再有上侧内边距）。
     * 取 3dp：除它之外只剩行框自身的上侧 leading——字号 22–32sp、行高 = 字号 × [PANEL_TEXT_LINE_HEIGHT_RATIO]，
     * Material3 的主题文本样式（`includeFontPadding = false`）下约 0.3sp，即使退化成 `includeFontPadding = true`
     * 也不过 +0.2sp/字号 ≈ 4.5dp，两边都 ≤ AC 的 8dp（真量见 `ReaderMenuTitleTest`）。
     */
    const val PANEL_TITLE_TOP_PADDING_DP: Float = 3f

    /** 菜单标题字号（sp，票 #67）：随面板内宽放大，夹在 [PANEL_TITLE_MIN_SP]..[PANEL_TITLE_MAX_SP] 之间 */
    fun panelTitleSp(panelInnerWidthDp: Float): Float =
        (panelInnerWidthDp * PANEL_TITLE_SP_RATIO).coerceIn(PANEL_TITLE_MIN_SP, PANEL_TITLE_MAX_SP)

    /**
     * 面板底部页码字号（sp，票 #66）：随面板内宽放大，夹在 [PANEL_PAGE_LABEL_MIN_SP]..[PANEL_PAGE_LABEL_MAX_SP] 之间。
     */
    fun panelPageLabelSp(panelInnerWidthDp: Float): Float =
        (panelInnerWidthDp * PANEL_PAGE_LABEL_SP_RATIO)
            .coerceIn(PANEL_PAGE_LABEL_MIN_SP, PANEL_PAGE_LABEL_MAX_SP)

    /**
     * 预览格上显示的页码（1-based，票 #64）：格位 `cellIndex` 显示 `cellIndex + 1`。
     * 0-based 页位 → 1-based 页码的换算只有这一处（面板底部的「当前页/总页数」与格内页码共用）。
     * 点击该格跳到的页位就是同一个页位（`cellIndex`，经 [clampPage] 夹取），与这里显示的页码一致、不差一格。
     */
    fun previewPageLabel(cellIndex: Int): Int = cellIndex + 1

    /**
     * 预览窗口起点（0-based，票 #65）：目标页居中——起点 = 目标页 − `(格数 − 1) / 2`（5 格时 − 2），
     * 再整体夹到合法区间 `0 .. 总页数 − 格数`。
     *
     * 因此首页起点为 0（显示第 1–5 页）、末页起点为 `总页数 − 格数`（显示第 N−4–N 页），中间页仍是目标页 ±2，
     * 三处都凑满 [PREVIEW_CELLS] 格。总页数 < [PREVIEW_CELLS] 时合法区间为空、起点恒为 0
     * （窗口按实际页数只渲染存在的页，不补空格、不越界取图）；总页数或格数非正时同样返回 0（窗口为空）。
     */
    fun previewWindowStart(target: Int, pageCount: Int, cells: Int = PREVIEW_CELLS): Int {
        if (pageCount <= 0 || cells <= 0) return 0
        val leading = (cells - 1) / 2
        return (clampPage(target, pageCount) - leading).coerceIn(0, (pageCount - cells).coerceAtLeast(0))
    }

    /**
     * 页位 → 预览窗口里画哪几格（0-based 页码，升序）：起点由 [previewWindowStart] 平移得出（票 #65），
     * 总页数 ≥ [PREVIEW_CELLS] 时恒为 [PREVIEW_CELLS] 格；总页数不足时只留实际存在的页。
     *
     * 高亮格是**目标页自己**（`index == target`）：窗口整体平移、目标页始终落在窗口内，
     * 因此末页必可高亮（票 #87）且首页也能占满整排。
     */
    fun previewWindow(target: Int, pageCount: Int): List<Int> {
        val start = previewWindowStart(target, pageCount)
        return (start until start + PREVIEW_CELLS).filter { it in 0 until pageCount }
    }

    /** 末页页位（0-based）：空书与单页书都是 0（跳页滑动条的页位上限口径） */
    fun lastPage(pageCount: Int): Int = (pageCount - 1).coerceAtLeast(0)

    /** 页位夹到 0..[lastPage]（页位夹取只有这一处口径，滑块值与页面变化都走它） */
    fun clampPage(page: Int, pageCount: Int): Int = page.coerceIn(0, lastPage(pageCount))

    /**
     * 滑块值（`Slider` 的 value）→ 跳页目标页（0-based，四舍五入后夹取）。
     *
     * 「手势中预览跟随哪一页」与「手势结束跳到哪一页」共用这一条口径（票 #63）。
     * 目标页必须由调用现场的最新值算：为什么（据 m3 1.3.0 调用序列推演、未在真机复核）见
     * `SeekBarGestureState` 的说明。
     */
    fun seekTargetPage(sliderValue: Float, pageCount: Int): Int =
        clampPage(sliderValue.roundToInt(), pageCount)
}
