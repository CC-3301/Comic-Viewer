package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 阅读菜单的布局口径（纯函数，由 [ReaderMenuLayoutTest] 与 [ReaderMenuTierGeometryTest] 锁定）。
 *
 * 本对象的各项口径一次重定（改的是同一处布局、互相牵制）：
 *
 * ### 面板高度与预览条保底（**按视口分档**）
 * **所有视口**都走同一条公式：`min(max(base, 固定行 + [ReaderMenuTierGeometry.previewStripTargetDp]), 视口高 × 80%)`，
 * `base` = 40%（常规视口）/ 52%（矮视口：可用高 < [SHORT_VIEWPORT_MAX_HEIGHT_DP]）、
 * 预览条目标高度**按视口分档、且与标题行数无关**（[ReaderMenuTierGeometry.previewStripTargetDp]）：**只有手机竖屏**（[isPhonePortrait]）
 * 取 [PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP]（201dp），其余视口取「[PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP]（80dp）
 * 与『基础面板扣掉一行标题后的余量』里较大的那个」——标题 1/2/3 行下预览条**逐像素相等**，
 * 行数只让面板变高。
 * **固定行按标题的实际行数预算**（1–3 行，[READER_MENU_TITLE_MAX_LINES]；不截断、不省略号）。
 * **按档取值**：
 * **手机竖屏**滑条行 28dp、面板不消费底部 inset；**其余视口**滑条行 48dp、面板消费底部 inset。
 *
 * - **手机竖屏**（852dp、内宽 365dp、一行标题）⇒ 固定行 118.9dp + 目标 221.9dp ⇒ 面板 **340.8dp（40%）**、
 *   预览条 221.9dp、一屏 **2.58 张**；363 × 800dp 机 ⇒ 固定行 118.6dp + 目标 201.4dp ⇒ 面板 **320dp（40%）**、
 *   预览条 **201.4dp**、缩略图 ≈124.7 × 187.0dp、一屏 **2.52 张**（差在整数除法与「页数那一行按 sp
 *   换算」两处零头）。面板**不消费底部 inset** ⇒
 *   两段各 36dp（上段 14 + 4 + 18、下段 18 + 18 + 0）。
 * - **其余视口**：平板竖屏面板 409.6dp（40%、预览条 253.8dp、一屏 4.52）、
 *   平板横屏 307.2dp（40%、预览条 151.4dp）、480dp 高横屏 235.8dp（49.1%、预览条 80dp）、
 *   600dp 高横屏 240dp（40%、预览条 84.2dp）、矮视口 360dp（两行标题）249.6dp（69.3%、预览条 80dp）、
 *   矮视口 fontScale 1.4 ⇒ 272.6dp（75.7%、预览条仍 80dp）。这些档滑条行仍 48dp、面板仍消费底部 inset，
 *   底部内边距按「两段相等」解（见 [ReaderMenuTierGeometry.panelBottomPaddingDp]）。
 *
 * 面板内四行：「标题 / 预览条 / **跳页滑动条** / 底部行（上/下一本 + 页数同一行）」——
 * **预览条吃剩下的高度**（`weight(1f)`），跳页滑动条**独占一行**（按档取 [ReaderMenuTierGeometry.sliderBandHeightDp]：手机竖屏 28dp / 其余 48dp；
 * 不再叠在预览条上），
 * 底部行因此永远在面板里、不需要下滑（现状是面板上限 60% + 整体可滚动，横屏平板上页数与按钮被挤到屏外）。
 *
 * ## 预览项尺寸
 * 单格**高度 = 预览条高 − 页数那一行**、**宽度 = 格内高 × [PREVIEW_CELL_WIDTH_RATIO]**（2/3）——
 * **常量、不看页面比例**：「一屏几格」只由屏幕宽度决定，而格宽不随缩略图到货变化
 * （布局一等不解码结果，「当前页摆到正中」的偏移因此固定）。
 * 缩略图**铺满图框**：**整页可见**（长图两侧留白、宽图上下留白），不裁（比例误差只在取整那一像素内）；
 * 常见竖图（2:3 及更宽）与横图因此铺满格宽，相邻两张的横向视觉缝恒为 [PREVIEW_GAP_DP]；
 * 页面还没解出来时格子里**空的**（底透明、不画占位，只有格内页码），出图只换格内画什么。
 *
 * **一屏张数**（算例与口径见 `ReaderMenuTierGeometryTest`）：口径是「**2.5–2.9 张可接受**」
 * （手机竖屏，2:3 格宽下实测 2.52–2.58）；其余视口档不在这里列精确数字——它们没有用例钉住，写了就是无据的精确值
 * （要看就按 [ReaderMenuTierGeometry.previewStripTargetDp] + [previewCellWidthDp] 现算）。
 *
 * ## 面板内三档字号
 *
 * | 层 | 口径 | 取值范围 |
 * | --- | --- | --- |
 * | 菜单标题 [panelTitleSp] | `内宽 × 0.05` | 18–24sp |
 * | 面板底部页码 [panelPageLabelSp] | `内宽 × 0.05` | 16–24sp |
 * | 格内页码 [previewPageLabelSp] | `内宽 × 0.035` | 12–16sp |
 *
 * 不变量 **标题 ≥ 页码 ≥ 格内页码**（正常档页码**严格大于**格内页码；极端 fontScale 下取等号——
 * 下限就是格内页码字号，见 [pageLabelSp]）：标题与页码比例相同、但标题的上下限更高（18–24 vs 16–24），
 * 格内页码的比例与上下限都最低。三处都随面板内宽放大（面板是 `fillMaxWidth()`），
 * 公式只有 [scaledSp] 一处。
 *
 * 底部页码的**实际取值**走 [pageLabelSp]：上面那个标称值先按中列宽度收口，
 * 再夹一条下限 = 格内页码字号（三等分把页数锁进 1/3 列宽 + `maxLines = 1` ⇒ 只按比例算会在窄屏 + 大字体下截断整串）。
 * 截断口径：**常规档不省略号**（字号收口后放得下），只有连下限也放不下的极端 fontScale 才允许省略号
 * （`ReaderMenuFooter` 给页数 `TextOverflow.Ellipsis`）。
 *
 * 按视口分档的那些取值（面板占比与基础高、行距、标题上留白、滑条行高、预览条保底、底内边距）
 * 只在 [ReaderMenuTierGeometry] 一处，由 [tierGeometry] 一次求出；本对象不另设同名读取入口，
 * 生产与用例都读几何的字段与派生方法（固定行合计 / 预览条目标高度 / 面板高度 / 预览条高度）。
 */
object ReaderMenuLayout {

    /** 面板高度占视口高度的比例（手机/平板/横屏统一到 40%） */
    const val PANEL_HEIGHT_FRACTION: Float = 0.4f

    // ---------- 矮视口（横屏手机）版式 ----------

    /**
     * 矮视口判定阈值（dp）：**可用高**小于它就按矮视口版式。
     *
     * 取 480dp 的依据：横屏手机的可用高约 360–440dp，而竖屏手机 ≥ 640dp、横屏平板 ≥ 600dp、
     * 平板竖屏 ≥ 1024dp——阈值落在两群之间，竖屏与平板因此**逐像素不变**。
     */
    const val SHORT_VIEWPORT_MAX_HEIGHT_DP: Float = 480f

