package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 阅读菜单的布局口径（纯函数，由 [ReaderMenuLayoutTest] 锁定）。
 *
 * 票 #105 把 #42 / #62 / #65 / #66 / #67 五张票的口径一次重定（改的是同一处布局、互相牵制）：
 *
 * ### 面板高度与预览条保底（批次 6 AC11 + 裁定 A + 第 6 轮真机反馈）
 * **所有视口**都走同一条公式：`min(max(base, 固定行 + 预览条保底), 视口高 × 80%)`，
 * `base` = 40%（常规视口）/ 52%（矮视口：可用高 < [SHORT_VIEWPORT_MAX_HEIGHT_DP]）、
 * 预览条保底 = [PREVIEW_STRIP_MIN_DP]（常规 200dp）/ [PREVIEW_STRIP_MIN_SHORT_VIEWPORT_DP]（矮视口 80dp）。
 * **固定行按标题的实际行数预算**（第 6 轮：1–3 行，[READER_MENU_TITLE_MAX_LINES]；不截断、不省略号）。实测：
 * 360dp 视口 ⇒ 固定行 169.6dp、面板 249.6dp（69.3%）、**预览条 80dp**；fontScale 1.4 ⇒ 面板 272.6dp（75.7%）、预览条仍 80dp；
 * 600dp 横屏 ⇒ 固定行 208.6dp、面板 288.6dp（48.1%）、预览条 80dp（此前走「恒 40%」时预览条只有 31.4dp）。
 * 手机竖屏（852dp）⇒ 40% = 340.8dp 小于「固定行 + 200dp」，**保底项生效**：面板 ≈ 373dp（43.8%）、预览条 **200dp**、一屏约 2.9 张。
 * 平板竖屏（1024dp）⇒ 40% = 409.6dp 仍大于保底需求 ⇒ 面板仍是 40%、逐像素不变。
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
 * **一屏张数**（AC1 的实测值，算例见 `ReaderMenuLayoutTest`）：**手机竖屏约 2.9 张**（第 6 轮：预览条保底由 80dp
 * 抬到 [PREVIEW_STRIP_MIN_DP] 后，图片比上一轮大一圈）、平板竖屏约 5.0 张（平板面板仍由 40% 兜底，未变）。
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
     * [panelHeightDp] 继续抬高（维护者 2026-09-21 裁决：**优先保预览条**，52% 作为基线）。
     * 在这个矮视口区间（H < [SHORT_VIEWPORT_MAX_HEIGHT_DP]）里它**永远不会成为约束**：
     * 保底需求（两行标题下 249.6dp）已大于 0.52 × 480dp，所以它只是一条形式上的下限（测试用合成输入钉住它确实是 52%）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT: Float = 0.52f

    /**
     * 矮视口面板高度比例的上限（票 #105 批次 6；裁定 A 后由 66% 上调到 80%）：80%。
     *
     * 用途：保底项（`固定行 + [SHORT_VIEWPORT_MIN_PREVIEW_DP]`）不能无限抬高面板——极矮视口（如 240dp）
     * 下会把整屏都吃掉、标题也被挤出去。上限从 66% 改成 80% 的理由：66% 在 360dp 视口下会先于保底项生效
     * （237.6dp < 249.6dp），预览条只剩 68dp；维护者 2026-09-21 裁定「**保 80dp 优先于压低占比**」，
     * 于是上限放宽到 80%（360dp 视口下 249.6dp = 69.3%，预览条足 80dp）。
     */
    const val PANEL_HEIGHT_FRACTION_SHORT_MAX: Float = 0.8f

    /**
     * 常规视口（竖屏手机、平板）预览条保底高度（dp，票 #105 第 6 轮真机反馈第 ① 条）：200dp。
     *
     * 维护者真机反馈「预览图还是不够大，现在手机是固定 4 张，我不是说要学 PV 滑动显示吗」，
     * 并给了两条口径：**手机竖屏预览条 ≥ 112dp**、**一屏约 2.5–3 张**。取 200dp 的推导（手机竖屏、
     * 面板内宽 365dp、常见 2:3 页）：一屏 3 张 ⇒ 单格占宽 = (365 − 2×6) / 3 ≈ 117.7dp ⇒ 图片高 = 117.7 ÷ (2/3) ≈ 176.5dp，
     * 再加页数那一行（12.8sp × 1.2 = 15.3dp）⇒ 预览条 ≈ 192dp；再留一点余量取整档 **200dp**
     * （实测一屏 2.87 张，稳稳落在 2.5–3.0 里；192dp 会贴到 3.0 边界）。
     * 上限再高就会把**平板竖屏**也顶离它的 40%（平板竖屏固定行 208.6dp，40% = 409.6dp ⇒ 保底最多 201dp）。
     *
     * 80dp 是上一轮「矮视口保底」那个值，本轮**不再用于常规视口**（它给出的一屏张数是 4 张左右，
     * 正是维护者说的「固定 4 张」）；矮视口仍用 [PREVIEW_STRIP_MIN_SHORT_VIEWPORT_DP]。
     */
    const val PREVIEW_STRIP_MIN_DP: Float = 200f

    /**
     * 矮视口（横屏手机）预览条保底高度（dp，票 #105 批次 6 AC11 + 裁定 A）：80dp。
     *
     * 矮视口上维护者只提过「预览条别再是 16dp 细缝」（批次 6 的 88/80dp 口径），没有提「大图」；
     * 因此这里**维持上一轮的值不动**：360dp 视口 ⇒ 面板 249.6dp（69.3%）、预览条 80dp；
     * fontScale 1.4 ⇒ 面板 272.6dp（75.7%）、预览条仍 80dp；fontScale 1.5 ⇒ 77.3%，仍 80dp。
     * 只有把上限顶满时（矮视口下 fontScale ≳ 1.65）预览条才会低于 80dp。
     */
    const val PREVIEW_STRIP_MIN_SHORT_VIEWPORT_DP: Float = 80f

    /** 预览条保底高度（dp）：矮视口取 [PREVIEW_STRIP_MIN_SHORT_VIEWPORT_DP]，其余视口取 [PREVIEW_STRIP_MIN_DP] */
    fun previewStripMinDp(shortViewport: Boolean): Float =
        if (shortViewport) PREVIEW_STRIP_MIN_SHORT_VIEWPORT_DP else PREVIEW_STRIP_MIN_DP

    /** 矮视口底部行高度（dp，票 #105 批次 6 AC11「固定行已压扁」：48 → 36） */
    const val PANEL_FOOTER_HEIGHT_SHORT_DP: Float = 36f

    /** 面板内共几行（标题 / 预览条 / 进度条 / 底部行）：行距个数 = 行数 − 1，票 #105 批次 6 AC13 */
    private const val PANEL_ROW_COUNT = 4


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
     * 固定行合计高度（dp）：标题行（按实际行数） + 进度条行 + 底部行（上/下一本 + 页数同一行）
     * + 行距 + 面板底部内边距 + 面板必然扣掉的底部 inset。
     *
     * 这是「面板高度 − 预览条高度」的**唯一一处**口径（见 [previewStripHeightDp] 的事实说明）。
     * 测试里的保底算例（预览条 = [PREVIEW_STRIP_MIN_DP] / [PREVIEW_STRIP_MIN_SHORT_VIEWPORT_DP]）也读它。
     *
     * [titleHeightDp] 是**标题全部行的总高**（dp），由调用方传入——两个因素都必须由调用方算进去，
     * 否则会把固定行算小：
     * ① **fontScale**：`sp` 随系统字体缩放放大，把 sp 数值当 dp 用会少算（[titleLineHeightDp] 含换算）；
     * ② **行数**：长书名会换行（第 6 轮口径：1–3 行、不截断、不省略号），调用方按**实际行数**预算
     *   （`ReaderMenu` 传的是 `titleLineHeightDp(...) × 标题实测行数`，上限见 [READER_MENU_TITLE_MAX_LINES]）。
     * [bottomInsetDp] 由调用方从 `readerPanelInsets()` 取（沉浸态由 [ReaderOverlayLayout.MIN_BOTTOM_DP] 兜底为 24dp）。
     */
    fun fixedRowsHeightDp(shortViewport: Boolean, titleHeightDp: Float, bottomInsetDp: Float): Float =
        bottomInsetDp + PANEL_BOTTOM_PADDING_DP + panelTitleTopPaddingDp(shortViewport) + titleHeightDp +
            panelRowGapDp(shortViewport) * (PANEL_ROW_COUNT - 1) + SLIDER_BAND_HEIGHT_DP +
            panelFooterHeightDp(shortViewport)

    /**
     * 面板高度（dp，票 #105 AC4 + 批次 6 AC11；**保底公式对所有视口生效**）：
     *
     * ```
     * panel = min( max(base, 固定行 + SHORT_VIEWPORT_MIN_PREVIEW_DP), 视口高 × PANEL_HEIGHT_FRACTION_SHORT_MAX )
     * base  = 视口高 × PANEL_HEIGHT_FRACTION（40%，常规视口）/ × PANEL_HEIGHT_FRACTION_SHORT（52%，矮视口）
     * ```
     *
     * 为什么保底项不再只对矮视口生效（票 #105 裁定 A，第 5 轮推广）：**480–700dp 高的横屏设备**
     * （1024×600 平板、480–520dp 档）此前走「恒 40%」那一支，四条固定行本身就把预览条压到 0–34dp
     * （480–520dp 下算式为负、布局里塌成 0）。现在所有视口都至少把预览条抬到 80dp，
     * 上限继续兜底（极矮视口不让面板吃掉整屏）。
     *
     * 竖屏与常规平板**逐像素不变**：那一档 40% 已大于「固定行 + 80dp」，`max` 取到的仍是 40%
     * （手机竖屏 340.8 > 274.8；平板竖屏 409.6 > 288.6；横屏平板 307.2 > 288.6），断言见 `ReaderMenuLayoutTest`。
     */
    fun panelHeightDp(viewportHeightDp: Float, titleHeightDp: Float, bottomInsetDp: Float): Float {
        val base = if (isShortViewport(viewportHeightDp)) {
            viewportHeightDp * PANEL_HEIGHT_FRACTION_SHORT
        } else {
            viewportHeightDp * PANEL_HEIGHT_FRACTION
        }
        val needed = fixedRowsHeightDp(isShortViewport(viewportHeightDp), titleHeightDp, bottomInsetDp) +
            previewStripMinDp(isShortViewport(viewportHeightDp))
        val cap = viewportHeightDp * PANEL_HEIGHT_FRACTION_SHORT_MAX
        return maxOf(base, needed).coerceAtMost(cap)
    }

    /**
     * 预览条的**几何模型值**（dp）= [panelHeightDp] − [fixedRowsHeightDp]。
     *
     * 事实说明（票 #105 标准轴 P2）：生产里预览条是 `weight(1f)`，它的高度由 Compose 在布局时分配
     * （结果等于本函数的值，但生产代码**不读**本函数）——本函数的真实消费者是
     * `ReaderMenuLayoutTest` 的张数算例与 AC11 保底算例。保留它是因为那两条口径必须与
     * [panelHeightDp]/[fixedRowsHeightDp] 同源，不能在两处各写一份减法。
     */
    fun previewStripHeightDp(viewportHeightDp: Float, titleHeightDp: Float, bottomInsetDp: Float): Float =
        panelHeightDp(viewportHeightDp, titleHeightDp, bottomInsetDp) -
            fixedRowsHeightDp(isShortViewport(viewportHeightDp), titleHeightDp, bottomInsetDp)

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
     * 旧的「遮挡 ≤ 25%」预算随之作废），滑条整宽可点。
     *
     * 行内**不画任何底色/渐变**（第 6 轮真机反馈第 ③ 条：「预览进度条有个更深的背景色，和阅览菜单的
     * 背景色不一样，直接删掉」）：只有 [SLIDER_TRACK_HEIGHT_DP] 的细线与 [SLIDER_THUMB_DIAMETER_DP] 的圆球。
     * 也不需要 Material3 的 `Slider` 了（第 6 轮真机反馈第 ④ 条要的就是 PV 那种自绘样式，而且真机第 ⑦ 条
     * 「3 页书点进度条任意位置都能跳页」第三次没修好的**真因**就是两条通路（M3 自己的按压换算 + 自接手势）
     * 并行——见 [SLIDER_BAND_GESTURE_OWNER]），行高 48dp 由本常量自己守住，不再受 M3 的 44dp 下限牵制。
     */
    const val SLIDER_BAND_HEIGHT_DP: Float = 48f

    /**
     * 滑动条的**手势唯一归属**（第 6 轮 AC9 返工的因果说明，口径只有这一处）：
     * `SeekSlider` 自己的 `pointerInput` 接管整行（按下 / 拖动 / 抬手都是一条路），**不再**在同行里叠一个
     * Material3 `Slider`。上一轮是两条并行：M3 对按下位置做「扣掉拇指半宽」的换算，自接手势另算「行上比例」。
     * 实测（`SeekSliderTapTest` + 一轮诊断用例）生效的是 M3 那条：200 页书按下行宽 25% 处跳到第 50 页
     * （线性口径应为第 51 页），3 页书按下行中点**什么都没有发出**（应为第 2 页）；如果两条都生效，
     * 每次点按会发出两次不同页的 `onSeek`。删掉 M3 那条后「行上比例 → 页」只剩一个函数
     * （[ReaderMenuLayout.seekTargetPageForFraction]）。
     */
    const val SLIDER_BAND_GESTURE_OWNER: String = "SeekSlider 自身的 pointerInput（唯一一条手势通路）"

    /** 跳页滑动条的轨道线高（dp，第 6 轮真机反馈第 ④ 条：学 PV 做成「一条线 + 一个圆球」） */
    const val SLIDER_TRACK_HEIGHT_DP: Float = 2f

    /** 跳页滑动条的圆球直径（dp，第 6 轮真机反馈第 ④ 条）：8dp */
    const val SLIDER_THUMB_DIAMETER_DP: Float = 8f

    /** 未划过的那段轨道颜色（已划过的那段用仓库既有强调色 `ui/AccentColor.ACCENT_ORANGE`） */
    const val SLIDER_TRACK_REMAINDER_COLOR: Long = 0x66FFFFFF

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
     * （200 页书按下 25% 处实测跳到第 50 页而不是第 51 页）——见 [SLIDER_BAND_GESTURE_OWNER]。
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
     * 手势不读它——手势看的是「按下位置 / 行宽」这条比例（见 [seekTargetPageForFraction]）。
     */
    fun sliderThumbCenterXPx(fraction: Float, trackWidthPx: Float, thumbDiameterPx: Float): Float {
        val radius = thumbDiameterPx / 2f
        val maxX = (trackWidthPx - radius).coerceAtLeast(radius)
        return (fraction.coerceIn(0f, 1f) * trackWidthPx).coerceIn(radius, maxX)
    }
}
