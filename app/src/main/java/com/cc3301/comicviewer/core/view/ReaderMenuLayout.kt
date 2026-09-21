package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 阅读菜单的布局口径（纯函数，由 [ReaderMenuLayoutTest] 锁定）。
 *
 * 票 #105 把 #42 / #62 / #65 / #66 / #67 五张票的口径一次重定（改的是同一处布局、互相牵制）：
 *
 * ## 面板高度（AC4/AC5）
 * 面板恒占视口高度的 [PANEL_HEIGHT_FRACTION]（40%）：手机、平板、横屏同一比例。面板内只有三行
 * 「标题 / 预览区 / 底部行」，**预览区吃剩下的高度**（`weight(1f)`），跳页滑动条叠在预览区下缘
 * （[SLIDER_BAND_HEIGHT_DP]）而不是独占一行——这样预览项才拿得到方案图那组尺寸（手机 160×240、
 * 平板 207×310），底部行也永远在面板里、不需要下滑（AC5；现状是面板上限 60% + 整体可滚动，
 * 横屏平板上页数与按钮被挤到屏外）。
 *
 * ## 预览项尺寸（AC1/AC3）
 * 单格**高度撑满预览区**，**宽度 = 高度 × 该页真实宽高比**（[previewItemWidth]）——因此
 * 「一屏几格」不写死，由屏幕宽度自然决定（AC1：手机约 2.5 格、平板约 3.5 格，见测试里的算例）。
 * 宽高比取**解码出来的位图**的（[previewItemAspect]），所以格子与图片比例一致：`Fit` 下既不留白也不裁切
 * （AC3；现状是固定 7:4 的格子，常见 2:3 页面上下留白、横向页面左右留白）。
 * 页面还没解出来时用 [PREVIEW_PLACEHOLDER_ASPECT] 占位（与常见页面同量级，出图后格子跟着变宽/变窄）。
 *
 * ## 面板内三档字号（AC6）
 *
 * | 层 | 口径 | 取值范围 |
 * | --- | --- | --- |
 * | 菜单标题 [panelTitleSp] | `内宽 × 0.05` | 18–24sp |
 * | 面板底部页码 [panelPageLabelSp] | `内宽 × 0.05` | 16–24sp |
 * | 格内页码 [previewPageLabelSp] | `内宽 × 0.035` | 12–16sp |
 *
 * 不变量 **标题 ≥ 页码 > 格内页码**：标题与页码比例相同、但标题的上下限更高（18–24 vs 16–24），
 * 格内页码的比例与上下限都最低。三处都随面板内宽放大（面板是 `fillMaxWidth()`），
 * 公式只有 [scaledSp] 一处。
 */
object ReaderMenuLayout {

    /** 面板高度占视口高度的比例（票 #105 AC4：手机/平板/横屏统一到 40%） */
    const val PANEL_HEIGHT_FRACTION: Float = 0.4f

    /**
     * 面板四行的几何（票 #105 方案 B：标题 / 预览区（含叠在它下缘的滑动条）/ 底部行，面板本身不再滚动）。
     * 这些值同时是 AC1「一屏几格」算数的输入，因此放在本对象里当**唯一一处来源**（`ReaderMenu` 与
     * `ReaderMenuLayoutTest` 都读它，测试里的 2.5 / 3.5 张算例不会与生产漂移）。
     */
    const val PANEL_HORIZONTAL_PADDING_DP: Float = 20f

    /**
     * 面板底部内边距。取 4dp（而不是从前的 16dp）：面板底部已经由 `windowInsetsPadding` 扣掉
     * **必然占用的底部 inset**（沉浸态 24dp）——那一段已经是「离手势导航上滑带的留白」，
     * 再叠 16dp 内边距就是重复占高、白吃预览区的高度。
     */
    const val PANEL_BOTTOM_PADDING_DP: Float = 4f

    /**
     * 面板三行之间的行距。取 8dp：面板底部有 [PANEL_BOTTOM_PADDING_DP] + **必然占用的底部 inset**
     * （沉浸态下由 `ReaderOverlayLayout.MIN_BOTTOM_DP` 兜底为 24dp），这两项会从预览区里扣；
     * 行距与底部内边距一起收紧后，**真机几何**（扣掉 inset）下的一屏张数才落在 AC1 的 2.5±0.3 / 3.5±0.3 里
     * （算例见 `ReaderMenuLayoutTest`）。
     */
    const val PANEL_ROW_GAP_DP: Float = 8f

