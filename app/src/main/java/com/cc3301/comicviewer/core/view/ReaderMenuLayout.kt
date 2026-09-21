package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 阅读菜单的布局口径（纯函数，由 [ReaderMenuLayoutTest] 锁定）。
 *
 * 票 #105 把 #42 / #62 / #65 / #66 / #67 五张票的口径一次重定（改的是同一处布局、互相牵制）：
 *
 * ### 面板高度与预览条保底（批次 6 AC11 + 裁定 A + 第 6 轮真机反馈 + 第 7 轮**按视口分档**）
 * **所有视口**都走同一条公式：`min(max(base, 固定行 + [previewStripTargetDp]), 视口高 × 80%)`，
 * `base` = 40%（常规视口）/ 52%（矮视口：可用高 < [SHORT_VIEWPORT_MAX_HEIGHT_DP]）、
 * 预览条目标高度**按视口分档、且与标题行数无关**（[previewStripTargetDp]）：**只有手机竖屏**（[isPhonePortrait]）
 * 取 [PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP]（224dp），其余视口取「[PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP]（80dp）
 * 与『基础面板扣掉一行标题后的余量』里较大的那个」——标题 1/2/3 行下预览条**逐像素相等**（票面第 4 条），
 * 行数只让面板变高。
 * **固定行按标题的实际行数预算**（第 6 轮：1–3 行，[READER_MENU_TITLE_MAX_LINES]；不截断、不省略号）。实测（第 9 轮 = 补记 8 的 A 档压缩后）：
 * 手机竖屏（852dp、内宽 365dp、一行标题）⇒ 固定行 148.9dp + 保底 224dp ⇒ 面板 372.9dp（43.8%）、**预览条 224dp**、一屏 2.56 张。
 * 平板竖屏面板 409.6dp（40%、预览条 253.8dp）、平板横屏 307.2dp（40%、预览条 151.4dp）、
 * 480dp 高横屏 235.8dp（49.1%）、600dp 高横屏 240dp（40%）——后两档的预览条分别是 80 / 84.2dp（80dp 保底档）。
 * 矮视口 360dp ⇒ 固定行（两行标题）169.6dp、面板 249.6dp（69.3%）、**预览条 80dp**；fontScale 1.4 ⇒ 面板 272.6dp（75.7%）、预览条仍 80dp。
 *
 * 票面（补记 8 ②）的算例（363 × 800dp、内宽 323dp、2:3 页）：固定行 148.6dp + 保底 224dp ⇒ 面板 372.6dp（46.6%）、
 * 预览条 224dp、缩略图 ≈139.7 × 209.6dp、一屏 2.26 张（压缩前 2.9 张）——同一套几何，与上面那台只差内宽。
 *
 * 面板内四行（批次 6 AC13/AC14 起）：「标题 / 预览条 / **跳页滑动条** / 底部行（上/下一本 + 页数同一行）」——
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
 * **一屏张数**（AC1 的实测值，算例见 `ReaderMenuLayoutTest`）：**手机竖屏 2.56 张**（第 9 轮：预览条保底抬到
 * [PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP] 的 224dp，图片再大一档）、平板竖屏 4.52 张（面板仍是 40%，预览条随 A 档压缩
 * 从 229.8dp 涨到 253.8dp）。**票面 AC1 的数字（手机 3.4 / 平板 5.0）在这两处几何之后都已过期**，待编排者同步。
 * 票面写的 2.5 / 3.5 是「滑动条叠在预览条上、页数叠在缩略图上」那个几何下的 PV 参考值；
 * 维护者第 6 轮真机反馈把「手机一屏几张」重新定为 **2.5–3 张**（不再是「固定 4 张」）。
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
 *
 * 底部页码的**实际取值**走 [pageLabelSp]（第 10 轮 spec P2 / 第 11 轮 P1 修订）：上面那个标称值先按中列宽度收口，
 * 再夹一条下限 = 格内页码字号（三等分把页数锁进 1/3 列宽 + `maxLines = 1` 不省略号 ⇒ 只按比例算会在窄屏 + 大字体下截断整串）。
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
     * 它只是起点，不是实际占比：固定行压扁后仍撑不下 [previewStripMinDp] 时由
     * [panelHeightDp] 继续抬高（维护者 2026-09-21 裁决：**优先保预览条**，52% 作为基线）。
     * 在这个矮视口区间（H < [SHORT_VIEWPORT_MAX_HEIGHT_DP]）里它**永远不会成为约束**：
     * 保底需求（两行标题下 249.6dp）已大于 0.52 × 480dp，所以它只是一条形式上的下限（测试用合成输入钉住它确实是 52%）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT: Float = 0.52f

    /**
     * 矮视口面板高度比例的上限（票 #105 批次 6；裁定 A 后由 66% 上调到 80%）：80%。
     *
     * 用途：保底项（`固定行 + [previewStripMinDp]`）不能无限抬高面板——极矮视口（如 240dp）
     * 下会把整屏都吃掉、标题也被挤出去。上限从 66% 改成 80% 的理由：66% 在 360dp 视口下会先于保底项生效
     * （237.6dp < 249.6dp），预览条只剩 68dp；维护者 2026-09-21 裁定「**保 80dp 优先于压低占比**」，
     * 于是上限放宽到 80%（360dp 视口下 249.6dp = 69.3%，预览条足 80dp）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT_MAX: Float = 0.8f

    /**
     * **手机竖屏**这一档的预览条保底高度（dp）：**224dp**（第 9 轮 A 档压缩前的 200dp 只保留为历史值）。
     *
     * 维护者 2026-09-22 第二次真机反馈：「『上/下一本的按钮和页数统计』的区域占比还是很大 继续压缩
     * 这样预览图还能更大」，编排者据此定了 A 档（底部行 48 → 36dp、面板行距 8 → 4dp）并把省出的高度
     * 全部给这一档：**200 → 224dp**（手机竖屏的面板高度因此与压缩前逐像素相等：372.9dp = 43.8%，
     * 预览条从 200dp 涨到 224dp、一屏从 2.87 张降到 2.56 张——图片变大、张数变少，即维护者要的方向）。
     * **本值只用于手机竖屏这一档**（[isPhonePortrait]）：其余视口仍取
     * [PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP]（80dp），它们的预览条由面板基础占比的余量决定。
     */
    const val PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP: Float = 224f

    /**
     * **其余视口**（矮视口、平板竖屏/横屏、480–700dp 高横屏）的预览条保底高度（dp）：80dp。
     *
     * 这三类视口上维护者没给过「大预览」口径，只有两条下限：
     * ① 矮视口（批次 6 AC11 + 裁定 A）「预览条别再是 16dp 细缝」⇒ 80dp；
     * ② 480–700dp 高的横屏设备（第 5 轮推广）「固定行本身就把预览条压到 0–34dp（480–520dp 下为负）」⇒ 至少 80dp。
     * 取值上只剩「面板仍是 AC4 的 40%」这一半逐像素不变（第 10 轮：A 档的 36dp 行 + 4dp 行距**对所有视口生效**，
     * 不再有「矮视口以外的视口与上一轮逐像素一致」这个说法）：360dp 视口 ⇒ 面板 249.6dp（69.3%）、预览条 80dp；
     * fontScale 1.4 ⇒ 面板 272.6dp（75.7%）、预览条仍 80dp；平板竖屏/横屏的面板仍是 AC4 的 40%；
     * 480dp 高横屏 235.8dp（49.1%）、600dp 高横屏 240dp（40%）——面板侧的算例表在 [panelHeightDp] 的 KDoc 里
     * （预览条侧在 [previewStripTargetDp]），本处只列结论、不另立一份表。
     * 只有把上限顶满时（矮视口下 fontScale ≳ 1.65）预览条才会低于 80dp。
     */
    const val PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP: Float = 80f

    /**
     * 手机竖屏的视口宽上限（dp）：600dp。
     *
     * 「手机竖屏」= 可用高 ≥ [SHORT_VIEWPORT_MAX_HEIGHT_DP]（不是横屏矮视口）**且** 视口宽 < 本值。
     * 依据：手机竖屏视口宽 320–480dp；平板竖屏 ≥ 600dp（票面平板档内宽 728dp + 两侧内边距 40dp = 768dp）；
     * 480–700dp 高的横屏设备（1024×600 平板、480–520dp 档）视口宽 ≥ 800dp。本值落在两群之间。
     */
    const val PHONE_PORTRAIT_MAX_WIDTH_DP: Float = 600f

    /** 是否手机竖屏（票 #105 第 7 轮按视口分档，见 [PHONE_PORTRAIT_MAX_WIDTH_DP]） */
    fun isPhonePortrait(viewportWidthDp: Float, viewportHeightDp: Float): Boolean =
        !isShortViewport(viewportHeightDp) && viewportWidthDp < PHONE_PORTRAIT_MAX_WIDTH_DP

    /**
     * 该视口档位的预览条保底高度（dp，票 #105 第 7 轮分档）：**只有手机竖屏**取大预览
     * [PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP]（224dp，补记 7 ①「手机竖屏 ≥112dp、一屏 2.5–3 张」仍成立；
     * 其余视口取 [PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP]（80dp）。
     *
     * 它只是**下限**：实际目标高度见 [previewStripTargetDp]（面板基础高度在扣掉固定行后能给多少，取较大者）。
     *
     * 为什么按视口分档（第 7 轮 spec P1）：上一轮把 200dp 施加到「所有非矮视口」，把 480/600dp 高横屏的面板
     * 抬到 80%/68.1%、平板横屏抬到 53%，与 AC4「约 40%」、AC11「竖屏与平板占比逐像素一致」冲突。
     */
    fun previewStripMinDp(viewportWidthDp: Float, viewportHeightDp: Float): Float =
        if (isPhonePortrait(viewportWidthDp, viewportHeightDp)) {
            PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP
        } else {
            PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP
        }

    /** 面板的**基础高度**（dp，票 #105 AC4 + 批次 6 AC11）：40%（常规视口）/ 52%（矮视口） */
    fun panelBaseHeightDp(viewportHeightDp: Float): Float =
        if (isShortViewport(viewportHeightDp)) {
            viewportHeightDp * PANEL_HEIGHT_FRACTION_SHORT
        } else {
            viewportHeightDp * PANEL_HEIGHT_FRACTION
        }

    /** 面板内共几行（标题 / 预览条 / 进度条 / 底部行）：行距个数 = 行数 − 1，票 #105 批次 6 AC13 */
    private const val PANEL_ROW_COUNT = 4


    /** 是否按矮视口版式（票 #105 批次 6 AC11）：可用高 < [SHORT_VIEWPORT_MAX_HEIGHT_DP] */
    fun isShortViewport(viewportHeightDp: Float): Boolean = viewportHeightDp < SHORT_VIEWPORT_MAX_HEIGHT_DP

    /** 面板行距（dp）：矮视口压到 0（固定行压扁的一部分），其余视口为 [PANEL_ROW_GAP_DP] */
    fun panelRowGapDp(shortViewport: Boolean): Float = if (shortViewport) 0f else PANEL_ROW_GAP_DP

    /** 标题自己的上侧留白（dp）：矮视口去掉（AC11「标题行去掉上下留白」），其余视口为 [PANEL_TITLE_TOP_PADDING_DP] */
    fun panelTitleTopPaddingDp(shortViewport: Boolean): Float =
        if (shortViewport) 0f else PANEL_TITLE_TOP_PADDING_DP

    /**
     * 固定行合计高度（dp）：标题行（按实际行数） + 进度条行 + 底部行（上/下一本 + 页数同一行）
     * + 行距 + 面板底部内边距 + 面板必然扣掉的底部 inset。
     *
     * 这是「面板高度 − 预览条高度」的**唯一一处**口径（见 [previewStripHeightDp] 的事实说明）。
     * 测试里的保底算例（预览条 = [PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP] / [PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP]）也读它。
     *
     * [titleHeightDp] 是**标题全部行的总高**（dp），由调用方传入——两个因素都必须由调用方算进去，
     * 否则会把固定行算小：
     * ① **fontScale**：`sp` 随系统字体缩放放大，把 sp 数值当 dp 用会少算（[titleLineHeightDp] 含换算）；
     * ② **行数**：长书名会换行（第 6 轮口径：1–3 行、不截断、不省略号），调用方按**实际行数**预算。
     *   生产侧调用点是 `ReaderMenu`（`titleLineHeightDp × 实测行数`，乘法与 `coerceIn` 在 [panelHeightDp]/
     *   [previewStripHeightDp] 里内联）；[titleHeightDp] 的实际消费者是测试——**生产不读它**。
     * [bottomInsetDp] 由调用方从 `readerPanelInsets()` 取（沉浸态由 [ReaderOverlayLayout.MIN_BOTTOM_DP] 兜底为 24dp）。
     */
    fun fixedRowsHeightDp(shortViewport: Boolean, titleHeightDp: Float, bottomInsetDp: Float): Float =
        bottomInsetDp + panelBottomPaddingDp(panelRowGapDp(shortViewport), bottomInsetDp) +
            panelTitleTopPaddingDp(shortViewport) + titleHeightDp +
            panelRowGapDp(shortViewport) * (PANEL_ROW_COUNT - 1) + SLIDER_BAND_HEIGHT_DP + PANEL_FOOTER_HEIGHT_DP

    /**
     * 该视口的**预览条目标高度**（dp，票 #105 第 8 轮）：**只由视口档位决定，标题行数不影响它**。
     *
     * ```
     * target = max( previewStripMinDp(视口宽, 视口高),             // 档位下限（手机竖屏 224 / 其余 80）
     *               panelBaseHeightDp(视口高) − 固定行(标题按**一行**算) )   // 基础面板扣掉最小标题后的余量
     * ```
     *
     * 为什么取「一行标题」那一档的余量（票面第 4 条「行数变化只让面板变高、预览条高度不变」）：
     * `panel = max(base, 固定行(实际行数) + target)`，而 `target ≥ base − 固定行(1 行)`，因此
     * `panel − 固定行(实际行数) = max(base − 固定行(实际行数), target) = target`——**任意行数下预览条都等于它**。
     * 取两行那一档的余量会让 1 行标题时的面板掉到 40% 以下（平板竖屏 380.8dp = 37.2%），与 AC4「约 40%」冲突，
     * 因此基准取**一行**（一行时面板恰好等于基础占比，行数变多只往上长）。
     *
     * 第 9 轮（A 档压缩）后本项实测：手机竖屏 224dp、平板竖屏 253.8dp、平板横屏 151.4dp、
     * 480dp 高横屏 80dp、600dp 高横屏 84.2dp、矮视口 360dp 80dp。
     *
     * [titleLineHeightDp] 是标题**一行**的高（dp，含 fontScale 换算，见 [titleLineHeightDp]）。
     */
    fun previewStripTargetDp(
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        titleLineHeightDp: Float,
        bottomInsetDp: Float,
    ): Float {
        val shortViewport = isShortViewport(viewportHeightDp)
        val oneLineFixed = fixedRowsHeightDp(shortViewport, titleLineHeightDp, bottomInsetDp)
        val baseRemainder = panelBaseHeightDp(viewportHeightDp) - oneLineFixed
        return maxOf(previewStripMinDp(viewportWidthDp, viewportHeightDp), baseRemainder)
    }

    /**
     * 面板高度（dp，票 #105 AC4 + 批次 6 AC11 + 第 7 轮分档 + 第 8 轮「行数只加高面板」）：
     *
     * ```
     * panel = min( max( base, 固定行(实际行数) + previewStripTargetDp(视口宽, 视口高, 一行标题高) ), 视口高 × 80% )
     * base  = panelBaseHeightDp(视口高)   // 40%（常规视口）/ 52%（矮视口）
     * ```
     *
     * 三条性质（第 8 轮补的用例逐条盯）：
     * ① **预览条高度与标题行数无关**（[previewStripTargetDp] 的推导）；
     * ② **面板随行数单调变高**（1 行 = 基础占比，2/3 行逐行加高），上限仍是 80% 屏高；
     * ③ 行数取 [READER_MENU_TITLE_MAX_LINES] 夹取后的值。
     *
     * 实测（一行标题那一档；行数变多只让面板长高、预览条不动）：
     *
     * | 视口 | 面板（1 行） | 占比 | 预览条（1/2/3 行都是它） |
     * | --- | --- | --- | --- |
     * | 手机竖屏 405×852 | 372.9dp | 43.8%（档位下限 224dp 生效） | 224dp |
     * | 平板竖屏 768×1024 | 409.6dp | 40%（= AC4 基础值） | 253.8dp |
     * | 平板横屏 1024×768 | 307.2dp | 40%（= AC4 基础值） | 151.4dp |
     * | 480dp 高横屏 852×480 | 235.8dp | 49.1% | 80dp |
     * | 600dp 高横屏 1024×600 | 240dp | 40% | 84.2dp |
     * | 矮视口 360dp 852×360 | 249.6dp（2 行标题） | 69.3% | 80dp |
     *
     * 手机竖屏的 43.8% 是补记 7 ① 的必然结果（40% 只能给 3.44 张，2.5–3 张必须抬到这一档）；
     * 第 9 轮的 A 档压缩（底部行 48 → 36dp、行距 8 → 4dp）把省下的高度全部给了预览条：
     * 手机竖屏 200 → 224dp、平板竖屏 229.8 → 253.8dp、平板横屏 127.4 → 151.4dp，**面板高度不变**；
     * 480/600dp 高横屏那一档高于 40% 是第 5 轮推广的目的（此前走「恒 40%」时四条固定行把预览条压到 0–34dp，
     * 480–520dp 下算式为负）。上限继续兜底，极矮视口不让面板吃掉整屏。
     */
    fun panelHeightDp(
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        titleLineHeightDp: Float,
        titleLineCount: Int,
        bottomInsetDp: Float,
    ): Float {
        val shortViewport = isShortViewport(viewportHeightDp)
        val lines = titleLineCount.coerceIn(1, READER_MENU_TITLE_MAX_LINES)
        val fixed = fixedRowsHeightDp(shortViewport, titleLineHeightDp * lines, bottomInsetDp)
        val needed = fixed + previewStripTargetDp(viewportWidthDp, viewportHeightDp, titleLineHeightDp, bottomInsetDp)
        val cap = viewportHeightDp * PANEL_HEIGHT_FRACTION_SHORT_MAX
        return maxOf(panelBaseHeightDp(viewportHeightDp), needed).coerceAtMost(cap)
    }

    /**
     * 预览条的**几何模型值**（dp）= [panelHeightDp] − [fixedRowsHeightDp]。
     *
     * 事实说明（票 #105 标准轴 P2）：生产里预览条是 `weight(1f)`，它的高度由 Compose 在布局时分配
     * （结果等于本函数的值，但生产代码**不读**本函数）——本函数的真实消费者是
     * `ReaderMenuLayoutTest` 的张数算例与 AC11 保底算例。保留它是因为那两条口径必须与
     * [panelHeightDp]/[fixedRowsHeightDp] 同源，不能在两处各写一份减法。
     */
    fun previewStripHeightDp(
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        titleLineHeightDp: Float,
        titleLineCount: Int,
        bottomInsetDp: Float,
    ): Float {
        val lines = titleLineCount.coerceIn(1, READER_MENU_TITLE_MAX_LINES)
        return panelHeightDp(viewportWidthDp, viewportHeightDp, titleLineHeightDp, titleLineCount, bottomInsetDp) -
            fixedRowsHeightDp(isShortViewport(viewportHeightDp), titleLineHeightDp * lines, bottomInsetDp)
    }

    /**
     * 面板**内容区宽度**（dp）：屏宽 − 两侧内边距 − 左右 inset。
     *
     * 生产唯一出处（票 #105 标准轴 P2，第 5 轮修）：面板整块消费 `readerPanelInsets()`
     * （四行一致，含标题），因此内容区比屏宽窄两侧内边距 + 左右 inset；预览区宽度与
     * [previewItemHeight] 的收口算据都读它——读未扣 inset 的屏宽会让超宽页按大一圈的宽度收口。
     */
    fun panelInnerWidthDp(viewportWidthDp: Float, horizontalInsetsDp: Float): Float =
        (viewportWidthDp - PANEL_HORIZONTAL_PADDING_DP * 2 - horizontalInsetsDp).coerceAtLeast(0f)

    /**
     * 面板四行的几何（票 #105 批次 6 的四行结构：标题 / 预览条 / 进度条 / 底部行，面板本身不再滚动）。
     * 这些值同时是「一屏几格」算数的输入，因此放在本对象里当**唯一一处来源**（`ReaderMenu` 与
     * `ReaderMenuLayoutTest` 都读它）；实测算例 2.56 / 4.52 张见测试与本对象头部说明。
     */
    const val PANEL_HORIZONTAL_PADDING_DP: Float = 20f

    /**
     * 面板底部内边距的**下限**（dp）：4dp。实际值由 [panelBottomPaddingDp] 算（补记 8 ③）。
     *
     * 下限的存在：底部 inset 很大时（如 48dp）「让上下间距相等」解出的内边距是负数，
     * 那种场合取本下限（宁可两间距不等，也不能负内边距）。
     */
    const val PANEL_BOTTOM_PADDING_MIN_DP: Float = 4f

    /**
     * 面板底部内边距（dp，**纯函数**，补记 8 ③「底部行上下间距必须相等」）。
     *
     * 判据是**中心到中心**的两个间距：
     * ```
     * 上间距 = 滑条行高/2 + 行距 + 底部行高/2
     * 下间距 = 底部行高/2 + 底部内边距 + 实际底部 inset
     * 相等 ⟹ 底部内边距 = 滑条行高/2 + 行距 − 实际底部 inset
     * ```
     * 底部行高（[PANEL_FOOTER_HEIGHT_DP]）与它的 1/2 在两边消掉，因此入参只需「行距」与「实际底部 inset」；
     * 实际底部 inset 由调用方从 `readerPanelInsets()` 取（沉浸态由 [ReaderOverlayLayout.MIN_BOTTOM_DP] 兜底 24dp）。
     * 维护者实测（`21.jpg`）：改动前上间距 ≈56.5dp、下间距 ≈40.6dp（差 ≈16dp）——就是本函数要收掉的那条差。
     * 它只改「内边距这一项」，不影响面板总高公式：`fixedRowsHeightDp` 里本来就是 `inset + 内边距`。
     */
    fun panelBottomPaddingDp(rowGapDp: Float, bottomInsetDp: Float): Float =
        (SLIDER_BAND_HEIGHT_DP / 2f + rowGapDp - bottomInsetDp).coerceAtLeast(PANEL_BOTTOM_PADDING_MIN_DP)

    /**
     * 面板四行之间的行距。取 **4dp**（补记 8 ② 的 A 档：8 → 4）：面板底部有 [panelBottomPaddingDp]
     * + **必然占用的底部 inset**（沉浸态下由 `ReaderOverlayLayout.MIN_BOTTOM_DP` 兜底为 24dp），
     * 连同底部行（补记 8 ②：48 → 36dp）一起收紧，省出的高度全给预览条：
     * 固定行合计比压缩前少 24dp（3 个行距 × 4dp + 底部行 12dp），手机竖屏的预览条因此从 200dp 抬到 224dp。
     * 矮视口把行距压到 0（[panelRowGapDp]）。
     */
    const val PANEL_ROW_GAP_DP: Float = 4f

    /**
     * 底部行**可见**高度（dp，补记 8 ②：48 → 36dp）。
     *
     * 维护者真机反馈「『上/下一本的按钮和页数统计』的区域占比还是很大 继续压缩 这样预览图还能更大」，
     * 编排者定 A 档：可见高压到 36dp。**可点区不跟着缩**：上一本/下一本列的命中带仍是
     * [PANEL_FOOTER_HIT_HEIGHT_DP]（48dp = 触摸目标下限），且**只向下挂**：命中带 = `[行顶, 行顶 + 48dp]`
     * （向上溢出 0、向下溢出 `48 − 行高 = 12dp`；做法与实测见 `ReaderMenuFooter` / `BookStepButton` 的 KDoc）。
     * 矮视口（横屏手机）自批次 6 起就是 36dp，本轮两者合一，不再有「矮视口专用底部行高」常量。
     */
    const val PANEL_FOOTER_HEIGHT_DP: Float = 36f

    /**
     * 底部行里上/下一本那两列的**可点高度**（dp）：48dp = 触摸目标下限。
     *
     * 与可见行高（[PANEL_FOOTER_HEIGHT_DP]，36dp）分开：可见矮 12dp、命中区溢出到行外，
     * 「可点高度 ≥ 48dp」才不被压缩口径弄丢（补记 8 ③「触区不许缩」）。数值与
     * [BOOK_STEP_MIN_HEIGHT_DP] 同值：一个是列的可点高度、一个是可见本体的最小高。
     */
    const val PANEL_FOOTER_HIT_HEIGHT_DP: Float = 48f

    /**
     * 跳页滑动条那一行的高度（票 #105 批次 6 AC13）：48dp = 触摸目标下限。
     *
     * 它**独占一行、在预览条正下方**（不再叠在预览条下缘）：因此不再遮挡任何缩略图（遮挡恒为 0，
     * 旧的「遮挡 ≤ 25%」预算随之作废），滑条整宽可点。
     *
     * 行内**不画任何底色/渐变**（第 6 轮真机反馈第 ③ 条：「预览进度条有个更深的背景色，和阅览菜单的
     * 背景色不一样，直接删掉」）：只有 [SLIDER_TRACK_HEIGHT_DP] 的细线与 [SLIDER_THUMB_DIAMETER_DP] 的圆球。
     * 也不需要 Material3 的 `Slider` 了（第 6 轮真机反馈第 ④ 条要的就是 PV 那种自绘样式），
     * 行高 48dp 由本常量自己守住，不再受 M3 的 44dp 下限牵制。
     *
     * **手势唯一归属**（第 6 轮 AC9 返工的因果说明，口径只有这一处）：`SeekSlider` 自己的 `pointerInput`
     * 接管整行（按下 / 拖动 / 抬手都是一条路），同行里**不再**叠 Material3 `Slider`。上一轮是两条并行：
     * M3 对按下位置做「扣掉拇指半宽」的换算，自接手势另算「行上比例」，实测生效的是 M3 那条
     * （200 页书按下行宽 25% 处跳到第 50 页 = 线性口径的第 51 页；3 页书按下行中点什么都没发出），
     * 真机现象因此是「只有个别位置有效」。删掉 M3 那条后，比例 → 页只有
     * [seekTargetPageForFraction] 一个函数、比例 → 值只有 [sliderValueForFraction] 一个函数。
     */
    const val SLIDER_BAND_HEIGHT_DP: Float = 48f

    /** 跳页滑动条的轨道线高（dp，第 6 轮真机反馈第 ④ 条：学 PV 做成「一条线 + 一个圆球」） */
    const val SLIDER_TRACK_HEIGHT_DP: Float = 2f

    /** 跳页滑动条的圆球直径（dp，第 6 轮真机反馈第 ④ 条）：8dp */
    const val SLIDER_THUMB_DIAMETER_DP: Float = 8f

    /** 未划过的那段轨道颜色（已划过的那段用仓库既有强调色 `ui/AccentColor.ACCENT_ORANGE`） */
    const val SLIDER_TRACK_REMAINDER_COLOR: Long = 0x66FFFFFF

    /**
     * 底部行**页数**的字色（补记 8 ④）：纯白。
     *
     * 维护者第二次真机反馈里列的「页数不要用橙色 用纯白」——`ReaderMenu` 里本来就写的是白，
     * 维护者看到的是编排者预览图画错了橙（编排者已认）；本常量把这句口径钉在唯一一处，
     * 并由 `ReaderMenuFooterTest` 用例锁住「页数白、上/下一本橙」的区分。
     */
    const val PANEL_PAGE_LABEL_COLOR: Long = 0xFFFFFFFF

    /**
     * 上/下一本按钮**可见本体**的最小尺寸（票 #105 AC7；宽 × 高）：96 × 48dp。
     * 票面要的是「按钮加大」（不只可点区域）：原来的裸文字按钮可见尺寸只有文字本身，
     * 本票给它两个尺寸下限（批次 6 AC15 去掉底色/描边后下限照旧守住可量尺寸）。
     * 高度 48dp 与底部行**可见**高（[PANEL_FOOTER_HEIGHT_DP]，36dp）不是一回事：
     * 本体 / 可点区都不缩（见 [PANEL_FOOTER_HIT_HEIGHT_DP]），缩的只是行本身的可视高度。
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
     * 这里把换算显式写成参数，调用方传 `Density.fontScale`。**行数不在本函数里**：调用方按实际行数相乘
     * （见 [titleHeightDp] 与 [READER_MENU_TITLE_MAX_LINES]）。
     */
    fun titleLineHeightDp(panelInnerWidthDp: Float, fontScale: Float): Float =
        panelTitleSp(panelInnerWidthDp) * PANEL_TEXT_LINE_HEIGHT_RATIO * fontScale

    /**
     * 阅读菜单标题的行数上限（第 6 轮真机反馈第 ⑤ 条）：3 行。
     *
     * 维护者原文：「标题名字不要固定 2 行，如果太长就增加到 3 行，动态加长菜单，不要影响到预览图区域，
     * 上限显示 3 行」。它**只管阅读菜单的标题**：浏览页条目名仍是最多两行（[ENTRY_NAME_MAX_LINES]，
     * `CONTEXT.md` 的「条目名称断行」口径）——两者不是同一个东西。
     */
    const val READER_MENU_TITLE_MAX_LINES: Int = 3

    /**
     * 标题行的总高（dp）：一行的高 × **实测行数**（夹在 1..[READER_MENU_TITLE_MAX_LINES]）。
     *
     * 行数由标题的 `onTextLayout` 实测回传（`ReaderMenu`）：短书名 1 行、长书名最多 3 行。
     * 它只是**固定行合计**的一项，而面板高度 = `max(base, 固定行 + 预览条保底)`——
     * 多出来的行只会把面板抬高，**预览条仍拿到保底高度**（维护者第 ⑤ 条「不要影响到预览图区域」）。
     */
    fun titleHeightDp(lineHeightDp: Float, lineCount: Int): Float =
        lineHeightDp * lineCount.coerceIn(1, READER_MENU_TITLE_MAX_LINES)

    /** 面板底部页码字号（sp）：随面板内宽放大，夹在 [PANEL_PAGE_LABEL_MIN_SP]..[PANEL_PAGE_LABEL_MAX_SP] 之间 */
    fun panelPageLabelSp(panelInnerWidthDp: Float): Float =
        scaledSp(panelInnerWidthDp, PANEL_PAGE_LABEL_SP_RATIO, PANEL_PAGE_LABEL_MIN_SP, PANEL_PAGE_LABEL_MAX_SP)

    /**
     * 中列（页数）两侧各留的空白（dp）：文字不贴列边。**三等分的三个中心不受它影响**
     * （三等分由 [PANEL_PAGE_LABEL_SP_RATIO] 之外的 `weight(1f)` 决定，这里只是从可用宽度里扣一份余量，
     * 供 [pageLabelWidthCapSp] 反推字号）。
     */
    const val PAGE_LABEL_COLUMN_INSET_DP: Float = 2f

    /**
     * 单个字符的**占位宽上界**（em = 字号倍数）：数字 ≈0.56em、空格 ≈0.26em、斜杠 ≈0.28em，
     * 而页数串 `"1234 / 5678"` 里数字占多数 ⇒ 真实平均值 ≈0.48em；取 0.5em 作为保守上界。
     * 它只服务 [pageLabelWidthCapSp]（按列宽反推字号），不参与任何绘制。
     */
    const val PAGE_LABEL_CHAR_ADVANCE_EM: Float = 0.5f

    /**
     * 中列（页数）的可用文字宽度（dp）= 面板内宽 ÷ 3 − 两侧各 [PAGE_LABEL_COLUMN_INSET_DP]。
     *
     * 「÷ 3」就是 V1 三等分的中间一份（`ReaderMenuFooter` 里页数是 `Row` 的第二个 `weight(1f)`，
     * 行宽 = 面板内容宽），因此本函数不改三个中心点（1/6 · 1/2 · 5/6），只是把那一份的宽度算出来。
     */
    fun pageLabelColumnWidthDp(panelInnerWidthDp: Float): Float =
        (panelInnerWidthDp / 3f - PAGE_LABEL_COLUMN_INSET_DP * 2f).coerceAtLeast(0f)

    /**
     * 按中列宽度反推的字号上限（sp）：`列宽 ÷ (字符数 × [PAGE_LABEL_CHAR_ADVANCE_EM] × fontScale)`。
     *
     * `fontScale` 必须进算式：字号是 sp，排版后的实际宽高都再乘一遍系统字体缩放。
     * 参数前提由唯一调用者 [pageLabelSp] 保证（`字符数 ≥ 5`、`fontScale = Density.fontScale > 0`），
     * 因此**不设防御分支**（补记 2：死分支要清掉）。
     */
    fun pageLabelWidthCapSp(charCount: Int, panelInnerWidthDp: Float, fontScale: Float): Float =
        pageLabelColumnWidthDp(panelInnerWidthDp) / (charCount * PAGE_LABEL_CHAR_ADVANCE_EM * fontScale)

    /** 面板底部页码的文字（票 #66 的格式，「当前页 / 总页数」）：格式化只有这一处 */
    fun pageLabelText(displayPage: Int, pageCount: Int): String = "$displayPage / $pageCount"

    /**
     * 中列页数**实际使用的字号**（sp，第 10 轮 spec P2 / 第 11 轮 P1 修订）：[panelPageLabelSp] 的 AC6 标称值
     * 先按中列宽度收口（[pageLabelWidthCapSp]），**再夹一条下限 = 格内页码字号**（[previewPageLabelSp]）。
     *
     * 为什么要收口：三等分把页数锁进 1/3 列宽（`ReaderMenuFooter` 的 `weight(1f)`），字号只按比例算时，
     * 窄屏 + 大字体（363dp 机 × fontScale 1.5 × 4 位页码 ≈110dp > 列宽 103.7dp）会把整串截掉，
     * 丢掉「总页数」那一半信息。
     *
     * 为什么要下限（第 11 轮维护者裁决方向：「不截断优先，但层级不许破」）：只按列宽收口会一路压到
     * 9–12sp，跌破 AC6 的 16sp 下限、并压到格内页码之下（`docs/SPEC.md:158` 的层级式）。
     * 下限取 [previewPageLabelSp] ⇒ **渲染字号恒 ≥ 格内页码字号**，且因 `panelPageLabelSp ≥ previewPageLabelSp`
     * （16 ≥ 16 起）也不会被下限抬到标称值之上。下限也放不下时（窄机 + 4 位页码 + fontScale ≳ 1.57）
     * 才允许截断：`ReaderMenuFooter` 给页数 `maxLines = 1` + `softWrap = false` + `TextOverflow.Ellipsis`。
     *
     * **取舍与依据（待维护者确认）**：AC6 的 16sp 下限在极端 fontScale 下让位给「不截断」——
     * 下限只保到「不低于格内页码」；要保住 16sp 只能回票面口径层（给中列留宽或允许换行，两者都动 V1 的三个中心点），
     * 不在本票改动面内。已写进 evidence-impl.md 第 11 轮的残余风险。
     *
     * 算例（票面那台窄机：内宽 323dp、`"1234 / 5678"` 11 字符、列宽 103.7dp、标称 16.15sp、下限 12sp）：
     * 上限 = `103.7 / (11 × 0.5 × fontScale) = 18.85 / fontScale` ⇒ fontScale ≈ 1.17 起上限开始生效、
     * ≈ 1.18 起低于 AC6 的 16sp、≈ 1.57 起落到下限 12sp（这一档才可能截断）。
     * 常规场合（fontScale 1、3–4 位页码、内宽 ≥ 323dp）上限不生效，字号仍是 AC6 的 16–24sp。
     */
    fun pageLabelSp(displayPage: Int, pageCount: Int, panelInnerWidthDp: Float, fontScale: Float): Float {
        val nominal = panelPageLabelSp(panelInnerWidthDp)
        val cap = pageLabelWidthCapSp(pageLabelText(displayPage, pageCount).length, panelInnerWidthDp, fontScale)
        return nominal.coerceAtMost(cap).coerceAtLeast(previewPageLabelSp(panelInnerWidthDp))
    }

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
     * 滑块值（本票的滑块值就是 0..末页页位的浮点页位）→ 跳页目标页（0-based，四舍五入后夹取）。
     *
     * 「手势中预览跟随哪一页」与「手势结束跳到哪一页」共用这一条口径；两端超出轨道的值也被夹到首末页。
     */
    fun seekTargetPage(sliderValue: Float, pageCount: Int): Int =
        clampPage(sliderValue.roundToInt(), pageCount)

    /**
     * 行上按下 / 拖动位置的比例（0..1）→ 跳页目标页（0-based，票 #105 AC9 的唯一落地口径）。
     *
     * 「点按行上任意位置」与「拖动到行上某处」共用这一个函数：`比例 × 末页页位` 四舍五入后夹取。
     * 因此页数少的书（3 页、值域 0..2）里行宽 0–25% 落到第 1 页、25%–75% 落到第 2 页、75%–100% 落到第 3 页，
     * **每个按下位置都有对应页**，不存在「只有最左/最中/最右有效」的死区；两端越界的比例也夹到首末页。
     *
     * 为什么它必须是**唯一**一条（第 6 轮真机反馈第 ⑦ 条，第三次没修好）：上一轮同行里还叠了一个
     * Material3 `Slider`，它对按下位置另有「扣掉拇指半宽」的换算，实测生效的是它、不是本函数
     * （200 页书按下 25% 处实测跳到第 50 页而不是第 51 页）。现在生产的两条路都读本函数：
     * `SeekSlider` 的拖动读 [sliderValueForFraction]，点按读 [SliderGestureState.onTapFraction]，而它也只做
     * `value = sliderValueForFraction(...)` / `emitPage(seekTargetPageForFraction(...))`（第 7 轮 standards P1）。
     */
    fun seekTargetPageForFraction(fraction: Float, pageCount: Int): Int =
        seekTargetPage(sliderValueForFraction(fraction, pageCount), pageCount)

    /** 行上比例（0..1）→ 滑块值（0..末页页位）：拖动时每一帧都读它，与 [seekTargetPageForFraction] 同一个映射 */
    fun sliderValueForFraction(fraction: Float, pageCount: Int): Float =
        fraction.coerceIn(0f, 1f) * lastPage(pageCount)

    /** 滑块值 → 行上比例（0..1，**仅供绘制**圆球位置；手势口径是 [seekTargetPageForFraction]） */
    fun sliderFraction(sliderValue: Float, pageCount: Int): Float {
        val last = lastPage(pageCount)
        return if (last <= 0) 0f else (sliderValue / last).coerceIn(0f, 1f)
    }

    /**
     * 圆球中心的 x（px，**仅供绘制**）：`比例 × 轨道宽`，两端夹到圆球半径内（圆球不越出轨道两端）。
     *
     * `trackWidthPx` 传**整条轨道的宽**（不是「扣掉两个半径后的行程」）：半径的收口只在本函数里做一次，
     * 调用方（`SeekSlider` 的 `Canvas`）直接把返回值当圆心，不得再加回半径（第 7 轮 standards P2：二次内缩
     * 会让绘制端与手势端在两端差一个半径）。
     */
    fun sliderThumbCenterXPx(fraction: Float, trackWidthPx: Float, thumbDiameterPx: Float): Float {
        val radius = thumbDiameterPx / 2f
        val maxX = (trackWidthPx - radius).coerceAtLeast(radius)
        return (fraction.coerceIn(0f, 1f) * trackWidthPx).coerceIn(radius, maxX)
    }
}