    /**
     * 矮视口面板高度比例的**起点**：40% → 52%。
     *
     * 它只是起点，不是实际占比：固定行压扁后仍撑不下 [ReaderMenuTierGeometry.previewStripMinDp] 时由
     * [ReaderMenuTierGeometry.panelHeightDp] 继续抬高（**优先保预览条**，52% 作为基线）。
     * 在这个矮视口区间（H < [SHORT_VIEWPORT_MAX_HEIGHT_DP]）里它**永远不会成为约束**：
     * 保底需求（两行标题下 249.6dp）已大于 0.52 × 480dp，所以它只是一条形式上的下限（测试用合成输入钉住它确实是 52%）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT: Float = 0.52f

    /**
     * 矮视口面板高度比例的上限（由 66% 上调到 80%）：80%。
     *
     * 用途：保底项（`固定行 + [ReaderMenuTierGeometry.previewStripMinDp]`）不能无限抬高面板——极矮视口（如 240dp）
     * 下会把整屏都吃掉、标题也被挤出去。上限从 66% 改成 80% 的理由：66% 在 360dp 视口下会先于保底项生效
     * （237.6dp < 249.6dp），预览条只剩 68dp；口径是「**保 80dp 优先于压低占比**」，
     * 于是上限放宽到 80%（360dp 视口下 249.6dp = 69.3%，预览条足 80dp）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT_MAX: Float = 0.8f

    /**
     * **手机竖屏**这一档的预览条保底高度（dp）：**201dp**——面板取基础 40%，预览条 = 40%
     * 扣掉固定行后的余量（设备反馈「一屏只 2.2 张」后收口到这一档）。
     *
     * 余量算例（363 × 800dp 机 201.4dp、缩略图 ≈124.7 × 187.0dp、一屏 2.52 张；
     * 405dp 宽机（内宽 365dp）221.9dp、一屏 2.58 张；口径见 `ReaderMenuTierGeometryTest`）。
     * 本值只兜「余量意外变小时也不低于它」这一层。
     * **本值只用于手机竖屏这一档**（[isPhonePortrait]）：其余视口仍取
     * [PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP]（80dp），它们的预览条由面板基础占比的余量决定。
     */
    const val PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP: Float = 201f

    /**
     * **其余视口**（矮视口、平板竖屏/横屏、480–700dp 高横屏）的预览条保底高度（dp）：80dp。
     *
     * 这三类视口上没有「大预览」口径，只有两条下限：
     * ① 矮视口「预览条不再是 16dp 细缝」⇒ 80dp；
     * ② 480–700dp 高的横屏设备「固定行本身就把预览条压到 0–34dp（480–520dp 下为负）」⇒ 至少 80dp。
     * 取值上只剩「面板仍是 40%」这一半逐像素不变（36dp 行 + 4dp 行距**对所有视口生效**，
     * 不再有「矮视口以外的视口与改动前逐像素一致」这个说法）：360dp 视口 ⇒ 面板 249.6dp（69.3%）、预览条 80dp；
     * fontScale 1.4 ⇒ 面板 272.6dp（75.7%）、预览条仍 80dp；平板竖屏/横屏的面板仍是 40%；
     * 480dp 高横屏 235.8dp（49.1%）、600dp 高横屏 240dp（40%）——面板侧的算例表在 [ReaderMenuTierGeometry.panelHeightDp] 的 KDoc 里
     * （预览条侧在 [ReaderMenuTierGeometry.previewStripTargetDp]），本处只列结论、不另立一份表。
     * 只有把上限顶满时（矮视口下 fontScale ≳ 1.65）预览条才会低于 80dp。
     */
    const val PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP: Float = 80f

    /**
     * 手机竖屏的视口宽上限（dp）：600dp。
     *
     * 「手机竖屏」= 可用高 ≥ [SHORT_VIEWPORT_MAX_HEIGHT_DP]（不是横屏矮视口）**且** 视口宽 < 本值。
     * 依据：手机竖屏视口宽 320–480dp；平板竖屏 ≥ 600dp（平板档内宽 728dp + 两侧内边距 40dp = 768dp）；
     * 480–700dp 高的横屏设备（1024×600 平板、480–520dp 档）视口宽 ≥ 800dp。本值落在两群之间。
     */
    const val PHONE_PORTRAIT_MAX_WIDTH_DP: Float = 600f

    /** 是否手机竖屏（按视口分档，见 [PHONE_PORTRAIT_MAX_WIDTH_DP]） */
    fun isPhonePortrait(viewportWidthDp: Float, viewportHeightDp: Float): Boolean =
        !isShortViewport(viewportHeightDp) && viewportWidthDp < PHONE_PORTRAIT_MAX_WIDTH_DP

    /** 面板内共几行（标题 / 预览条 / 进度条 / 底部行）：行距个数 = 行数 − 1 */
    private const val PANEL_ROW_COUNT = 4


    /** 是否按矮视口版式：可用高 < [SHORT_VIEWPORT_MAX_HEIGHT_DP] */
    fun isShortViewport(viewportHeightDp: Float): Boolean = viewportHeightDp < SHORT_VIEWPORT_MAX_HEIGHT_DP

    /**
     * 本视口档的**档位几何**：两个档位判据（[isPhonePortrait] / [isShortViewport]）**各算一次**，
     * 六个「按档取值」一次算齐，见 [ReaderMenuTierGeometry]。
     *
     * 生产（`ReaderMenu`）与用例都只读它的字段与派生方法，不再各自 `if (phonePortrait)`：本对象只有
     * 这一个档位几何出口。
     */
    fun tierGeometry(viewportWidthDp: Float, viewportHeightDp: Float, bottomInsetDp: Float): ReaderMenuTierGeometry =
        resolveTierGeometry(
            phonePortrait = isPhonePortrait(viewportWidthDp, viewportHeightDp),
            shortViewport = isShortViewport(viewportHeightDp),
            viewportHeightDp = viewportHeightDp,
            bottomInsetDp = bottomInsetDp,
        )