    /** 底部行高度（票 #105 AC7：48dp = 触摸目标下限） */
    const val PANEL_FOOTER_HEIGHT_DP: Float = 48f

    /**
     * 叠在预览区下缘的跳页滑动条那一条的高度（票 #105 方案 B）：取 48dp = 触摸目标下限，
     * 滑动条的可点区域因此不因为叠放而变小；预览项的遮挡比例 = 它 / 预览区高度（手机约 21%、平板约 17%，
     * 都在裁决给的 25% 以内）。
     */
    const val SLIDER_BAND_HEIGHT_DP: Float = 48f

    /**
     * 上/下一本按钮**可见本体**的最小尺寸（票 #105 AC7；宽 × 高）：96 × 48dp。
     * 票面要的是「按钮加大」（不只可点区域）：原来的裸文字按钮可见尺寸只有文字本身，
     * 本票给它一个带底/描边的药丸，尺寸由这两个下限守住（数值来自维护者方案图 §1 的内边距 9×20 量级）。
     */
    const val BOOK_STEP_MIN_WIDTH_DP: Float = 96f

    /** 见 [BOOK_STEP_MIN_WIDTH_DP]（48dp 同时是触摸目标下限） */
    const val BOOK_STEP_MIN_HEIGHT_DP: Float = 48f

    /** 预览项之间的间隙（与原实现一致） */
    const val PREVIEW_GAP_DP: Float = 6f

    /**
     * 页面尚未解码时预览项的占位宽高比（宽/高，票 #105 AC3）：取 2:3，与常见漫画页面同量级，
     * 出图后按 [previewItemAspect] 换成真实比例。取正数是因为宽度 = 高度 × 它（0 会让格子先塌成一条线）。
     */
    const val PREVIEW_PLACEHOLDER_ASPECT: Float = 2f / 3f

    /**
     * 放大字号的行高比例（× 字号）：字号大于原行高时数字/文字不被压，留 20% 余量，
     * 面板底部页码、格内页码与菜单标题共用这一份（票 #66 起）。
     */
    const val PANEL_TEXT_LINE_HEIGHT_RATIO: Float = 1.2f

    /**
     * 标题上方留白（dp，票 #67 AC2）：面板顶边 → 标题第一行行顶，由标题自己带（面板不再有上侧内边距）。
     * 真量见 `ReaderMenuTitleTest`。
     */
    const val PANEL_TITLE_TOP_PADDING_DP: Float = 3f

    // ---------- 预览项尺寸（票 #105 AC1/AC3）----------

    /**
     * 单格宽度：**高度撑满预览区**、宽度按该页真实宽高比走（票 #105 AC3 的落地式）。
     *
     * 取这个口径的原因（维护者原文：「预览太小、上下有留白；要求按图片实际比例显示」+「改为 PV 式：
     * 不固定张数、滑动式显示」）：格子比例与图片一致时 `Fit` 既不裁切也不留白；格子宽度随页面比例变，
     * 一屏能放几格因此由屏幕宽度决定（AC1），不需要写死格数。
     * 非正比例（页面还没解出来、尺寸异常）回 0，由调用方用 [PREVIEW_PLACEHOLDER_ASPECT] 兜。
     */
    fun previewItemWidth(itemHeightDp: Float, pageAspect: Float): Float =
        if (pageAspect > 0f) itemHeightDp * pageAspect else 0f

    /**
     * 单格高度：撑满预览区；但页面比预览区还宽时（宽高比 > 预览区宽 / 预览区高）按**宽度**收口，
     * 比例仍不变。
     *
     * 为什么要有这条收口（票 #105 AC3「横向（宽大于高）的预览项必须水平居中，不得靠左贴边」）：
     * 双页跨页这类超宽页在「高度撑满」下宽度会超过预览区（例：预览区 224dp 高、页面 2:1 ⇒ 448dp 宽），
     * 于是它只能从预览区左缘开始排、右半被裁掉——正是「靠左贴边」。收口后它横向铺满预览区、整页可见，
     * 在预览区里垂直居中；格子比例仍 = 图片比例，所以**格内上下不留白**照旧成立。
     */
    fun previewItemHeight(stripHeightDp: Float, previewAreaWidthDp: Float, pageAspect: Float): Float =
        if (pageAspect > 0f) minOf(stripHeightDp, previewAreaWidthDp / pageAspect) else stripHeightDp

