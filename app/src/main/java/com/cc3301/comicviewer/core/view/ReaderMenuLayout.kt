package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 阅读菜单的布局口径（纯函数，由 [ReaderMenuLayoutTest] 锁定）。
 *
 * 票 #105 把 #42 / #62 / #65 / #66 / #67 五张票的口径一次重定（改的是同一处布局、互相牵制）：
 *
 * ## 面板高度（AC4/AC5 + 批次 6 AC11）
 * 竖屏与平板恒占视口高度的 [PANEL_HEIGHT_FRACTION]（40%，手机/平板/横屏同一比例）；
 * **矮视口**（可用高 < [SHORT_VIEWPORT_MAX_HEIGHT_DP] 的横屏手机）改由 [panelHeightDp] 按需抬高：
 * `max(视口 × [PANEL_HEIGHT_FRACTION_SHORT], 固定行 + [SHORT_VIEWPORT_MIN_PREVIEW_DP])` 再夹上限，
 * 保证横屏下预览条不会再被压成一条细缝。
 *
 * 面板内四行（批次 6 AC13/AC14 起）：「标题 / 预览条 / **跳页滑动条** / 底部行」——
 * **预览条吃剩下的高度**（`weight(1f)`），跳页滑动条**独占一行**（[SLIDER_BAND_HEIGHT_DP]，不再叠在预览条上），
 * 底部行因此永远在面板里、不需要下滑（AC5；现状是面板上限 60% + 整体可滚动，横屏平板上页数与按钮被挤到屏外）。
 *
 * ## 预览项尺寸（AC1/AC3 + 批次 6 AC14）
 * 单格**高度撑满预览条减去页数那一行**、**宽度 = 高度 × 该页真实宽高比**（[previewItemWidth]、
 * [previewImageHeightDp]）——因此「一屏几格」不写死，由屏幕宽度自然决定。
 * 宽高比取**解码出来的位图**的（[previewItemAspect]），所以格子与图片比例一致：`Fit` 下既不留白也不裁切
 * （AC3；现状是固定 7:4 的格子，常见 2:3 页面上下留白、横向页面左右留白）。
 * 页面还没解出来时用 [PREVIEW_PLACEHOLDER_ASPECT] 占位（与常见页面同量级，出图后格子跟着变宽/变窄）。
 *
 * **一屏张数**（AC1 的实测值，算例见 `ReaderMenuLayoutTest`）：手机竖屏约 **3.4** 张、平板竖屏约 **5.0** 张。
 * 票面写的 2.5 / 3.5 是「滑动条叠在预览条上、页数叠在缩略图上」那个几何下的数字；
 * 批次 6 的 D2-A（进度条独占一行）+ D3-A（页数占一行）让图片高度少了 71.3dp（−32%），
 * 张数必然变大。**票面数字待维护者更新**，本对象以实测几何为准（见 evidence-impl.md）。
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

    // ---------- 矮视口（横屏手机）版式（票 #105 批次 6 AC11）----------

    /**
     * 矮视口判定阈值（dp，票 #105 批次 6 AC11）：**可用高**小于它就按矮视口版式。
     *
     * 取 480dp 的依据：横屏手机的可用高约 360–440dp，而竖屏手机 ≥ 640dp、横屏平板 ≥ 600dp、
     * 平板竖屏 ≥ 1024dp——阈值落在两群之间，竖屏与平板因此**逐像素不变**（AC11 后半句）。
     */
    const val SHORT_VIEWPORT_MAX_HEIGHT_DP: Float = 480f

    /**
     * 矮视口面板高度比例的**起点**（票 #105 批次 6 AC11）：40% → 52%。
     *
     * 它只是起点，不是实际占比：固定行压扁后仍撑不下 [SHORT_VIEWPORT_MIN_PREVIEW_DP] 时由
     * [panelHeightDp] 继续抬高（维护者 2026-09-21 裁决：**保预览条 88dp**，52% 作为基线）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT: Float = 0.52f

    /**
     * 矮视口面板高度比例的上限（票 #105 批次 6）：66%。
     *
     * 用途：`max(52%, 固定行 + 预览条保底)` 若在极矮视口（如 300dp）下算出一个把整屏都吃掉的值，
     * 面板会把标题都挤出屏幕——这个上限保证矮视口下面板**始终**留得下标题行，代价是预览条可能不足 88dp
     * （残余风险写进证据）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT_MAX: Float = 0.66f

    /**
     * 矮视口预览条保底高度（dp，票 #105 批次 6 AC11）：88dp。
     *
     * 数值来自维护者方案图 D1-C（矮视口预览条 ≈ 88dp）；它**没有**做进面板比例的公式里，而是由
     * [panelHeightDp] 反推面板需要多高——因为 Material3 1.3.0 的 `Slider` 最小高 44dp，
     * 进度条行**压不到**方案图假设的 12dp，52% 的面板在 360dp 视口下只能剩 ~46dp 预览条。
     */
    const val SHORT_VIEWPORT_MIN_PREVIEW_DP: Float = 88f

    /** 矮视口底部行高度（dp，票 #105 批次 6 AC11「固定行已压扁」：48 → 36） */
    const val PANEL_FOOTER_HEIGHT_SHORT_DP: Float = 36f

    /** 面板内共几行（标题 / 预览条 / 进度条 / 底部行）：行距个数 = 行数 − 1，票 #105 批次 6 AC13 */
    private const val PANEL_ROW_COUNT = 4

    /**
     * 面板内的四行（票 #105 批次 6 的四行结构）。
     *
     * 存在的理由：**横向 inset 只给其中三行**（票 #105 AC12：标题不吃横向 inset）——
     * 横屏挖孔/侧边手势条会让左、右 inset **不等**，而 `windowInsetsPadding` 是无条件往内让位的，
     * 给整块面板加横向 inset 会让内容盒中心整体偏离屏幕中心；标题在内容盒里居中，
     * 于是真机上看起来「标题不居中」（维护者的真机结论）。因此 inset 逐行分配，口径在
     * [rowConsumesHorizontalInsets] 一处。
     */
    enum class PanelRow { TITLE, PREVIEW, SLIDER, FOOTER }

    /**
     * 该行是否吃横向 inset（票 #105 AC12）：**标题不吃**（除标题外的三行吃）——
     * 标题因此按面板内宽居中（盒子中心 = 屏幕中心），不因左右 inset 不等而偏移。
     */
    fun rowConsumesHorizontalInsets(row: PanelRow): Boolean = row != PanelRow.TITLE

    /** 是否按矮视口版式（票 #105 批次 6 AC11）：可用高 < [SHORT_VIEWPORT_MAX_HEIGHT_DP] */
    fun isShortViewport(viewportHeightDp: Float): Boolean = viewportHeightDp < SHORT_VIEWPORT_MAX_HEIGHT_DP

    /** 面板行距（dp）：矮视口压到 0（固定行压扁的一部分），其余视口为 [PANEL_ROW_GAP_DP] */
    fun panelRowGapDp(shortViewport: Boolean): Float = if (shortViewport) 0f else PANEL_ROW_GAP_DP

    /** 标题自己的上侧留白（dp）：矮视口去掉（AC11「标题行去掉上下留白」），其余视口为 [PANEL_TITLE_TOP_PADDING_DP] */
    fun panelTitleTopPaddingDp(shortViewport: Boolean): Float =
        if (shortViewport) 0f else PANEL_TITLE_TOP_PADDING_DP

    /** 底部行高度（dp）：矮视口压到 [PANEL_FOOTER_HEIGHT_SHORT_DP]，其余视口为 [PANEL_FOOTER_HEIGHT_DP] */
    fun panelFooterHeightDp(shortViewport: Boolean): Float =
        if (shortViewport) PANEL_FOOTER_HEIGHT_SHORT_DP else PANEL_FOOTER_HEIGHT_DP

    /**
     * 固定行合计高度（dp）：标题行 + 进度条行 + 底部行 + 行距 + 面板底部内边距 + 面板必然扣掉的底部 inset。
     *
     * 这是「面板高度 − 预览条高度」的**唯一一处**口径：预览条是 `weight(1f)`，它拿到的就是
     * [panelHeightDp] 减掉这一份。测试里的 AC11 算例（预览条 ≥ 88dp）也读它。
     *
     * [titleLineHeightDp] 由调用方传入**已经换算成 dp** 的标题行高（= 字号 sp ×
     * [PANEL_TEXT_LINE_HEIGHT_RATIO] × `Density.toDp()`），原因有两个，两者都会把「固定行」算小：
     * ① **fontScale**：`sp` 随系统字体缩放放大，把 sp 数值当 dp 用会少算；
     * ② **行数**：长书名会换行，调用方按实际行数传（矮视口只给 1 行，见 `ReaderMenu` 的口径）。
     * [bottomInsetDp] 由调用方从 `readerPanelInsets()` 取（沉浸态由 [ReaderOverlayLayout.MIN_BOTTOM_DP] 兜底为 24dp）。
     */
    fun fixedRowsHeightDp(shortViewport: Boolean, titleLineHeightDp: Float, bottomInsetDp: Float): Float =
        bottomInsetDp + PANEL_BOTTOM_PADDING_DP + panelTitleTopPaddingDp(shortViewport) + titleLineHeightDp +
            panelRowGapDp(shortViewport) * (PANEL_ROW_COUNT - 1) + SLIDER_BAND_HEIGHT_DP +
            panelFooterHeightDp(shortViewport)

    /**
     * 面板高度（dp，票 #105 AC4 + 批次 6 AC11）：
     * - 非矮视口：恒为视口高度的 [PANEL_HEIGHT_FRACTION]（40%）——竖屏/平板逐像素与改动前一致；
     * - 矮视口：`max(视口 × [PANEL_HEIGHT_FRACTION_SHORT], 固定行 + [SHORT_VIEWPORT_MIN_PREVIEW_DP])`，
     *   再夹到 `视口 × [PANEL_HEIGHT_FRACTION_SHORT_MAX]`：52% 是**起点**（维护者口径），
     *   固定行压扁后仍撑不下 88dp 时继续抬高（360dp 视口实测 ≈ 63.6%）。
     */
    fun panelHeightDp(viewportHeightDp: Float, titleLineHeightDp: Float, bottomInsetDp: Float): Float {
        if (!isShortViewport(viewportHeightDp)) return viewportHeightDp * PANEL_HEIGHT_FRACTION
        val base = viewportHeightDp * PANEL_HEIGHT_FRACTION_SHORT
        val needed = fixedRowsHeightDp(true, titleLineHeightDp, bottomInsetDp) + SHORT_VIEWPORT_MIN_PREVIEW_DP
        val cap = viewportHeightDp * PANEL_HEIGHT_FRACTION_SHORT_MAX
        return maxOf(base, needed).coerceAtMost(cap)
    }

    /** 预览条高度（dp）= [panelHeightDp] − [fixedRowsHeightDp]（`weight(1f)` 实际拿到的值） */
    fun previewStripHeightDp(viewportHeightDp: Float, titleLineHeightDp: Float, bottomInsetDp: Float): Float =
        panelHeightDp(viewportHeightDp, titleLineHeightDp, bottomInsetDp) -
            fixedRowsHeightDp(isShortViewport(viewportHeightDp), titleLineHeightDp, bottomInsetDp)

    /**
     * 面板四行的几何（票 #105 批次 6 的四行结构：标题 / 预览条 / 进度条 / 底部行，面板本身不再滚动）。
     * 这些值同时是「一屏几格」算数的输入，因此放在本对象里当**唯一一处来源**（`ReaderMenu` 与
     * `ReaderMenuLayoutTest` 都读它）；实测算例 3.4 / 5.0 张见测试与本对象头部说明。
     */
    const val PANEL_HORIZONTAL_PADDING_DP: Float = 20f

    /**
     * 面板底部内边距。取 4dp（而不是从前的 16dp）：面板底部已经由 `windowInsetsPadding` 扣掉
     * **必然占用的底部 inset**（沉浸态 24dp）——那一段已经是「离手势导航上滑带的留白」，
     * 再叠 16dp 内边距就是重复占高、白吃预览区的高度。
     */
    const val PANEL_BOTTOM_PADDING_DP: Float = 4f

    /**
     * 面板四行之间的行距。取 8dp：面板底部有 [PANEL_BOTTOM_PADDING_DP] + **必然占用的底部 inset**
     * （沉浸态下由 `ReaderOverlayLayout.MIN_BOTTOM_DP` 兜底为 24dp），这两项会从预览条里扣；
     * 行距与底部内边距一起收紧后的一屏张数算例见 `ReaderMenuLayoutTest`。
     * 矮视口把行距压到 0（[panelRowGapDp]）。
     */
    const val PANEL_ROW_GAP_DP: Float = 8f

    /** 底部行高度（票 #105 AC7：48dp = 触摸目标下限） */
    const val PANEL_FOOTER_HEIGHT_DP: Float = 48f

    /**
     * 跳页滑动条那一行的高度（票 #105 批次 6 AC13）：48dp = 触摸目标下限。
     *
     * 它**独占一行、在预览条正下方**（不再叠在预览条下缘）：因此不再遮挡任何缩略图（遮挡恒为 0，
     * 旧的「遮挡 ≤ 25%」预算随之作废），滑条整宽可点。维护者方案图按 12dp 建模这一行——
     * 做不到：Material3 1.3.0 的 `Slider` 最小高 44dp（`requiredSizeIn(minHeight = ThumbHeight)`），
     * 所以矮视口的面板是靠 [panelHeightDp] 抬高来保住预览条，而不是靠压薄这一行。
     */
    const val SLIDER_BAND_HEIGHT_DP: Float = 48f

    /**
     * 上/下一本按钮**可见本体**的最小尺寸（票 #105 AC7；宽 × 高）：96 × 48dp。
     * 票面要的是「按钮加大」（不只可点区域）：原来的裸文字按钮可见尺寸只有文字本身，
     * 本票给它两个尺寸下限（批次 6 AC15 去掉底色/描边后下限照旧守住可量尺寸）。
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
     * 格内页数那一行的行高（**sp**，票 #105 批次 6 AC14）：字号 × [PANEL_TEXT_LINE_HEIGHT_RATIO]。
     *
     * 返回的是 **sp 值**（与字号同一单位），调用方必须用 `Density.toDp()` 换成 dp 再当高度用：
     * `sp` 会随系统字体缩放（fontScale）放大，把 sp 数值当 dp 用会在放大字体下把页数那一行算小、
     * 进而把缩略图算高（被字撑破）。`ReaderMenu` 与 `ReaderMenuLayoutTest` 都走这条换算。
     *
     * 页数从「叠在缩略图右上角」改到「缩略图正下方居中」后，它**自己要占一行**：
     * 预览条的可用高被这一行吃掉，缩略图高度 = 预览条高 − 它（见 [previewImageHeightDp]）。
     */
    fun previewLabelHeightSp(labelSp: Float): Float = labelSp * PANEL_TEXT_LINE_HEIGHT_RATIO

    /**
     * 缩略图（图片本体）高度（dp，票 #105 批次 6 AC14）：预览条高先扣掉页数那一行，再交给 [previewItemHeight] 收口。
     *
     * AC3「上下不留白」不变：图片高度撑满「预览条高 − 页数行高」这块可用高，宽度仍 = 高度 × 该页真实比例。
     */
    fun previewImageHeightDp(
        stripHeightDp: Float,
        labelHeightDp: Float,
        previewAreaWidthDp: Float,
        pageAspect: Float,
    ): Float =
        previewItemHeight((stripHeightDp - labelHeightDp).coerceAtLeast(0f), previewAreaWidthDp, pageAspect)

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

    /**
     * 标题**一行**的高（dp，票 #105 标准轴 P2-5）：字号 sp × [PANEL_TEXT_LINE_HEIGHT_RATIO] × [fontScale]。
     *
     * `sp` 与 `dp` 在 fontScale ≠ 1 时不等值（`1.sp.toDp() == fontScale`），而固定行合计必须拿 **dp**；
     * 这里把换算显式写成参数，调用方传 `Density.fontScale`：
     * - fontScale > 1（系统大字体）时标题行变高，[panelHeightDp] 因此把矮视口面板再抬高，预览条仍保 88dp；
     * - 行数不在本函数里：调用方按实际行数相乘（矮视口只给 1 行，见 `ReaderMenu.ReaderMenuTitle` 的 `maxLines`）。
     */
    fun titleLineHeightDp(panelInnerWidthDp: Float, fontScale: Float): Float =
        panelTitleSp(panelInnerWidthDp) * PANEL_TEXT_LINE_HEIGHT_RATIO * fontScale

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