    /**
     * 档位几何的**唯一实现**：两个标志与视口高/底部 inset 进来，六个按档取值一次算齐。
     *
     * 这里也是六个分档分支的唯一住处（全对象只此一处）：滑条行高、预览条保底、行距、标题上留白、占比、
     * 底内边距各一条 `if`。**新增或调整任何一档尺寸只改这里。**
     */
    private fun resolveTierGeometry(
        phonePortrait: Boolean,
        shortViewport: Boolean,
        viewportHeightDp: Float,
        bottomInsetDp: Float,
    ): ReaderMenuTierGeometry {
        val panelHeightFraction = if (shortViewport) PANEL_HEIGHT_FRACTION_SHORT else PANEL_HEIGHT_FRACTION
        val rowGapDp = if (shortViewport) 0f else PANEL_ROW_GAP_DP
        val titleTopPaddingDp = if (shortViewport) 0f else PANEL_TITLE_TOP_PADDING_DP
        val sliderBandHeightDp =
            if (phonePortrait) SLIDER_BAND_HEIGHT_PHONE_PORTRAIT_DP else SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP
        val panelBottomPaddingDp = bottomPaddingDp(
            rowGapDp = rowGapDp,
            bottomInsetDp = bottomInsetDp,
            phonePortrait = phonePortrait,
            sliderBandHalfHeightDp = sliderBandHeightDp / 2f,
        )
        return ReaderMenuTierGeometry(
            phonePortrait = phonePortrait,
            shortViewport = shortViewport,
            panelHeightFraction = panelHeightFraction,
            panelBaseHeightDp = viewportHeightDp * panelHeightFraction,
            rowGapDp = rowGapDp,
            titleTopPaddingDp = titleTopPaddingDp,
            sliderBandHeightDp = sliderBandHeightDp,
            previewStripMinDp =
                if (phonePortrait) PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP else PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP,
            panelBottomPaddingDp = panelBottomPaddingDp,
            // 固定行里除标题以外的部分：底部 inset 只有非手机竖屏档消费、底内边距与标题上留白刚算出、
            // 行距个数 = 行数 − 1、再加滑条行与底部行
            fixedChromeHeightDp = (if (phonePortrait) 0f else bottomInsetDp) + panelBottomPaddingDp +
                titleTopPaddingDp + rowGapDp * (PANEL_ROW_COUNT - 1) + sliderBandHeightDp + PANEL_FOOTER_HEIGHT_DP,
            panelHeightCapDp = viewportHeightDp * PANEL_HEIGHT_FRACTION_SHORT_MAX,
        )
    }

    /**
     * 面板底部内边距的**唯一算式**（[ReaderMenuTierGeometry.panelBottomPaddingDp] 的取值）：
     *
     * - **手机竖屏档**：面板不消费底部 inset ⇒ 判据里的 inset 项为 0，
     *   `P = 滑条行半高 + 行距 = 14 + 4 = 18dp`。上段 = 14 + 4 + 18 = 36、下段 = 18 + 18 + 0 = 36。
     * - **其余档**（平板竖屏/横屏、480–700dp 高横屏、矮视口，按「逐像素不变」保留改动前口径）：
     *   `P = max(下限, 滑条行半高 + 行距 − 实际底部 inset)`——面板消费底部 inset，解出负值时回
     *   [PANEL_BOTTOM_PADDING_MIN_DP]（宁可两段不等、也不给负内边距）。
     */
    private fun bottomPaddingDp(
        rowGapDp: Float,
        bottomInsetDp: Float,
        phonePortrait: Boolean,
        sliderBandHalfHeightDp: Float,
    ): Float =
        if (phonePortrait) {
            sliderBandHalfHeightDp + rowGapDp
        } else {
            (sliderBandHalfHeightDp + rowGapDp - bottomInsetDp).coerceAtLeast(PANEL_BOTTOM_PADDING_MIN_DP)
        }

    /**
     * 面板**内容区宽度**（dp）：屏宽 − 两侧内边距 − 左右 inset。
     *
     * 生产唯一出处：面板整块消费 `readerPanelInsets()`
     * （四行一致，含标题），因此内容区比屏宽窄两侧内边距 + 左右 inset；格内页码字号与面板各行的宽度都读它
     * （读未扣 inset 的屏宽会把内容区算大一圈）。
     */
    fun panelInnerWidthDp(viewportWidthDp: Float, horizontalInsetsDp: Float): Float =
        (viewportWidthDp - PANEL_HORIZONTAL_PADDING_DP * 2 - horizontalInsetsDp).coerceAtLeast(0f)

    /**
     * 面板四行的几何（四行结构：标题 / 预览条 / 进度条 / 底部行，面板本身不再滚动）。
     * 这些值同时是「一屏几格」算数的输入，因此放在本对象里当**唯一一处来源**（`ReaderMenu` 与
     * `ReaderMenuLayoutTest` 都读它）；算例（手机竖屏 2.52–2.58 张、平板竖屏 4.52 张）见测试与本对象头部说明。
     */
    const val PANEL_HORIZONTAL_PADDING_DP: Float = 20f

    /** 底部内边距下限（dp）：4dp。只有**非手机竖屏档**用得上（见 [ReaderMenuTierGeometry.panelBottomPaddingDp]） */
    const val PANEL_BOTTOM_PADDING_MIN_DP: Float = 4f

    /**
     * 面板四行之间的行距。取 **4dp**（8 → 4）：它与底部行（48 → 36dp）、
     * 滑条行（按档取 [ReaderMenuTierGeometry.sliderBandHeightDp]）和 [ReaderMenuTierGeometry.panelBottomPaddingDp] 一起定出底部两段间距。
     *
     * **手机竖屏**（滑条行 28dp）：「两段各 36dp」（上段 = 14 + 4 + 18、下段 = 18 + 18 + 0——
     * 下段第二项为 0 是因为面板不消费底部 inset）；
     * **其余档**（滑条行 48dp、面板消费 inset）：回到改动前的「两段相等」口径（中心到中心 24 + 4 + 18 = 46dp，
     * 下段 = 18 + P + inset 与它相等，P 由 [ReaderMenuTierGeometry.panelBottomPaddingDp] 解出）。
     * 矮视口把行距压到 0（[ReaderMenuTierGeometry.rowGapDp]）。
     */
    const val PANEL_ROW_GAP_DP: Float = 4f

    /**
     * 底部行**可见**高度（dp，48 → 36dp）。
     *
     * 设备反馈「『上/下一本的按钮和页数统计』的区域占比还是很大 继续压缩 这样预览图还能更大」，
     * 可见高压到 36dp。**可点区不跟着缩**：上一本/下一本列的命中带仍是
     * [PANEL_FOOTER_HIT_HEIGHT_DP]（48dp = 触摸目标下限），且**只向下挂**：命中带 = `[行顶, 行顶 + 48dp]`
     * （向上溢出 0、向下溢出 `48 − 行高 = 12dp`；做法见 `ReaderMenuFooter` / `BookStepButton` 的 KDoc）。
     * 矮视口（横屏手机）同为 36dp，两者合一，不再有「矮视口专用底部行高」常量。
     */
    const val PANEL_FOOTER_HEIGHT_DP: Float = 36f

    /**
     * 底部行里上/下一本那两列的**可点高度**（dp）：48dp = 触摸目标下限。
     *
     * 与可见行高（[PANEL_FOOTER_HEIGHT_DP]，36dp）分开：可见矮 12dp、命中区溢出到行外，
     * 「可点高度 ≥ 48dp」才不被压缩口径弄丢（「触区不许缩」）。数值与
     * [BOOK_STEP_MIN_HEIGHT_DP] 同值：一个是列的可点高度、一个是可见本体的最小高。
     */
    const val PANEL_FOOTER_HIT_HEIGHT_DP: Float = 48f