    /**
     * 位图尺寸 → 宽高比（宽/高，票 #105 AC3）：宽或高非正时回 `null`（调用方回落到占位比例）。
     * 取解码出来的位图（而不是源图头）是因为预览本来就按目标高度解码（`PageDecoder.decodePageByHeight`），
     * 位图的比例就是显示比例。
     */
    fun previewItemAspect(bitmapWidthPx: Int, bitmapHeightPx: Int): Float? =
        if (bitmapWidthPx > 0 && bitmapHeightPx > 0) bitmapWidthPx.toFloat() / bitmapHeightPx else null

    /**
     * 预览项的解码目标高度（px，票 #105）：与封面同一把尺子——[CoverDecode.targetWidthPx] 只上取到 32px 的
     * 整数倍（分桶规则只有那一处实现，与量的是宽还是高无关）。因此解码高度恒 ≥ 格子高度像素
     * （格子变大后不会拿旧高度的位图拉伸变糊），过冲 < 32px、±1px 的布局抖动也只解一次。
     */
    fun previewDecodeHeightPx(itemHeightPx: Float): Int = CoverDecode.targetWidthPx(itemHeightPx)

    // ---------- 面板内三档字号（票 #105 AC6）----------

    /**
     * 菜单标题字号比例（sp/dp）：标题与面板页码同比例（0.05），靠上下限分出层级（标题 18–24、页码 16–24）。
     * 360dp 屏（内宽约 320dp）不夹是 16sp、被下限抬到 18sp；平板（内宽约 740dp）不夹是 37sp、被上限压到 24sp
     * （票面表：手机 18.3sp、平板 24sp，对应内宽 365 / 740）。
     */
    const val PANEL_TITLE_SP_RATIO: Float = 0.05f

    /** 菜单标题字号下限（sp）：票面表的 18sp（现状 22–32sp 档整体降一档，AC6） */
    const val PANEL_TITLE_MIN_SP: Float = 18f

    /**
     * 菜单标题字号上限（sp）：票面表的 24sp。必须**大于等于**面板页码上限（同为 24sp）——
     * 同比例下宽面板上两者会同时顶到上限，层级由下限与格内页码一起保住（见 [previewPageLabelSp]）。
     */
    const val PANEL_TITLE_MAX_SP: Float = 24f

    /** 面板底部页码字号比例（sp/dp）：与标题同比例，票面表 `内宽 × 0.05` */
    const val PANEL_PAGE_LABEL_SP_RATIO: Float = 0.05f

    /**
     * 面板底部页码字号下限（sp）：票面表的 16sp。与格内页码上限同值，但**层级不靠上下限、靠比例**：
     * 格内页码比例更低（0.035 vs 0.05），页码顶到 16sp 的下限时格内页码才 12sp（见 [previewPageLabelSp]）。
     */
    const val PANEL_PAGE_LABEL_MIN_SP: Float = 16f

    /** 面板底部页码字号上限（sp）：票面表的 24sp */
    const val PANEL_PAGE_LABEL_MAX_SP: Float = 24f

    /** 格内页码字号比例（sp/dp）：票面表 `内宽 × 0.035`（手机 12.8sp、平板 16sp） */
    const val PREVIEW_LABEL_SP_RATIO: Float = 0.035f

    /** 格内页码字号下限（sp）：票面表的 12sp（原 `labelSmall` 的 11sp 之上一档） */
    const val PREVIEW_LABEL_MIN_SP: Float = 12f

    /**
     * 格内页码字号上限（sp）：票面表的 16sp，三层里最小的一档。上限与面板页码下限同值，
     * 层级由比例保住（格内页码 0.035 < 页码 0.05）：格内页码顶到 16sp 时页码已 ≥ 22.9sp。
     */
    const val PREVIEW_LABEL_MAX_SP: Float = 16f