    /**
     * 跳页滑动条那一行的高度（dp）——**非手机竖屏档**（改动前口径，48dp = 触摸目标下限）：
     * 平板竖屏/横屏、480–700dp 高横屏、矮视口都取本值，因此这些档的行高逐像素不变。
     * **手机竖屏档**取 [SLIDER_BAND_HEIGHT_PHONE_PORTRAIT_DP]（28dp，取舍）。
     * 生产只经档位几何取值（`ReaderMenu` 从 [ReaderMenuTierGeometry.sliderBandHeightDp] 传行高与 `SeekSlider`
     * 的命中行高）；测试另直接钉两档的字面值（本常量 48dp / [SLIDER_BAND_HEIGHT_PHONE_PORTRAIT_DP] 28dp）。
     *
     * 它**独占一行、在预览条正下方**：因此不遮挡任何缩略图（遮挡恒为 0），滑条整宽可点。
     *
     * 行内**不画任何底色/渐变**（设备反馈：「预览进度条有个更深的背景色，和阅览菜单的
     * 背景色不一样，直接删掉」）：只有 [SLIDER_TRACK_HEIGHT_DP] 的细线与 [SLIDER_THUMB_DIAMETER_DP] 的圆球。
     * 也不需要 Material3 的 `Slider` 了（设备反馈要的就是 PV 那种自绘样式），
     * 行高由各档的常量自己守住（本档 48dp、手机竖屏档 28dp），不再受 M3 的 44dp 下限牵制。
     *
     * **手势唯一归属**（口径只有这一处）：`SeekSlider` 自己的 `pointerInput`
     * 接管整行（按下 / 拖动 / 抬手都是一条路），**不叠 Material3 `Slider`**——两条并存会互相抢：
     * M3 对按下位置做「扣掉拇指半宽」的换算，自接手势另算「行上比例」，按下位置与落页对不上
     * （200 页书按下行宽 25% 处 M3 给第 50 页 = 线性口径的第 51 页；3 页书按下行中点 M3 什么都没发出）。
     * 单一归属后，比例 → 页只有
     * [seekTargetPageForFraction] 一个函数、比例 → 值只有 [sliderValueForFraction] 一个函数。
     */
    const val SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP: Float = 48f

    /**
     * **手机竖屏档**的滑条行高度（dp）：**28dp**（「滑条行压扁后可拖区随之变小，属取舍」）。
     * 它同时是手机竖屏档「上段 = 14 + 4 + 18 = 36dp」里的滑条行半高。
     */
    const val SLIDER_BAND_HEIGHT_PHONE_PORTRAIT_DP: Float = 28f

    /** 跳页滑动条的轨道线高（dp，学 PV 做成「一条线 + 一个圆球」） */
    const val SLIDER_TRACK_HEIGHT_DP: Float = 2f

    /** 跳页滑动条的圆球直径（dp）：8dp */
    const val SLIDER_THUMB_DIAMETER_DP: Float = 8f

    /** 未划过的那段轨道颜色（已划过的那段用仓库既有强调色 `ui/AccentColor.ACCENT_ORANGE`） */
    const val SLIDER_TRACK_REMAINDER_COLOR: Long = 0x66FFFFFF

    /**
     * 底部行**页数**的字色：纯白。
     *
     * 「页数不要用橙色 用纯白」——`ReaderMenu` 里本来就写的是白，
     * 预览图那里画错了橙；本常量把这句口径钉在唯一一处，
     * 并由 `ReaderMenuFooterTest` 用例锁住「页数白、上/下一本橙」的区分。
     */
    const val PANEL_PAGE_LABEL_COLOR: Long = 0xFFFFFFFF

    /**
     * 上/下一本按钮**可见本体**的最小尺寸（宽 × 高）：96 × 48dp。
     * 要的是「按钮加大」（不只可点区域）：原来的裸文字按钮可见尺寸只有文字本身，
     * 这里给它两个尺寸下限（去掉底色/描边后下限照旧守住可量尺寸）。
     * 高度 48dp 与底部行**可见**高（[PANEL_FOOTER_HEIGHT_DP]，36dp）不是一回事：
     * 本体 / 可点区都不缩（见 [PANEL_FOOTER_HIT_HEIGHT_DP]），缩的只是行本身的可视高度。
     */
    const val BOOK_STEP_MIN_WIDTH_DP: Float = 96f

    /** 见 [BOOK_STEP_MIN_WIDTH_DP]（48dp 同时是触摸目标下限） */
    const val BOOK_STEP_MIN_HEIGHT_DP: Float = 48f

    /** 预览项之间的间隙（与原实现一致） */
    const val PREVIEW_GAP_DP: Float = 6f

    /**
     * 预览条单格宽与「格内高」的比例：**2/3**。
     *
     * 命中这个数的理由：常见竖版页（2:3 及更宽的竖图）与横图都铺满格宽，
     * 相邻两张的横向视觉缝因此恒为 [PREVIEW_GAP_DP]（不随页面比例变）；比 2:3 更窄的长条图
     * 撑满格内高、只留两侧缝。代价是**一屏张数偏多**（手机竖屏 2.52–2.58 张），
     * 因此口径是 **2.5–2.9 张**（判据见 `ReaderMenuTierGeometryTest`）。
     * 它是**常量、不看页面比例**：格宽不随缩略图到货变化（布局一等不解码结果，到货只换格子里画什么），
     * 「当前页摆到正中」的偏移也就固定（判据见 `docs/spec/reader.md` 的「预览条」）。
     */
    const val PREVIEW_CELL_WIDTH_RATIO: Float = 2f / 3f

    /**
     * 放大字号的行高比例（× 字号）：字号大于原行高时数字/文字不被压，留 20% 余量，
     * 面板底部页码、格内页码与菜单标题共用这一份。
     */
    const val PANEL_TEXT_LINE_HEIGHT_RATIO: Float = 1.2f

    /**
     * 标题上方留白（dp）：面板顶边 → 标题第一行行顶，由标题自己带（面板不再有上侧内边距）。
     * 真量见 `ReaderMenuTitleTest`。
     */
    const val PANEL_TITLE_TOP_PADDING_DP: Float = 3f

    // ---------- 预览项尺寸 ----------

    /**
     * **格内高**（dp）：预览条高扣掉页数那一行——格子的可用高，也是单格宽的算据（[previewCellWidthDp]）。
     * 页数行比预览条还高时回 0（不出负高度）。
     */
    fun previewBodyHeightDp(stripHeightDp: Float, labelHeightDp: Float): Float =
        (stripHeightDp - labelHeightDp).coerceAtLeast(0f)

    /**
     * 单格宽度（dp）：**格内高 × [PREVIEW_CELL_WIDTH_RATIO]**——常量，与页面比例无关。
     *
     * 格宽是常量，缩略图**铺满图框**（整页可见、不裁），因此
     * 「一屏几格」只由屏幕宽度决定，而格宽不随解码结果变（旧口径是「宽 = 高 × 该页比例」，
     * 于是每张图到货都会推动整条重排）。
     */
    fun previewCellWidthDp(bodyHeightDp: Float): Float = bodyHeightDp * PREVIEW_CELL_WIDTH_RATIO

    /**
     * 缩略图在格子里的**实际显示宽度**（dp）：按比例缩进 [cellWidthDp] × [cellHeightDp]
     * 的格子——整页可见、不裁（长图两侧留缝、宽图上下留缝）。
     *
     * [imageAspect] 是图的宽高比（宽 ÷ 高）；非正数、以及**非有限值**（位图有一维是 0 ⇒ ±Inf 或 NaN）
     * 或格子没量出尺寸时回 0——回 0 就是「这一格没有图可画」。
     * 框贴的就是按它算出的这块矩形（外扩 [PREVIEW_FRAME_DP]），因此框跟着图走、不贴格子边。
     */
    fun previewImageWidthDp(cellWidthDp: Float, cellHeightDp: Float, imageAspect: Float): Float {
        if (imageAspect <= 0f || !imageAspect.isFinite()) return 0f
        return minOf(cellWidthDp, cellHeightDp * imageAspect).coerceAtLeast(0f)
    }

    /**
     * 当前页那一格的框粗细（dp）：**画在图的轮廓外侧**（外扩，不盖住图的像素）。
     * 非当前页不画框——底是透明的，框就是给当前页的标记（页码也用同一个橙色）。
     */
    const val PREVIEW_FRAME_DP: Float = 2f

    /**
     * 格内页数那一行的行高（**sp**）：字号 × [PANEL_TEXT_LINE_HEIGHT_RATIO]。
     *
     * 返回的是 **sp 值**（与字号同一单位），调用方必须用 `Density.toDp()` 换成 dp 再当高度用：
     * `sp` 会随系统字体缩放（fontScale）放大，把 sp 数值当 dp 用会在放大字体下把页数那一行算小、
     * 进而把缩略图算高（被字撑破）。`ReaderMenu` 与 `ReaderMenuLayoutTest` 都走这条换算。
     *
     * 页数从「叠在缩略图右上角」改到「缩略图正下方居中」后，它**自己要占一行**：
     * 预览条的可用高被这一行吃掉，格内高 = 预览条高 − 它。
     */
    fun previewLabelHeightSp(labelSp: Float): Float = labelSp * PANEL_TEXT_LINE_HEIGHT_RATIO

    /**
     * 「当前页缩略图落在预览区正中」要传给 `LazyListState.scrollToItem` 的偏移（px）：
     * `−(视口宽 − 条目宽) / 2`；条目不比视口窄时回 0（没有可用的居中空间）。
     *
     * 符号依 `scrollToItem(index, scrollOffset)` 的口径——`scrollOffset` 是「条目相对视口起点的偏移的相反数」，
     * 因此**负数**才是把条目往右推（往回滚）的那个方向；正负号搞反会把当前页推出视口。
     * 条目宽取**目标项自己**的测量宽（`visibleItemsInfo.size`）：生产里每格等宽（固定格宽，见 [previewCellWidthDp]）
     * ，按测量宽取因此与「拿格宽常量算」等价，纯函数层不必知道格宽从哪来。
     * 视口宽取**预览区的实际宽**（条子节点宽，含 [contentPaddingPx] 那部分内缩）。
     * [contentPaddingPx] 是条子左右各让出的量（一个框宽，见 [PREVIEW_FRAME_DP]）：条目是从**内容区**起算的，
     * 因此居中量要把这段内缩加回来，否则当前页会偏出一个框宽。
     *
     * 两端的处理**不在这里**（「首页与末页除外，到头只能贴边」）：两端由 `LazyList` 自己夹取滚动量——
     * 首页要求往回滚、末页要求往前滚，两头的滚动位置都被夹在 `[0, 最大滚动量]` 内，
     * 于是首页贴**内容区**左缘、末页贴**内容区**右缘，本函数不需要为它们走分支。
     */
    fun previewCenterScrollOffsetPx(itemWidthPx: Int, viewportWidthPx: Int, contentPaddingPx: Int): Int =
        (-((viewportWidthPx - itemWidthPx) / 2) + contentPaddingPx).coerceAtMost(0)

    /**
     * 预览项的解码目标高度（px）：与封面同一把尺子——[CoverDecode.targetWidthPx] 只上取到 32px 的
     * 整数倍（分桶规则只有那一处实现，与量的是宽还是高无关）。因此解码高度恒 ≥ 格内高像素
     * （格子变大后不会拿旧高度的位图拉伸变糊），过冲 < 32px、±1px 的布局抖动也只解一次。图在格子里铺满图框（比例误差只在取整那一像素内），
     * 所以按高度解码就够、不需要另解一遍宽度。
     */
    fun previewDecodeHeightPx(itemHeightPx: Float): Int = CoverDecode.targetWidthPx(itemHeightPx)

    // ---------- 面板内三档字号 ----------

    /**
     * 菜单标题字号比例（sp/dp）：标题与面板页码同比例（0.05），靠上下限分出层级（标题 18–24、页码 16–24）。
     * 360dp 屏（内宽约 320dp）不夹是 16sp、被下限抬到 18sp；平板（内宽约 740dp）不夹是 37sp、被上限压到 24sp
     * （手机 18.3sp、平板 24sp，对应内宽 365 / 740）。
     */
    const val PANEL_TITLE_SP_RATIO: Float = 0.05f

    /** 菜单标题字号下限（sp）：18sp（现状 22–32sp 档整体降一档） */
    const val PANEL_TITLE_MIN_SP: Float = 18f

    /**
     * 菜单标题字号上限（sp）：24sp。必须**大于等于**面板页码上限（同为 24sp）——
     * 同比例下宽面板上两者会同时顶到上限，层级由下限与格内页码一起保住（见 [previewPageLabelSp]）。
     */
    const val PANEL_TITLE_MAX_SP: Float = 24f

    /** 面板底部页码字号比例（sp/dp）：与标题同比例，`内宽 × 0.05` */
    const val PANEL_PAGE_LABEL_SP_RATIO: Float = 0.05f

    /**
     * 面板底部页码字号下限（sp）：16sp。与格内页码上限同值，但**层级不靠上下限、靠比例**：
     * 格内页码比例更低（0.035 vs 0.05），页码顶到 16sp 的下限时格内页码才 12sp（见 [previewPageLabelSp]）。
     */
    const val PANEL_PAGE_LABEL_MIN_SP: Float = 16f

    /** 面板底部页码字号上限（sp）：24sp */
    const val PANEL_PAGE_LABEL_MAX_SP: Float = 24f

    /** 格内页码字号比例（sp/dp）：`内宽 × 0.035`（手机 12.8sp、平板 16sp） */
    const val PREVIEW_LABEL_SP_RATIO: Float = 0.035f

    /** 格内页码字号下限（sp）：12sp（原 `labelSmall` 的 11sp 之上一档） */
    const val PREVIEW_LABEL_MIN_SP: Float = 12f

    /**
     * 格内页码字号上限（sp）：16sp，三层里最小的一档。上限与面板页码下限同值，
     * 层级由比例保住（格内页码 0.035 < 页码 0.05）：格内页码顶到 16sp 时页码已 ≥ 22.9sp。
     */
    const val PREVIEW_LABEL_MAX_SP: Float = 16f