    /** 菜单标题字号（sp）：随面板内宽放大，夹在 [PANEL_TITLE_MIN_SP]..[PANEL_TITLE_MAX_SP] 之间 */
    fun panelTitleSp(panelInnerWidthDp: Float): Float =
        scaledSp(panelInnerWidthDp, PANEL_TITLE_SP_RATIO, PANEL_TITLE_MIN_SP, PANEL_TITLE_MAX_SP)

    /** 面板底部页码字号（sp）：随面板内宽放大，夹在 [PANEL_PAGE_LABEL_MIN_SP]..[PANEL_PAGE_LABEL_MAX_SP] 之间 */
    fun panelPageLabelSp(panelInnerWidthDp: Float): Float =
        scaledSp(panelInnerWidthDp, PANEL_PAGE_LABEL_SP_RATIO, PANEL_PAGE_LABEL_MIN_SP, PANEL_PAGE_LABEL_MAX_SP)

    /**
     * 格内页码字号（sp）：随**面板内宽**放大（不是格宽——格宽现在随页面比例变，拿它当基准会让同一屏里
     * 每格的页码大小不一样），夹在 [PREVIEW_LABEL_MIN_SP]..[PREVIEW_LABEL_MAX_SP] 之间。
     */
    fun previewPageLabelSp(panelInnerWidthDp: Float): Float =
        scaledSp(panelInnerWidthDp, PREVIEW_LABEL_SP_RATIO, PREVIEW_LABEL_MIN_SP, PREVIEW_LABEL_MAX_SP)

    /** 三档字号的唯一一处公式（票 #105 AC6）：按比例放大后夹在各自上下限里 */
    private fun scaledSp(value: Float, ratio: Float, minSp: Float, maxSp: Float): Float =
        (value * ratio).coerceIn(minSp, maxSp)

    // ---------- 页位口径 ----------

    /**
     * 预览格上显示的页码（1-based）：格位 `cellIndex` 显示 `cellIndex + 1`。
     * 0-based 页位 → 1-based 页码的换算在**阅读菜单内**只有这一处（面板底部的「当前页/总页数」与格内页码共用）；
     * `ReaderScreen` 另有自己的 `index + 1` 换算（页码显示与 `contentDescription`），不共用本函数。
     * 点击该格跳到的页位就是同一个页位（`cellIndex`）：预览条按 `items(count = pageCount)` 枚举，
     * 格位天然在 `0 until pageCount` 内，**不经过** [clampPage]（夹取只用于滑块通路）。
     */
    fun previewPageLabel(cellIndex: Int): Int = cellIndex + 1

    /** 末页页位（0-based）：空书与单页书都是 0（跳页滑动条的页位上限口径） */
    fun lastPage(pageCount: Int): Int = (pageCount - 1).coerceAtLeast(0)

    /**
     * 页位夹到 0..[lastPage]。
     * 这是**阅读菜单内**的**滑块通路**页位夹取口径（滑块值与页面变化都走它）；预览格点击不经它
     * （格位由 `items(count = pageCount)` 保证在界内），`ReaderScreen` 另有自己的夹取，不等同于本处。
     */
    fun clampPage(page: Int, pageCount: Int): Int = page.coerceIn(0, lastPage(pageCount))

    /**
     * 滑块值（`Slider` 的 value）→ 跳页目标页（0-based，四舍五入后夹取）。
     *
     * 「手势中预览跟随哪一页」与「手势结束跳到哪一页」共用这一条口径；**点滑动条任意位置**也走它
     * （票 #105 AC9）：Material3 的 `Slider` 把按下位置换算成滑块值（`SliderState.onPress` 记下
     * `pressOffset`、抬手时 `dispatchRawDelta(0f)` 落位），本函数再四舍五入到**最近的页**并夹到首末页——
     * 因此 3 页这类页数少的书，滑动条上任意位置都落在某一页上（不是只有最左/最中/最右三个点），
     * 两端超出轨道的按下位置也被夹到首末页。
     */
    fun seekTargetPage(sliderValue: Float, pageCount: Int): Int =
        clampPage(sliderValue.roundToInt(), pageCount)
}