    /** 菜单标题字号（sp）：随面板内宽放大，夹在 [PANEL_TITLE_MIN_SP]..[PANEL_TITLE_MAX_SP] 之间 */
    fun panelTitleSp(panelInnerWidthDp: Float): Float =
        scaledSp(panelInnerWidthDp, PANEL_TITLE_SP_RATIO, PANEL_TITLE_MIN_SP, PANEL_TITLE_MAX_SP)

    /**
     * 标题**一行**的高（dp）：字号 sp × [PANEL_TEXT_LINE_HEIGHT_RATIO] × [fontScale]。
     *
     * `sp` 与 `dp` 在 fontScale ≠ 1 时不等值（`1.sp.toDp() == fontScale`），而固定行合计必须拿 **dp**；
     * 这里把换算显式写成参数，调用方传 `Density.fontScale`。**行数不在本函数里**：调用方按实际行数相乘
     * （见 [titleHeightDp] 与 [READER_MENU_TITLE_MAX_LINES]）。
     */
    fun titleLineHeightDp(panelInnerWidthDp: Float, fontScale: Float): Float =
        panelTitleSp(panelInnerWidthDp) * PANEL_TEXT_LINE_HEIGHT_RATIO * fontScale

    /**
     * 阅读菜单标题的行数上限：3 行。
     *
     * 原文：「标题名字不要固定 2 行，如果太长就增加到 3 行，动态加长菜单，不要影响到预览图区域，
     * 上限显示 3 行」。它**只管阅读菜单的标题**：浏览页条目名仍是最多两行（[ENTRY_NAME_MAX_LINES]，
     * `GLOSSARY.md` 的「条目名称断行」口径）——两者不是同一个东西。
     */
    const val READER_MENU_TITLE_MAX_LINES: Int = 3

    /**
     * 标题行的总高（dp）：一行的高 × **实际行数**（夹在 1..[READER_MENU_TITLE_MAX_LINES]）。
     *
     * 行数由标题的 `onTextLayout` 回传（`ReaderMenu`）：短书名 1 行、长书名最多 3 行。
     * 它只是**固定行合计**的一项，而面板高度 = `max(base, 固定行 + 预览条保底)`——
     * 多出来的行只会把面板抬高，**预览条仍拿到保底高度**（「不要影响到预览图区域」）。
     *
     * 「行数 → 标题总高」只有这一处：[ReaderMenuTierGeometry.panelHeightDp] / [ReaderMenuTierGeometry.previewStripHeightDp] 都调它，
     * 不再各自内联一遍乘法与夹取。
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
     * 参数前提由**生产侧唯一调用者** [pageLabelSp] 保证（`字符数 ≥ 5`、`fontScale = Density.fontScale > 0`）；
     * 测试侧也直接调它（`ReaderMenuLayoutTest` 的页数收口用例），同样传 ≥ 5 字符与正的 fontScale，
     * 因此**不设防御分支**（死分支要清掉；`charCount = 0` 只会得到 +∞，等于不设上限）。
     */
    fun pageLabelWidthCapSp(charCount: Int, panelInnerWidthDp: Float, fontScale: Float): Float =
        pageLabelColumnWidthDp(panelInnerWidthDp) / (charCount * PAGE_LABEL_CHAR_ADVANCE_EM * fontScale)

    /** 面板底部页码的文字（「当前页 / 总页数」）：格式化只有这一处 */
    fun pageLabelText(displayPage: Int, pageCount: Int): String = "$displayPage / $pageCount"

    /**
     * 中列页数**实际使用的字号**（sp）：[panelPageLabelSp] 的标称值
     * 先按中列宽度收口（[pageLabelWidthCapSp]），**再夹一条下限 = 格内页码字号**（[previewPageLabelSp]）。
     *
     * 为什么要收口：三等分把页数锁进 1/3 列宽（`ReaderMenuFooter` 的 `weight(1f)`），字号只按比例算时，
     * 窄屏 + 大字体（363dp 机 × fontScale 1.5 × 4 位页码 ≈110dp > 列宽 103.7dp）会把整串截掉，
     * 丢掉「总页数」那一半信息。
     *
     * 为什么要下限（「不截断优先，但层级不许破」）：只按列宽收口会一路压到
     * 9–12sp，跌破 16sp 下限、并压到格内页码之下（`docs/SPEC.md`「阅读菜单与预览条 ·
     * 三档字号层级」的那条链式层级）。
     * 下限取 [previewPageLabelSp] ⇒ **渲染字号恒 ≥ 格内页码字号**，且因 `panelPageLabelSp ≥ previewPageLabelSp`
     * （16 ≥ 16 起）也不会被下限抬到标称值之上。下限也放不下时（窄机 + 4 位页码 + fontScale ≳ 1.57）
     * 才允许截断：`ReaderMenuFooter` 给页数 `maxLines = 1` + `softWrap = false` + `TextOverflow.Ellipsis`。
     *
     * **取舍与依据**：16sp 下限在极端 fontScale 下让位给「不截断」——
     * 下限只保到「不低于格内页码」；要保住 16sp 只能回口径层（给中列留宽或允许换行，两者都动 V1 的三个中心点），
     * 不在本改动面内。已写进 evidence-impl.md 的残余风险。
     *
     * 算例（窄机：内宽 323dp、`"1234 / 5678"` 11 字符、列宽 103.7dp、标称 16.15sp、下限 12sp）：
     * 上限 = `103.7 / (11 × 0.5 × fontScale) = 18.85 / fontScale` ⇒ fontScale ≈ 1.17 起上限开始生效、
     * ≈ 1.18 起低于 16sp、≈ 1.57 起落到下限 12sp（这一档才可能截断）。
     * 常规场合（fontScale 1、3–4 位页码、内宽 ≥ 323dp）上限不生效，字号仍是 16–24sp。
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

    /** 三档字号的唯一一处公式：按比例放大后夹在各自上下限里 */
    private fun scaledSp(value: Float, ratio: Float, minSp: Float, maxSp: Float): Float =
        (value * ratio).coerceIn(minSp, maxSp)

    // ---------- 跳页弹窗 ----------

    /**
     * 跳页弹窗宽度的下限（dp）：**150dp**。
     *
     * 弹窗宽度按屏宽算（[jumpDialogWidthDp]），只有窄屏会落到本值：393dp 的手机竖屏按 1/3 算出 131dp，
     * 比「1234 / 1240」这串页数底文还窄（那串 16sp 下约 84dp，加输入框左右内边距 28dp 与边框 2dp
     * 需要 114dp 的内容区，弹窗内边距再各 16dp ⇒ 146dp），因此给一条下限兜住。
     */
    const val JUMP_DIALOG_MIN_WIDTH_DP: Float = 150f

    /**
     * 跳页弹窗宽度（dp）：**屏宽 1/3，但不低于 [JUMP_DIALOG_MIN_WIDTH_DP]**。
     *
     * 为什么是 1/3：弹窗居中后左右各留 `(屏宽 − 弹窗宽) / 2`，取 1/3 时两侧留白恰好各等于弹窗宽
     * ——大屏上弹窗与两侧留白同宽、不显空；小屏按同一比例会窄到放不下页数底文，由下限接管。
     *
     * 算例：393dp ⇒ 150dp（撞下限）、800dp ⇒ 266.7dp、1280dp ⇒ 426.7dp。
     */
    fun jumpDialogWidthDp(viewportWidthDp: Float): Float =
        (viewportWidthDp / 3f).coerceAtLeast(JUMP_DIALOG_MIN_WIDTH_DP)

    /** 跳页弹窗四边的内边距（dp） */
    const val JUMP_DIALOG_PADDING_DP: Float = 16f

    /** 跳页弹窗圆角（dp） */
    const val JUMP_DIALOG_CORNER_DP: Float = 8f

    /** 跳页弹窗里标题与「确定」的文字字号（sp）：两处同值，因此两处看起来一样大 */
    const val JUMP_DIALOG_TEXT_SP: Float = 16f

    /**
     * 跳页弹窗里「标题行」与「确定行」的**可见**高（dp）：两行同高。
     *
     * 两行等高是「确定」不显得比「跳转到」大的判据：只按文字撑高时，按钮自带的上下内边距
     * 会让它那一段比标题那一段高，看起来像另一个量级。
     */
    const val JUMP_DIALOG_ROW_HEIGHT_DP: Float = 24f

    /**
     * 跳页弹窗内相邻两行之间的间距（dp）：标题 → 输入框、输入框 → 确定行各一份。
     * 与四边内边距（[JUMP_DIALOG_PADDING_DP]）一起定出「标题上方留白 = 确定下方留白」。
     */
    const val JUMP_DIALOG_ROW_GAP_DP: Float = 12f

    /** 跳页弹窗输入框的可见高（dp）：与 Material3 单行输入框的默认高一致 */
    const val JUMP_DIALOG_FIELD_HEIGHT_DP: Float = 56f

    /**
     * 跳页弹窗「确定」的命中带高（dp）：可见高只有 [JUMP_DIALOG_ROW_HEIGHT_DP]（24dp），
     * 命中带向下挂到触摸目标下限 48dp（与底部行「可见 36 / 命中 48」同一套做法）。
     * **只向下挂**：向上溢出会盖住上方输入框的下缘、抢走点输入框的手势。
     */
    const val JUMP_DIALOG_CONFIRM_HIT_HEIGHT_DP: Float = 48f

    /**
     * 跳页弹窗输入框里敲进来的字符 → 只留数字。
     *
     * 非数字**打不进去**（而不是打进去再报错）：用户手滑敲了字母时框里没有变化，
     * 比「字母进去了、确定还是灰的」更容易看懂。过滤只此一处，界面与用例都读它。
     */
    fun jumpDialogSanitizeInput(raw: String): String = raw.filter { it in '0'..'9' }

    /**
     * 跳页弹窗输入框里的文字 → 要跳的**页位（0-based）**；不合法回 `null`。
     *
     * 合法只有一种：`1..pageCount` 的整数。空串、`0`、超出总页数、非数字都回 `null`——
     * 界面据此把「确定」置灰，**不钳制、不报错**（总页数已经写在灰字底文里，用户看得见上限）。
     * 位数过长导致溢出时 [String.toIntOrNull] 回 `null`，同样按不合法处理。
     *
     * 返回 0-based 页位而不是 1-based 页码：输入框里是给人看的页码，`onSeek` 要的是页位，
     * 换算只在本函数一处（阅读菜单内的其它 0/1-based 换算见 [previewPageLabel]）。
     */
    fun jumpDialogPageIndex(input: String, pageCount: Int): Int? {
        if (input.isEmpty() || !input.all { it in '0'..'9' }) return null
        val page = input.toIntOrNull() ?: return null
        return if (page in 1..pageCount) page - 1 else null
    }

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
     * 滑块值（0..末页页位的浮点页位）→ 跳页目标页（0-based，四舍五入后夹取）。
     *
     * 「手势中预览跟随哪一页」与「手势结束跳到哪一页」共用这一条口径；两端超出轨道的值也被夹到首末页。
     */
    fun seekTargetPage(sliderValue: Float, pageCount: Int): Int =
        clampPage(sliderValue.roundToInt(), pageCount)

    /**
     * 行上按下 / 拖动位置的比例（0..1）→ 跳页目标页（0-based，唯一落地口径）。
     *
     * 「点按行上任意位置」与「拖动到行上某处」共用这一个函数：`比例 × 末页页位` 四舍五入后夹取。
     * 因此页数少的书（3 页、值域 0..2）里行宽 0–25% 落到第 1 页、25%–75% 落到第 2 页、75%–100% 落到第 3 页，
     * **每个按下位置都有对应页**，不存在「只有最左/最中/最右有效」的死区；两端越界的比例也夹到首末页。
     *
     * 为什么它必须是**唯一**一条：同行里还叠了一个
     * Material3 `Slider`，它对按下位置另有「扣掉拇指半宽」的换算，生效的是它、不是本函数
     * （200 页书按下 25% 处跳到第 50 页而不是第 51 页）。现在生产的两条路都读本函数：
     * `SeekSlider` 的拖动读 [sliderValueForFraction]，点按读 [SliderGestureState.onTapFraction]，而它也只做
     * `value = sliderValueForFraction(...)` / `emitPage(seekTargetPageForFraction(...))`。
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
     * 调用方（`SeekSlider` 的 `Canvas`）直接把返回值当圆心，不得再加回半径（二次内缩
     * 会让绘制端与手势端在两端差一个半径）。
     */
    fun sliderThumbCenterXPx(fraction: Float, trackWidthPx: Float, thumbDiameterPx: Float): Float {
        val radius = thumbDiameterPx / 2f
        val maxX = (trackWidthPx - radius).coerceAtLeast(radius)
        return (fraction.coerceIn(0f, 1f) * trackWidthPx).coerceIn(radius, maxX)
    }
}

/**
 * 阅读菜单的**档位几何**：一个视口档的「按档取值」一次算齐，供生产与测试只读。
 *
 * ### 为什么收成一处
 * 反复出错的类型都是「同一组档位几何散在 6 处、分档时漏改一处 ⇒ 其它视口被带跑」——
 * 最典型的是把只该给手机竖屏档的滑条行字号改成了全局常量，非手机竖屏各档几何被带跑，
 * 违反「除手机竖屏外其它视口逐像素不变」。这类错**没有任何用例会报警**（只有人拿平板/横屏设备逐档目视）。
 * 收敛后新增或调整任何一档尺寸**只改 [ReaderMenuLayout.resolveTierGeometry]**（六个分支的唯一住处）；
 * 取值只在 [ReaderMenuLayout.resolveTierGeometry] 一处算出后存进本类，`ReaderMenuLayout` 不另设同名入口。
 *
 * ### 字段
 * 六个按档取值：[panelHeightFraction]（+ 由它派生的 [panelBaseHeightDp]）、[rowGapDp]、[titleTopPaddingDp]、
 * [sliderBandHeightDp]、[previewStripMinDp]、[panelBottomPaddingDp]；另加固定行里除标题以外的部分
 * [fixedChromeHeightDp] 与面板高度上限 [panelHeightCapDp]（都只在工厂里算一次）。
 * 两个档位标志 [phonePortrait] / [shortViewport] 也在对象里：`ReaderMenu` 不再自己判一次。
 *
 * 构造入口只有 [ReaderMenuLayout.tierGeometry]（两个档位判据在那里各算一次）；本类不自己算判据。
 * 逐档取值与收敛前**逐值相等**（「行为不变」），由 `ReaderMenuTierGeometryTest` 的档位表逐档钉住
 * （手机竖屏 363×800 / 405×852 · 平板竖屏 · 平板横屏 · 480dp 高横屏 · 600dp 高横屏 · 矮视口 360dp ·
 * 矮视口 fontScale 1.4）。
 */
data class ReaderMenuTierGeometry(
    /** 是否手机竖屏档（[ReaderMenuLayout.isPhonePortrait]）：滑条行压扁与底内边距不扣 inset 只对这一档生效 */
    val phonePortrait: Boolean,
    /** 是否矮视口档（[ReaderMenuLayout.isShortViewport]）：占比 52%、行距与标题上留白压到 0 只对这一档生效 */
    val shortViewport: Boolean,
    /** 面板高度占比（dp/dp）：40%（常规视口）/ 52%（矮视口，公式起点，见 [ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT]） */
    val panelHeightFraction: Float,
    /** 面板**基础高度**（dp）= 视口高 × [panelHeightFraction] */
    val panelBaseHeightDp: Float,
    /** 面板行距（dp）：矮视口压到 0（固定行压扁的一部分），其余视口为 [ReaderMenuLayout.PANEL_ROW_GAP_DP] */
    val rowGapDp: Float,
    /** 标题自己的上侧留白（dp）：矮视口去掉，其余视口为 [ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP] */
    val titleTopPaddingDp: Float,
    /** 跳页滑动条那一行的高度（dp）：手机竖屏 [ReaderMenuLayout.SLIDER_BAND_HEIGHT_PHONE_PORTRAIT_DP]（28dp）/ 其余 [ReaderMenuLayout.SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP]（48dp） */
    val sliderBandHeightDp: Float,
    /** 预览条保底高度（dp，「一屏张数」的档位常量）：手机竖屏 [ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP]（201dp）/ 其余 [ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP]（80dp） */
    val previewStripMinDp: Float,
    /** 面板底部内边距（dp）：手机竖屏 = 滑条行半高 + 行距（不扣 inset）；其余 = 半高 + 行距 − 实际 inset，解出负值回下限 */
    val panelBottomPaddingDp: Float,
    /** 固定行里**除标题以外**的那部分（dp）：底部 inset 消费 + 底内边距 + 标题上留白 + 行距 + 滑条行 + 底部行 */
    val fixedChromeHeightDp: Float,
    /** 面板高度上限（dp）= 视口高 × [ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX]（80%） */
    val panelHeightCapDp: Float,
) {
    /** 面板是否消费底部 inset：手机竖屏档**不消费**，其余档消费（逐像素不变） */
    val consumesBottomInset: Boolean get() = !phonePortrait

    /** 滑条行半高（dp）：底内边距与手机竖屏档「两段各 36dp」都读它 */
    val sliderBandHalfHeightDp: Float get() = sliderBandHeightDp / 2f

    /**
     * 固定行合计（dp）= [fixedChromeHeightDp] + 标题**全部行**的总高。
     * [titleTotalHeightDp] 由调用方按实际行数给出（行数 → 总高的换算只有
     * [ReaderMenuLayout.titleHeightDp] 一处，本类不自己乘一遍）。
     */
    fun fixedRowsHeightDp(titleTotalHeightDp: Float): Float = fixedChromeHeightDp + titleTotalHeightDp

    /**
     * 该档的预览条目标高度（dp）：只由档位决定，标题行数不影响它。
     *
     * ```
     * target = max( [previewStripMinDp]（手机竖屏 201 / 其余 80）,
     *               [panelBaseHeightDp] − 固定行（标题按一行算） )
     * ```
     *
     * 基准取一行标题的余量：取两行的余量会让 1 行标题时的面板掉到 40% 以下（平板竖屏 380.8dp = 37.2%），
     * 与「约 40%」冲突。
     *
     * 分档后：手机竖屏 201.4 / 221.9dp（363dp / 405dp 宽机）；其余档逐像素不变：平板竖屏 253.8dp、
     * 平板横屏 151.4dp、480dp 高横屏 80dp、600dp 高横屏 84.2dp、矮视口 360dp 80dp。
     *
     * [titleLineHeightDp] 是标题一行的高（dp，含 fontScale 换算，见 [ReaderMenuLayout.titleLineHeightDp]）。
     */
    fun previewStripTargetDp(titleLineHeightDp: Float): Float =
        maxOf(previewStripMinDp, panelBaseHeightDp - fixedRowsHeightDp(titleLineHeightDp))

    /**
     * 该档的面板高度（dp）：
     *
     * ```
     * panel = min( max( [panelBaseHeightDp], 固定行(实际行数) + [previewStripTargetDp](一行标题高) ), [panelHeightCapDp] )
     * ```
     *
     * 行数取 [ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES] 夹取后的值，上限是 [panelHeightCapDp]（80% 屏高）。
     * 480/600dp 高横屏高于 40%：恒 40% 时固定行会把预览条压到 0–34dp，由保底项兜到 80dp。
     *
     * 一行标题那一档：
     *
     * | 视口 | 面板（1 行） | 占比 | 预览条（1/2/3 行都是它） |
     * | --- | --- | --- | --- |
     * | 手机竖屏 405×852 | 340.8dp | 40%（= 基础值） | 221.9dp |
     * | 手机竖屏 363×800（算例） | 320dp | 40%（= 基础值） | 201.4dp |
     * | 平板竖屏 768×1024 | 409.6dp | 40%（= 基础值） | 253.8dp |
     * | 平板横屏 1024×768 | 307.2dp | 40%（= 基础值） | 151.4dp |
     * | 480dp 高横屏 852×480 | 235.8dp（1 行）/ 264.6dp（2 行） | 49.1% / 55.1% | 80dp |
     * | 600dp 高横屏 1024×600 | 240dp（1 行）/ 268.8dp（2 行） | 40% / 44.8% | 84.2dp |
     * | 矮视口 360dp 852×360 | 249.6dp（2 行标题） | 69.3% | 80dp |
     */
    fun panelHeightDp(titleLineHeightDp: Float, titleLineCount: Int): Float {
        val fixed = fixedRowsHeightDp(ReaderMenuLayout.titleHeightDp(titleLineHeightDp, titleLineCount))
        return maxOf(panelBaseHeightDp, fixed + previewStripTargetDp(titleLineHeightDp)).coerceAtMost(panelHeightCapDp)
    }

    /**
     * 预览条的**几何模型值**（dp）= [panelHeightDp] − [fixedRowsHeightDp]。
     * 生产里预览条是 `weight(1f)`（本值不参与布局）；消费者是档位表用例的「预览条高度」列与张数算例。
     */
    fun previewStripHeightDp(titleLineHeightDp: Float, titleLineCount: Int): Float =
        panelHeightDp(titleLineHeightDp, titleLineCount) -
            fixedRowsHeightDp(ReaderMenuLayout.titleHeightDp(titleLineHeightDp, titleLineCount))
}
