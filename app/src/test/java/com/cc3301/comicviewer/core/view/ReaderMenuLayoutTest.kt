package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * 阅读菜单的布局口径（票 #105 一次重定 #42/#62/#65/#66/#67）。
 *
 * 覆盖：
 * - 面板高度占比（AC4）与「三种视口下预览区都吃得到高度」（AC5：底部行不被挤出面板）；
 * - 预览项尺寸（AC1/AC3）：高度撑满预览条减页数那一行、宽度 = 高度 × 该页真实比例、一屏几格由屏幕宽度决定
 *   （**第 13/14 轮分档后实测：手机竖屏 2.52 张（363dp 宽机）/ 2.58 张（405dp 宽机）、平板竖屏 4.52 张**——票面的
 *   2.5 / 3.5 是「滑动条叠放 + 页数叠在图上」那个已被 D2-A/D3-A 推翻的几何下的 PV 参考值，
 *   见 evidence-impl.md 与 `ReaderMenuLayout` 头部说明）；
 * - 四行结构（批次 6 AC13）：进度条**独占一行**、不在预览条里（遮挡恒 0，旧「遮挡 ≤25%」预算作废）；
 * - 预览条保底**按视口分档**（第 7 轮 spec P1 + 第 13/14 轮）：**只有手机竖屏**取 201dp（第 13 轮 AC19：224 → 201，
 *   实际取到的是「基础 40% 扣掉固定行后的余量」221.9 / 201.4dp，仍满足补记 7 ① 的 ≥112dp），
 *   其余视口一律 80dp、占比仍走 AC4（平板竖屏/横屏 40%、480dp 高横屏 49.1%、600dp 高横屏 40%）；
 * - **几何按档**（第 14 轮，票面 AC19「除手机竖屏外其它视口逐像素不变」）：滑条行只对手机竖屏压到 28dp、
 *   底部内边距只对手机竖屏不扣 inset（见 [ReaderMenuLayout.sliderBandHeightDp] / [ReaderMenuLayout.panelBottomPaddingDp]），
 *   其余档保留改动前口径；
 * - 矮视口（批次 6 AC11 + 裁定 A）：面板按需加高（52% 公式起点、**80dp 预览条保底**、80% 屏高上限），
 *   标题 1–3 行（第 6 轮：短书名 1 行、超长最多 3 行、不省略号），固定行按**实测行数**预算；
 * - 三档字号（AC6）：票面表的 `内宽 × 0.05 / 0.05 / 0.035` 与 18–24 / 16–24 / 12–16sp 上下限，
 *   不变量 **标题 ≥ 页码 ≥ 格内页码**（正常档页码严格大于格内页码；极端 fontScale 取等，见 `渲染页码字号恒不低于格内页码`）；
 * - 页位口径：滑块值 → 最近页（AC9 的纯函数侧；自接点按手势的比例→页与幂等规则在 `ui/SliderGestureStateTest`）、
 *   页位夹取、格内页码换算。
 *
 * 真机目视与实测截图（AC1/AC4/AC5 的目视、AC3 的「不留白/水平居中」观感、AC11 的面板真实占比）
 * 不在 JVM 里测，由 `ReaderMenuFooterTest`（底部行几何）与真机验收覆盖。
 */
class ReaderMenuLayoutTest {

    // ---------- 面板高度与预览区（AC4/AC5）----------

    /** 一屏能放几格：预览条宽度 ÷ 单格宽度（含间隙） */
    private fun visibleItems(innerWidthDp: Float, imageHeightDp: Float, aspect: Float): Float {
        val item = ReaderMenuLayout.previewItemWidth(imageHeightDp, aspect)
        return (innerWidthDp + ReaderMenuLayout.PREVIEW_GAP_DP) / (item + ReaderMenuLayout.PREVIEW_GAP_DP)
    }

    /**
     * 标题一行的 dp 高（票 #105 标准轴 P2-5）。`sp ≠ dp`：换算在 [ReaderMenuLayout.titleLineHeightDp] 里，
     * [fontScale] 显式传入（1 = 常规字体）。标题的行数上限见 [ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES]
     * （第 6 轮：1–3 行、不省略号），行数由 `ReaderMenu` 从 `onTextLayout` 实测后回传。
     */
    private fun titleLine(innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.titleLineHeightDp(innerWidthDp, fontScale)

    /**
     * 预览条高度（dp）= 面板高度 − 固定行合计（生产口径：[ReaderMenuLayout.previewStripHeightDp]）。
     *
     * 固定行含四项（票 #105 批次 6 AC13 的四行结构）：标题行、**进度条行**（改前它叠在预览条上、不占行）、
     * 底部行、行距与内边距；底部 inset 那一项不能漏：面板整块消费 `readerPanelInsets()`，
     * 而阅读器是沉浸态，底部由 [ReaderOverlayLayout.MIN_BOTTOM_DP]（24dp）兜底。
     */
    private fun previewStripHeight(
        viewportHeightDp: Float,
        innerWidthDp: Float,
        fontScale: Float = 1f,
        lineCount: Int = 1,
    ): Float =
        ReaderMenuLayout.previewStripHeightDp(
            viewportWidth(innerWidthDp),
            viewportHeightDp,
            titleLine(innerWidthDp, fontScale),
            lineCount,
            ReaderOverlayLayout.MIN_BOTTOM_DP,
        )

    /**
     * 视口宽（dp）= 面板内宽 + 两侧内边距（第 7 轮的预览条保底按视口宽分档，见
     * [ReaderMenuLayout.isPhonePortrait]）。测试里各档的「内宽」都来自真机（365/728/984/812），
     * 加回 2 × 20dp 就是该设备真实的屏宽。
     */
    private fun viewportWidth(innerWidthDp: Float): Float =
        innerWidthDp + ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP * 2

    /**
     * 面板高度（dp）：走生产口径 [ReaderMenuLayout.panelHeightDp]。
     * [lineCount] 是标题的**实测行数**（1–3，票面第 4/⑤ 条）；矮视口 AC11 的算例按**两行**建模。
     */
    private fun panelHeight(
        viewportHeightDp: Float,
        innerWidthDp: Float,
        lineCount: Int = 1,
        fontScale: Float = 1f,
    ): Float =
        ReaderMenuLayout.panelHeightDp(
            viewportWidth(innerWidthDp),
            viewportHeightDp,
            titleLine(innerWidthDp, fontScale),
            lineCount,
            ReaderOverlayLayout.MIN_BOTTOM_DP,
        )

    /** 矮视口预览条高度（dp）：固定行按**两行**标题建模（矮视口恒走 80dp 档） */
    private fun shortViewportStripHeight(viewportHeightDp: Float, innerWidthDp: Float, fontScale: Float = 1f): Float =
        previewStripHeight(viewportHeightDp, innerWidthDp, fontScale, lineCount = 2)

    /**
     * 页数那一行的 dp 高（票 #105 批次 6 AC14）：字号 sp × 行高比例 × fontScale——
     * 走生产的同一条换算（`Density.toDp()`），因此 fontScale ≠ 1 时两个数字能对上。
     */
    private fun labelHeight(innerWidthDp: Float, fontScale: Float = 1f): Float = with(Density(1f, fontScale)) {
        ReaderMenuLayout.previewLabelHeightSp(ReaderMenuLayout.previewPageLabelSp(innerWidthDp)).sp.toDp().value
    }

    /**
     * 缩略图（图片本体）高度（dp）：预览条高再扣掉页数那一行（票 #105 批次 6 AC14）。
     * 一屏张数由它决定（张数 = 预览条宽度 ÷ 格子宽度，而格子宽度 = 图片高 × 页面比例）。
     */
    private fun imageHeight(viewportHeightDp: Float, innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.previewImageHeightDp(
            previewStripHeight(viewportHeightDp, innerWidthDp, fontScale),
            labelHeight(innerWidthDp, fontScale),
            innerWidthDp,
            ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT,
        )

    /** 票面的三种视口：手机竖屏、平板竖屏、平板横屏（高、面板内宽） */
    private val viewports = listOf(
        "手机竖屏" to (852f to 365f),
        "平板竖屏" to (1024f to 728f),
        "平板横屏" to (768f to 984f),
    )

    @Test
    fun `面板高度是视口高度的四成`() {
        assertEquals(0.4f, ReaderMenuLayout.PANEL_HEIGHT_FRACTION, 0.0001f)
        for ((label, viewport) in viewports) {
            val (height, _) = viewport
            val panel = height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION
            assertEquals("$label：面板应占视口 40%", height * 0.4f, panel, 0.01f)
        }
        // 改动前是「不超过视口 60%」且整体可滚动：本票必须真的统一到 40%
        assertTrue("必须比改动前的 60% 上限小", ReaderMenuLayout.PANEL_HEIGHT_FRACTION < 0.6f)
    }

    @Test
    fun `三种视口下预览区都吃得到高度 底部行不被挤出面板`() {
        for ((label, viewport) in viewports) {
            val (height, inner) = viewport
            val strip = previewStripHeight(height, inner)
            assertTrue("$label：预览条高度 ${strip}dp 必须为正（否则底部行被挤出/面板要滚动，AC5）", strip > 0f)
        }
    }

    /**
     * 手机竖屏一屏张数（第 7 轮 spec P1 的判据 ①）：一屏约 2.5–3 张（不再是「固定 4 张」）、
     * 预览条 ≥ 112dp、且**面板占比仍在「约 40%」档**（这一档的预览条保底抬到
     * [ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP]，但只影响手机竖屏）。
     */
    @Test
    fun `手机竖屏一屏两到三张 预览条不小于 112dp 且面板约四成`() {
        val (height, inner) = viewports[0].second
        val visible = visibleItems(inner, imageHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("手机一屏 $visible 张，必须落在 2.5–3.0（第 6 轮真机口径）", visible >= 2.5f && visible <= 3.0f)
        val strip = previewStripHeight(height, inner)
        assertTrue("手机竖屏预览条 ${strip}dp 必须 ≥ 112dp（第 6 轮真机反馈第 ① 条）", strip >= 112f)
        // 第 14 轮分档后手机竖屏的实际预览条 = 「基础 40% 扣掉固定行后的余量」（221.9dp，> 保底 201dp）：
        // 等值断言钉的是该档目标高度（同源），下限断言钉的是档位保底本身
        assertEquals(
            "手机竖屏预览条必须等于该档目标高度（基础占比扣掉固定行后的余量）",
            ReaderMenuLayout.previewStripTargetDp(
                viewportWidth(inner),
                height,
                titleLine(inner),
                ReaderOverlayLayout.MIN_BOTTOM_DP,
            ),
            strip,
            0.01f,
        )
        assertTrue(
            "手机竖屏预览条 ${strip}dp 必须 ≥ 档位保底 ${ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP}dp（第 13 轮 AC19）",
            strip >= ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP - 0.01f,
        )
        val panel = panelHeight(height, inner)
        val ratio = panel / height
        assertTrue("手机竖屏面板占比 $ratio 必须仍在「约 40%」档（0.40–0.45，AC4）", ratio in 0.40f..0.45f)
    }

    /**
     * 平板竖屏一屏张数：AC1 原值 3.5，几何修正后为 5.0（容差 0.3 未放宽），理由同手机竖屏那条。
     * **第 9 轮（补记 8 的 A 档）后又变成 4.52**：底部行 48→36dp、行距 8→4dp 把面板基础占比的余量放大，
     * 平板竖屏预览条 229.8 → **253.8dp**，图片变大、一屏从 5.01 降到 4.52 张（面板仍是 40%）。
     * 票面 AC1 的数字（平板 5.0）因此又一次过期，待编排者同步。
     */
    @Test
    fun `平板竖屏一屏约 4_5 张`() {
        val (height, inner) = viewports[1].second
        val visible = visibleItems(inner, imageHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("平板一屏 $visible 张，必须落在 4.5 ± 0.3（A 档压缩后的实测值）", kotlin.math.abs(visible - 4.5f) <= 0.3f)
    }

    /**
     * 第 13 轮（AC19 + 裁决 C）的算例：363 × 800dp、内宽 323dp、2:3 页 —— 面板 320dp（40%）、预览条 201.4dp、
     * 缩略图 ≈124.7 × 187.0dp、一屏 ≈2.52 张（AC19 目标是 201 / 124×186 / 2.54，差在整数除法与「页数那一行
     * 按 sp 换算」两处零头）。它是维护者拍 AC19 时用的那台机器。
     *
     * 与 [viewports] 里那台 405dp 宽机只差内宽：一屏张数**随屏宽变**（这台更窄 ⇒ 2.52 张、405dp 机 2.58 张），
     * 两者都在维护者「2.5–2.8 可接受」区间内（见 `手机竖屏一屏两到三张`）。
     */
    @Test
    fun `票面算例 363 乘 800 内宽 323 面板四成 预览条 201dp`() {
        val height = 800f
        val inner = 323f
        assertEquals("面板 = 40% × 屏高（AC19 的面板 320dp）", 320f, panelHeight(height, inner), 0.05f)
        assertEquals("面板占比 = 40%（裁决 C 后手机竖屏不再被抬到 43.8%）", 0.40f, panelHeight(height, inner) / height, 0.001f)
        assertEquals("预览条 = 201.4dp（AC19 的 201dp）", 201.4f, previewStripHeight(height, inner), 0.05f)
        val image = imageHeight(height, inner)
        assertEquals("缩略图高 ≈187dp（AC19 的 186dp）", 187.0f, image, 0.05f)
        assertEquals(
            "缩略图宽 ≈124.7dp = 高 × 2/3（AC19 的 124dp）",
            124.7f,
            ReaderMenuLayout.previewItemWidth(image, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT),
            0.05f,
        )
        val visible = visibleItems(inner, image, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("一屏 $visible 张必须 ≈ AC19 的 2.54 张（维护者可接受区间 2.5–2.8）", kotlin.math.abs(visible - 2.52f) <= 0.05f)
    }

    /**
     * 第 6 轮第 ⑤ 条：**标题行数只让面板变高，不影响预览条**（「动态加长菜单，不要影响到预览图区域」）。
     *
     * 手机竖屏上预览条恒由保底项撑住（保底需求 372.9–415.8dp > 40% × 852 = 340.8dp），所以 1 行、2 行、3 行
     * 三种标题下预览条都是 [ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP]、一屏张数都一样；面板则逐行变高。
     */
    @Test
    fun `标题变多行只抬高面板不改预览条高度`() {
        val (height, inner) = viewports[0].second
        val stripForLines = (1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES).map { lines ->
            previewStripHeight(height, inner, lineCount = lines)
        }
        val phoneTarget = ReaderMenuLayout.previewStripTargetDp(
            viewportWidth(inner),
            height,
            titleLine(inner),
            ReaderOverlayLayout.MIN_BOTTOM_DP,
        )
        assertTrue(
            "各行数下预览条 $stripForLines 必须全都等于手机竖屏档目标高度 ${phoneTarget}dp（标题变长不吃预览图）",
            stripForLines.all { kotlin.math.abs(it - phoneTarget) <= 0.01f },
        )
        val panels = (1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES).map { lines ->
            panelHeight(height, inner, lineCount = lines)
        }
        assertTrue("标题每多一行，面板必须跟着变高：$panels", panels[0] < panels[1] && panels[1] < panels[2])
    }

    @Test
    fun `标题行数夹在 1 到 3 行`() {
        // 第 6 轮第 ⑤ 条：短标题 1 行、超长最多 3 行（不省略号），超过上限的实测行数一律夹回 3
        assertEquals(10f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 0), 0.01f)
        assertEquals(10f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 1), 0.01f)
        assertEquals(30f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 3), 0.01f)
        assertEquals("超过 3 行一律夹回 3 行（不省略号，只是不再加高面板）", 30f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 9), 0.01f)
    }

    @Test
    fun `滑动条是 2dp 细线加 8dp 圆球 滑条行按档取高`() {
        // 第 6 轮真机反馈第 ④ 条：学 PV 做成「一条线 + 一个圆球」，命中行仍要 48dp（非手机竖屏档，改动前口径）
        assertEquals(2f, ReaderMenuLayout.SLIDER_TRACK_HEIGHT_DP, 0.01f)
        assertEquals(8f, ReaderMenuLayout.SLIDER_THUMB_DIAMETER_DP, 0.01f)
        assertEquals("非手机竖屏档（平板/矮视口）保持 48dp，逐像素不变", 48f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP, 0.01f)
        assertEquals("第 13 轮 AC19：手机竖屏档压到 28dp", 28f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_PHONE_PORTRAIT_DP, 0.01f)
        assertEquals("档位取值：手机竖屏 → 28dp", 28f, ReaderMenuLayout.sliderBandHeightDp(true), 0.01f)
        assertEquals("档位取值：其余视口 → 48dp", 48f, ReaderMenuLayout.sliderBandHeightDp(false), 0.01f)
        assertTrue("圆球必须比线粗（否则看不出来）", ReaderMenuLayout.SLIDER_THUMB_DIAMETER_DP > ReaderMenuLayout.SLIDER_TRACK_HEIGHT_DP)
    }

    @Test
    fun `圆球位置两端夹在半径内 中间线性`() {
        // 绘制口径：比例 0 / 1 分别停在轨道两端（圆球不出轨道），中间线性
        assertEquals(4f, ReaderMenuLayout.sliderThumbCenterXPx(0f, 100f, 8f), 0.01f)
        assertEquals(50f, ReaderMenuLayout.sliderThumbCenterXPx(0.5f, 100f, 8f), 0.01f)
        assertEquals(96f, ReaderMenuLayout.sliderThumbCenterXPx(1f, 100f, 8f), 0.01f)
        assertEquals("越界比例也夹在两端", 4f, ReaderMenuLayout.sliderThumbCenterXPx(-3f, 100f, 8f), 0.01f)
        assertEquals(96f, ReaderMenuLayout.sliderThumbCenterXPx(9f, 100f, 8f), 0.01f)
    }

    @Test
    fun `行上比例到页的映射三段无死区`() {
        // 第 6 轮第 ⑦ 条（AC9）：3 页书（值域 0..2）里 0–25% / 25–75% / 75–100% 分别落到三页，
        // 每个按下位置都有对应页——「只有最左/最中/最右有效」的写法在这条断言下必红
        for ((fraction, page) in listOf(
            0.05f to 0,
            0.24f to 0,
            0.26f to 1,
            0.5f to 1,
            0.74f to 1,
            0.76f to 2,
            0.95f to 2,
        )) {
            assertEquals("行宽 ${fraction * 100}% 处应落到页位 $page", page, ReaderMenuLayout.seekTargetPageForFraction(fraction, 3))
        }
        assertEquals("越界比例夹到首末页", 0, ReaderMenuLayout.seekTargetPageForFraction(-0.5f, 3))
        assertEquals(2, ReaderMenuLayout.seekTargetPageForFraction(1.5f, 3))
        assertEquals("空书恒第 0 页", 0, ReaderMenuLayout.seekTargetPageForFraction(0.9f, 0))
        // 比例 → 值 与 值 → 比例 必须互为逆（拖动时拇指与预览用的是同一映射）
        assertEquals(100f, ReaderMenuLayout.sliderValueForFraction(0.5f, 201), 0.01f)
        assertEquals(0.5f, ReaderMenuLayout.sliderFraction(100f, 201), 0.001f)
        assertEquals("单页书比例恒 0（分母为 0 不炸）", 0f, ReaderMenuLayout.sliderFraction(3f, 1), 0.001f)
    }

    @Test
    fun `两行书名下平板竖屏一屏仍约 4_5 张`() {
        // 票面第 4 条（第 8 轮）：标题行数**不改**预览条，因此两行书名下的一屏张数与一行完全相同
        // （r7 时它是 5.77 张——那是「行数吃掉预览条」的旧口径，第 8 轮已按票面改掉）；
        // 第 9 轮的 A 档压缩把平板竖屏预览条抬到 253.8dp，数值随之落到 4.52（容差 0.3 未放宽）
        val (height, inner) = viewports[1].second
        fun visibleAt(lines: Int): Float {
            val strip = previewStripHeight(height, inner, lineCount = lines)
            val image = ReaderMenuLayout.previewImageHeightDp(
                strip,
                labelHeight(inner),
                inner,
                ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT,
            )
            return visibleItems(inner, image, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        }
        val oneLine = visibleAt(1)
        val twoLines = visibleAt(2)
        assertEquals("两行书名下的一屏张数必须与一行相同（预览条不因行数变）", oneLine, twoLines, 0.001f)
        assertTrue("平板竖屏一屏 $twoLines 张，必须落在 4.5 ± 0.3（A 档压缩后的实测值）", kotlin.math.abs(twoLines - 4.5f) <= 0.3f)
    }

    @Test
    fun `张数不写死 屏幕越宽一屏越多`() {
        val counts = viewports.map { (_, viewport) ->
            val (height, inner) = viewport
            visibleItems(inner, imageHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        }
        assertTrue("平板竖屏（${counts[1]}）必须比手机竖屏（${counts[0]}）多", counts[1] > counts[0])
        assertTrue("平板横屏（${counts[2]}）必须比平板竖屏（${counts[1]}）多", counts[2] > counts[1])
    }

    /**
     * 票 #105 AC17（第 13 轮）「当前页缩略图始终居中」的**偏移口径**：
     * ① 一格比视口窄时偏移 = `−(视口宽 − 条目宽)/2`，且**必须是负的**——正偏移是「往前滚」那一侧，
     *    会把当前页往视口左外推（符号反了这个函数仍然「有输出」，所以专门断言符号）；
     * ② 居中后条目两侧留白相等（这就是「落在正中」的可算形式，整数除法只允许 1px 误差）；
     * ③ 条目不比视口窄时回 0（没有可居中的空间）。
     * 两端（首页/末页贴边）不在这里判：它是 `LazyList` 夹取滚动量的行为，由 Robolectric 量真几何
     * （`ui/PreviewStripCenterTest`）。
     */
    @Test
    fun `当前页居中偏移是负值 两侧留白相等 不窄于视口时回零`() {
        assertEquals("偶数差：严格一半", -120, ReaderMenuLayout.previewCenterScrollOffsetPx(125, 365))
        assertEquals("奇数差：整数除法把 1px 留给一侧", -120, ReaderMenuLayout.previewCenterScrollOffsetPx(124, 365))
        assertTrue(
            "条目比视口窄时偏移必须是负的（正数会把当前页推出视口）",
            ReaderMenuLayout.previewCenterScrollOffsetPx(124, 365) < 0,
        )
        for (itemWidth in 40..340 step 20) {
            val offset = ReaderMenuLayout.previewCenterScrollOffsetPx(itemWidth, 365)
            val leftGap = -offset
            val rightGap = 365 - (itemWidth - offset)
            assertTrue(
                "条目 ${itemWidth}px：两侧留白 $leftGap / $rightGap 必须相等（±1px）",
                kotlin.math.abs(leftGap - rightGap) <= 1,
            )
        }
        assertEquals("条目与视口同宽：没有可居中的空间", 0, ReaderMenuLayout.previewCenterScrollOffsetPx(365, 365))
        assertEquals("条目比视口宽：同此（不能往正方向推）", 0, ReaderMenuLayout.previewCenterScrollOffsetPx(400, 365))
    }

    /**
     * 票 #105 AC17（第 15 轮）：居中偏移的输入是**目标项的实测宽**，而它在居中之后还会变 ——
     * 位图到达时真实比例生效（未解码时是占位比例 2:3）、标题行数回填时预览条高度也会变。
     * 本用例把两种比例下的「图片宽」走生产口径算出来，钉两件事：
     * ① 同一条（占位 → 真实）偏移**必须变化**（若某处把宽度当常量、或把居中算在宽度还会变的时刻，
     *    下面这条不等式就红）；② 每个宽度下偏移都让条目两侧留白相等（= 居中）。
     * 生产侧的「何时重算」由 `PreviewStrip` 的 `LaunchedEffect(target, pageCount, targetItemWidthPx, …)`
     * 实现（目标项实测宽进 key）；那条链在本机没有自动用例（见 `ui/PreviewStripCenterTest` 的类 KDoc
     * 与 evidence-impl.md 第 13/15 轮残余风险），真机判据：打开菜单后等一秒（位图解码完成）看当前页
     * 是否仍在预览区正中，尤其是**封面/双页跨页**这类真实比例 ≠ 2:3 的页。
     */
    @Test
    fun `居中偏移随目标项实测宽变化 占位比例与真实比例必须不同`() {
        val height = 852f
        val inner = 365f
        val strip = previewStripHeight(height, inner)
        val label = labelHeight(inner)
        // 同一格在「占位比例 2:3」与「真实比例 2:1（双页跨页）」下的图片宽（都走生产两函数）
        val placeholderImage = ReaderMenuLayout.previewImageHeightDp(
            strip,
            label,
            inner,
            ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT,
        )
        val realImage = ReaderMenuLayout.previewImageHeightDp(strip, label, inner, 2f)
        val placeholderWidth = ReaderMenuLayout.previewItemWidth(placeholderImage, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        val realWidth = ReaderMenuLayout.previewItemWidth(realImage, 2f)
        assertTrue(
            "同一格在两种比例下的宽度必须不同（占位 $placeholderWidth px → 真实 $realWidth px，否则本用例无判别力）",
            kotlin.math.abs(realWidth - placeholderWidth) > 1f,
        )
        val viewport = inner.toInt()
        val placeholderOffset = ReaderMenuLayout.previewCenterScrollOffsetPx(placeholderWidth.toInt(), viewport)
        val realOffset = ReaderMenuLayout.previewCenterScrollOffsetPx(realWidth.toInt(), viewport)
        assertTrue(
            "宽度变了偏移必须跟着变（占位 $placeholderOffset px → 真实 $realOffset px）：把宽度当常量就会偏 " +
                "|真实宽 − 占位宽| / 2 = ${kotlin.math.abs(realWidth - placeholderWidth) / 2f}dp",
            placeholderOffset != realOffset,
        )
        for ((label2, width) in listOf("占位比例" to placeholderWidth, "真实比例" to realWidth)) {
            val offset = ReaderMenuLayout.previewCenterScrollOffsetPx(width.toInt(), viewport)
            val leftGap = -offset
            val rightGap = viewport - (width.toInt() - offset)
            assertTrue("$label2：两侧留白 $leftGap / $rightGap 必须相等（±1px）", kotlin.math.abs(leftGap - rightGap) <= 1)
        }
    }

    @Test
    fun `进度条独占一行 不遮挡缩略图`() {
        // 票 #105 批次 6 AC13：改前滑动条叠在预览条下缘（48dp 高）——真机 18.jpg 里它盖住了缩略图；
        // 旧断言是「遮挡 ≤ 25%」，四行结构下遮挡恒为 0，这里给它的**等价替代**：
        // 面板内容高 = 标题行 + 预览条 + 进度条行 + 底部行 + 行距 + 内边距 —— 每一项都在测试里独立写出，
        // 因此 `fixedRowsHeightDp` 若把进度条行算漏/算重（或又把它塞回预览条）这条等式就穿。
        for ((label, viewport) in viewports) {
            val (height, inner) = viewport
            val short = ReaderMenuLayout.isShortViewport(height)
            val panel = panelHeight(height, inner)
            val strip = previewStripHeight(height, inner)
            // 几何按档（第 14 轮）：手机竖屏档不消费底部 inset、滑条行 28dp；其余档照旧
            val phonePortrait = ReaderMenuLayout.isPhonePortrait(viewportWidth(inner), height)
            val chrome = (if (phonePortrait) 0f else ReaderOverlayLayout.MIN_BOTTOM_DP) +
                ReaderMenuLayout.panelBottomPaddingDp(
                    ReaderMenuLayout.panelRowGapDp(short),
                    ReaderOverlayLayout.MIN_BOTTOM_DP,
                    phonePortrait,
                ) +
                ReaderMenuLayout.panelTitleTopPaddingDp(short)
            val gaps = ReaderMenuLayout.panelRowGapDp(short) * 3
            val rows = titleLine(inner) + ReaderMenuLayout.sliderBandHeightDp(phonePortrait) +
                ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP
            // **本用例的唯一判据**：面板内容高展开成四条固定行 + 行距 + 内边距 + 预览条。
            // chrome / rows / gaps 都在测试里独立写出（不读 fixedRowsHeightDp），因此把进度条行算漏、
            // 算重、或又把它塞回预览条，这条等式就穿。
            assertEquals(
                "$label：面板内容高必须 = 预览条 + 四条固定行 + 行距 + 内边距",
                panel,
                chrome + rows + gaps + strip,
                0.01f,
            )
        }
        // 页数那一行**真的**占掉预览条的高度（AC14）：行高为正、且严格小于预览条高。
        // （不再断言「缩略图 + 页数 ≤ 预览条」——那一条由 previewImageHeightDp 的定义蕴含、不可能失败。）
        for ((label, viewport) in viewports) {
            val (height, inner) = viewport
            val strip = previewStripHeight(height, inner)
            val label2 = labelHeight(inner)
            assertTrue("$label：页数行高 ${label2}dp 必须为正", label2 > 0f)
            assertTrue("$label：页数行高 ${label2}dp 必须小于预览条高 ${strip}dp", label2 < strip)
        }
    }

    @Test
    fun `滑动条是 48dp 触摸目标 底部行可见 36dp 而可点仍 48dp`() {
        // 补记 8 的 A 档：底部行**可见**高 48 → 36dp（省出的高度给预览条），但触区不许缩：
        // 上一本/下一本那两列的可点高度仍是 48dp（触摸目标下限，命中区溢出到行外）
        assertEquals(36f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        assertEquals(48f, ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP, 0.01f)
        assertEquals(48f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP, 0.01f)
        assertTrue(
            "可点高度 ${ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP}dp 必须 ≥ 48dp 触摸目标下限",
            ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP >= 48f,
        )
        assertEquals(
            "本体最小高与列可点高同值（一个是可见本体、一个是整列的可点高）",
            ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP,
            ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP,
            0.01f,
        )
        assertTrue("行距不得为负", ReaderMenuLayout.PANEL_ROW_GAP_DP >= 0f)
        assertTrue("左右内边距不得为负", ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP >= 0f)
        assertTrue(
            "底部内边距下限不得为负",
            ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP >= 0f,
        )
        assertEquals("补记 8 ② 的 A 档：面板行距 8 → 4dp", 4f, ReaderMenuLayout.PANEL_ROW_GAP_DP, 0.01f)
        // 命中带几何（r10「只向下挂」后的实情，第 11 轮按评审改成真算式）：
        //   命中带 = [行顶, 行顶 + 48dp] ⇒ 向上溢出 0（这一条的行为守卫在 `ReaderMenuFooterTest`：
        //   行顶上方 1dp / 5dp 点不中）、向下溢出 = 48 − 36 = 12dp。
        //   行下方空白 = 底部内边距 P + 面板必然扣掉的底部 inset（沉浸态下限 24dp）⇒ inset = 24 时 P = 4，
        //   即 12dp 里 **8dp 伸进底部 inset（系统手势带）**。口径集不可满足：要让 12dp 全落在内边距里得
        //   `P ≥ 48 − 36 = 12dp`，而 `P = 28 − inset = 4`（行距在底部行**上方**，不进这条式子）。
        //   本票保命中带 48dp（票面 AC），越界量登记在案、由真机目视判。
        // 下面两条都读真实算式 ⇒ HIT / 行高 / P 任一改动都会变红（旧版用「对称溢出 (48−36)/2」建模，恒绿）。
        val rowGap = ReaderMenuLayout.panelRowGapDp(shortViewport = false)
        val bottomPadding = ReaderMenuLayout.panelBottomPaddingDp(rowGap, ReaderOverlayLayout.MIN_BOTTOM_DP, phonePortrait = false)
        val hitOverflowDown = ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP - ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP
        val blankBelowRow = bottomPadding + ReaderOverlayLayout.MIN_BOTTOM_DP
        assertEquals("命中带向下溢出 = 48 − 36 = 12dp", 12f, hitOverflowDown, 0.01f)
        assertEquals("行下方空白 = 内边距 4dp + 沉浸态 inset 24dp = 28dp", 28f, blankBelowRow, 0.01f)
        assertTrue(
            "向下溢出 ${hitOverflowDown}dp 必须**严格小于**行下方空白 ${blankBelowRow}dp（否则命中带伸出面板底边、更进手势带）",
            hitOverflowDown < blankBelowRow,
        )
        assertEquals(
            "已登记的越界量：向下 $hitOverflowDown − 底部内边距 $bottomPadding = 8dp 落在底部 inset（系统手势带）内",
            8f,
            hitOverflowDown - bottomPadding,
            0.01f,
        )
    }

    /**
     * 补记 8 ③：底部行的**上下间距必须相等**（维护者口径是「中心到中心」）：
     * 上间距 = 滑条行高/2 + 行距 + 底部行可见高/2；下间距 = 底部行可见高/2 + 底部内边距 + 实际底部 inset。
     * [ReaderMenuLayout.panelBottomPaddingDp] 就是解出「两者相等」的那个内边距（纯函数），
     * 并在解为负数时回落到下限 4dp。四档 inset 逐档复算这条等式（测试自己写两个间距，不读被测函数的中间量）。
     */
    @Test
    fun `非手机竖屏档底部行上下间距相等 四档 inset`() {
        assertEquals("补记 8 ③ 的下限", 4f, ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP, 0.01f)
        for (inset in listOf(0f, 12.6f, 24f, 48f)) {
            val gap = ReaderMenuLayout.panelRowGapDp(shortViewport = false)
            val footerHalf = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP / 2f
            val padding = ReaderMenuLayout.panelBottomPaddingDp(gap, inset, phonePortrait = false)
            val spacingAbove = ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP / 2f + gap + footerHalf
            val spacingBelow = footerHalf + padding + inset
            assertTrue("inset $inset：底部内边距 $padding 不得低于下限", padding >= ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP - 0.01f)
            if (inset <= ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP / 2f + gap - ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP) {
                assertEquals(
                    "inset $inset：上间距 $spacingAbove 与下间距 $spacingBelow 必须相等",
                    spacingAbove,
                    spacingBelow,
                    0.01f,
                )
            } else {
                // 大 inset（48dp，如三键导航）：解出的内边距是负数（24 + 4 − 48 = −20），取 4dp 下限。
                // 这一档两个间距**无法**相等（内边距不得为负是硬约束，凑平只能让底部行伸进系统手势带）——
                // 此时可守的是：内边距恰好落在下限、且偏差方向只是「下间距更大」（不会反过来小于上间距）。
                assertEquals("inset $inset：解为负时必须回落到下限 4dp", ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP, padding, 0.01f)
                assertTrue(
                    "inset $inset：下限兜底后下间距 $spacingBelow 只允许不小于上间距 $spacingAbove（不得反过来更小）",
                    spacingBelow >= spacingAbove - 0.01f,
                )
            }
        }
        // 改动前是定值 4dp + 行距 8：inset 24 时相差 16dp（实测 56.5 vs 40.6），本函数把它收干
        assertEquals(
            "矮视口（行距 0）下也收干：24 + 0 − 24 = 0 → 下限 4",
            4f,
            ReaderMenuLayout.panelBottomPaddingDp(0f, 24f, phonePortrait = false),
            0.01f,
        )
        assertEquals(
            "无 inset 时行距全给底部内边距",
            28f,
            ReaderMenuLayout.panelBottomPaddingDp(4f, 0f, phonePortrait = false),
            0.01f,
        )
    }

    /**
     * **手机竖屏档**的 AC18（第 13 轮 + 裁决 C，第 14 轮限定在该档）：底部行**上下两段各 36dp** ——
     * 上段 = 滑条行半高 14 + 行距 4 + 底行半高 18；下段 = 底行半高 18 + 面板底内边距 **18** + 实际底部 inset **0**
     * （面板不消费底部 inset，所以判据里那一项为 0）。两段都读真实算式：把滑条行改回 48dp、
     * 或让面板重新消费 inset，本用例立刻变红。
     * 矮视口（行距 0）属**非手机竖屏档**，其两段值由上面那条用例的 inset 档位覆盖（24 + 0 + 18 = 42 / 18 + P + inset）。
     */
    @Test
    fun `手机竖屏档底部行上下两段各 36dp`() {
        val gap = ReaderMenuLayout.panelRowGapDp(shortViewport = false)
        val sliderHalf = ReaderMenuLayout.sliderBandHeightDp(phonePortrait = true) / 2f
        val footerHalf = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP / 2f
        assertEquals("手机竖屏档滑条行半高 = 14dp", 14f, sliderHalf, 0.01f)
        val padding = ReaderMenuLayout.panelBottomPaddingDp(gap, 24f, phonePortrait = true)
        assertEquals("手机竖屏档底部内边距 = 滑条行半高 + 行距 = 18dp（不扣 inset）", 18f, padding, 0.01f)
        val spacingAbove = sliderHalf + gap + footerHalf
        val spacingBelow = footerHalf + padding + 0f
        assertEquals("上段 = 14 + 4 + 18 = 36dp", 36f, spacingAbove, 0.01f)
        assertEquals("下段 = 18 + 18 + 0 = 36dp", 36f, spacingBelow, 0.01f)
        assertEquals("两段必须相等", spacingAbove, spacingBelow, 0.01f)
        // inset 真值对手机竖屏档**不影响结果**（面板不消费它）——这一条也是「分档」的守卫
        assertEquals(
            "手机竖屏档的内边距与 inset 无关（inset 从 0 换到 48dp 仍 18dp）",
            ReaderMenuLayout.panelBottomPaddingDp(gap, 0f, phonePortrait = true),
            ReaderMenuLayout.panelBottomPaddingDp(gap, 48f, phonePortrait = true),
            0.01f,
        )
    }

    @Test
    fun `上下一本按钮的可见本体不小于 96 乘 48dp`() {
        // 票 #105 AC7「按钮加大」的可见尺寸下限（不只可点区域）；96×48 也覆盖了触摸目标下限
        assertTrue("按钮可见宽度 ${ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP}dp 必须 ≥ 96dp", ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP >= 96f)
        assertTrue("按钮可见高度 ${ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP}dp 必须 ≥ 48dp", ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP >= 48f)
        // 本体 48dp **高于**底部行可见高 36dp 是有意的（补记 8：视觉 36、命中 48）：
        // 行高只是布局占位，本体与命中区都不缩（实测见 ReaderMenuFooterTest）
        assertTrue(
            "底部行可见高必须已压到 A 档 36dp（≤ 本体高度）",
            ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP <= ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP,
        )
    }

    /** 补记 8 ④：页数纯白、上/下一本橙——两种颜色不得混（口径都在 [ReaderMenuLayout] / `ui/AccentColor`） */
    @Test
    fun `页数是纯白 上下一本是橙`() {
        assertEquals("页数字色是纯白（补记 8 ④）", 0xFFFFFFFFL, ReaderMenuLayout.PANEL_PAGE_LABEL_COLOR)
        assertEquals(
            "上/下一本的橙必须是仓库强调色（与页数的白分开）",
            0xFFFF9800.toInt(),
            com.cc3301.comicviewer.ui.ACCENT_ORANGE.toArgb(),
        )
        assertTrue(
            "页数不得用橙色（维护者看到的是预览图画错了）",
            ReaderMenuLayout.PANEL_PAGE_LABEL_COLOR != 0xFFFF9800L,
        )
    }

    /**
     * 第 10 轮 spec P2 + 第 11 轮 P1（维护者裁决方向「不截断优先，但层级不许破」）：三等分把页数锁进 1/3 列宽
     * + `maxLines = 1` ⇒ 字号要按列宽收口，否则窄屏 + 大字体下 4 位页码（「1234 / 5678」≈110dp > 列宽 103.7dp）
     * 会把整串截掉；但收口不得把页码压到格内页码之下，因此**下限 = 格内页码字号**。
     *
     * 钉四件事：① 列宽 = 内宽 1/3 减两侧 2dp（三个中心不动）；② 上限生效时字号 = 上限、整串估算宽放得进列宽；
     * ③ 上限落到下限之下时取下限（此时才允许省略号截断，估算宽会超出列宽——本用例把这条取舍显式写出来）；
     * ④ 常规场合（fontScale 1、平板、3 位页码）不生效。
     * 不覆盖的部分：Robolectric 的字体度量是 stub，量不出真实字宽——估算用的是生产同一个占位宽常量
     * （[ReaderMenuLayout.PAGE_LABEL_CHAR_ADVANCE_EM]），真机目视（fs ≥1.5 + 4 位页码到底截不截断）仍是验收项。
     */
    @Test
    fun `四位数页码加大字体 先按列宽收口 再保层级下限`() {
        val inner = 323f // 票面算例那台窄机（363dp 屏 − 两侧 20dp）
        val text = ReaderMenuLayout.pageLabelText(displayPage = 1234, pageCount = 5678)
        assertEquals("格式只有一处（当前页 / 总页数）", "1234 / 5678", text)
        val chars = text.length
        val column = ReaderMenuLayout.pageLabelColumnWidthDp(inner)
        assertEquals("中列可用宽 = 面板内宽 1/3 − 两侧各 2dp", inner / 3f - 4f, column, 0.01f)
        val nominal = ReaderMenuLayout.panelPageLabelSp(inner)
        val floor = ReaderMenuLayout.previewPageLabelSp(inner)
        assertEquals("下限 = 格内页码字号（12sp）", 12f, floor, 0.01f)
        for (fontScale in listOf(1f, 1.2f, 1.5f, 2f)) {
            val sp = ReaderMenuLayout.pageLabelSp(1234, 5678, inner, fontScale)
            val cap = ReaderMenuLayout.pageLabelWidthCapSp(chars, inner, fontScale)
            val renderedWidth = sp * fontScale * chars * ReaderMenuLayout.PAGE_LABEL_CHAR_ADVANCE_EM
            assertTrue("fontScale $fontScale：渲染字号 ${sp}sp 不得小于格内页码 ${floor}sp（层级不破）", sp >= floor - 0.01f)
            assertTrue("fontScale $fontScale：渲染字号 ${sp}sp 不得超过 AC6 标称 ${nominal}sp", sp <= nominal + 0.01f)
            if (cap >= nominal) {
                assertEquals("fontScale $fontScale：上限不低于标称 ⇒ 字号 = 标称", nominal, sp, 0.01f)
            } else if (cap > floor) {
                assertEquals("fontScale $fontScale：上限生效时字号 = 上限", cap, sp, 0.01f)
            } else {
                assertEquals("fontScale $fontScale：上限落到下限之下时取下限", floor, sp, 0.01f)
            }
            if (cap > floor) {
                assertTrue(
                    "fontScale $fontScale：整串估算宽 ${renderedWidth}dp 必须放得进中列 ${column}dp（不截断）",
                    renderedWidth <= column + 0.01f,
                )
            } else {
                assertTrue(
                    "fontScale $fontScale：这一档连下限都放不下（估算宽 ${renderedWidth}dp > 列宽 ${column}dp）⇒ 允许省略号截断（真机判）",
                    renderedWidth > column,
                )
            }
        }
        assertEquals("窄机 + fontScale 1：上限不生效，字号仍是 AC6 的标称值", nominal, ReaderMenuLayout.pageLabelSp(1234, 5678, inner, 1f), 0.01f)
        assertTrue(
            "fontScale 2：字号 ${ReaderMenuLayout.pageLabelSp(1234, 5678, inner, 2f)}sp 必须被压到标称值 ${nominal}sp 之下（收口真会生效）",
            ReaderMenuLayout.pageLabelSp(1234, 5678, inner, 2f) < nominal,
        )
        assertEquals("平板内宽 728 + fontScale 1：不得被无谓压小", ReaderMenuLayout.panelPageLabelSp(728f), ReaderMenuLayout.pageLabelSp(12, 340, 728f, 1f), 0.01f)
        assertEquals("窄机 + 3 位页码 + fontScale 1.5：仍放得下，上限不生效", nominal, ReaderMenuLayout.pageLabelSp(45, 340, inner, 1.5f), 0.01f)
    }

    @Test
    fun `预览区高度必须扣掉面板必然占用的底部 inset`() {
        // 沉浸态下面板底部恒有一份 inset 兜底（ReaderOverlayLayout.MIN_BOTTOM_DP）：
        // 漏掉它算出来的预览区会比真机高 24dp、一屏张数会比真机少 ~15%（评审 r1 的 P2）。
        // 第 14 轮分档：**手机竖屏档例外**（裁决 C：面板不消费底部 inset）⇒ 只对非手机竖屏档成立，
        // 手机竖屏档的反方向由下面那条用例钉住。
        assertEquals(24f, ReaderOverlayLayout.MIN_BOTTOM_DP, 0.01f)
        // 手机竖屏档单独处理（`viewports[0]` 即手机竖屏，见本文件顶部的视口表）：用**索引**筛，
        // 不用显示标签字符串相等——标签改了不该静默改变被测集合
        for ((label, viewport) in viewports.drop(1)) {
            val (height, inner) = viewport
            val withInset = imageHeight(height, inner)
            val withoutInset = withInset + ReaderOverlayLayout.MIN_BOTTOM_DP
            val countWith = visibleItems(inner, withInset, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
            val countWithout = visibleItems(inner, withoutInset, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
            assertTrue(
                "$label：扣掉 inset 后的一屏张数 $countWith 必须多于漏算时的 $countWithout",
                countWith > countWithout,
            )
        }
        // 手机竖屏档（第 14 轮分档 + 裁决 C）：固定行**不含**底部 inset —— 这一条是可失败的：
        // 把 `fixedRowsHeightDp` 里的 `if (phonePortrait) 0f else bottomInsetDp` 删掉、或让手机竖屏档
        // 重新消费 inset，下面两条等值断言立刻变红（同一档传 0 / 24dp 两个实参，结果必须逐值相等）
        val (phoneHeight, phoneInner) = viewports[0].second
        val phoneTitle = titleLine(phoneInner)
        val phoneWithoutInset = ReaderMenuLayout.fixedRowsHeightDp(false, phoneTitle, 0f, phonePortrait = true)
        val phoneWithInset = ReaderMenuLayout.fixedRowsHeightDp(false, phoneTitle, 24f, phonePortrait = true)
        assertEquals(
            "手机竖屏档固定行与底部 inset 无关（传 0 与传 24dp 必须同值）",
            phoneWithoutInset,
            phoneWithInset,
            0.001f,
        )
        assertEquals(
            "手机竖屏档固定行 = 底部内边距 18 + 标题留白 3 + 标题 21.9 + 行距 3×4 + 滑条行 28 + 底行 36",
            118.9f,
            phoneWithInset,
            0.05f,
        )
        // 反方向守卫：非手机竖屏档**必须**消费 inset —— 判据是「底部 inset 与内边距之和」：
        // inset ≤ 滑条行半高 + 行距（28dp）时内边距把它吸收掉（和恒为 28dp），inset 再大则内边距落到下限 4dp、
        // 和随 inset 增长。把 else 分支的 `- bottomInsetDp` 删掉、或误用手机竖屏档分支，下面两条都红。
        val tabletInset0 = ReaderMenuLayout.fixedRowsHeightDp(false, phoneTitle, 0f, phonePortrait = false)
        val tabletInset24 = ReaderMenuLayout.fixedRowsHeightDp(false, phoneTitle, 24f, phonePortrait = false)
        val tabletInset48 = ReaderMenuLayout.fixedRowsHeightDp(false, phoneTitle, 48f, phonePortrait = false)
        assertEquals(
            "非手机竖屏档：inset 24dp 仍被内边距吸收（和 = 滑条行半高 24 + 行距 4 = 28dp）",
            tabletInset0,
            tabletInset24,
            0.001f,
        )
        assertEquals(
            "非手机竖屏档：inset 48dp 时内边距落到下限 4dp ⇒ 和 = 48 + 4（比 28dp 大一档）",
            tabletInset0 + 24f,
            tabletInset48,
            0.001f,
        )
    }

    // ---------- 矮视口（横屏手机）版式（批次 6 AC11）----------

    /** 矮视口（841×393dp 横屏手机的可用高按维护者口径取 360dp）与两个必须不受影响的视口 */
    private val shortViewport = 360f

    private val tallViewports = listOf("手机竖屏" to 852f, "平板竖屏" to 1024f, "平板横屏" to 768f)

    @Test
    fun `矮视口判定阈值落在横屏手机与其余设备之间`() {
        assertEquals(480f, ReaderMenuLayout.SHORT_VIEWPORT_MAX_HEIGHT_DP, 0.01f)
        assertTrue("360dp 可用高必须判为矮视口（横屏手机）", ReaderMenuLayout.isShortViewport(360f))
        for (height in listOf(640f, 768f, 852f, 1024f)) {
            assertTrue(
                "可用高 ${height}dp 必须是常规视口（竖屏/平板不受矮视口版式影响）",
                !ReaderMenuLayout.isShortViewport(height),
            )
        }
    }

    @Test
    fun `矮视口 360dp 视口预览条保底 80dp`() {
        // 裁定 A 后固定行按两行标题预算：内宽 812dp ⇒ 标题一行 28.8dp × 2 = 57.6dp，
        // 固定行 = 24(inset) + 4 + 0 + 57.6 + 0 + 48(进度条行) + 36(底部行) = 169.6dp。
        // 360dp 视口：固定行 + 80dp = 249.6dp（= 69.3%）≤ 80% × 360 = 288dp ⇒ **保底项先生效**，
        // 预览条足 80dp（改动前 66% 上限时会掉到 68dp —— 维护者 2026-09-21 裁定把上限放到 80%）。
        val strip = shortViewportStripHeight(shortViewport, 812f)
        val panel = panelHeight(shortViewport, 812f, lineCount = 2)
        assertTrue(
            "360dp 视口预览条 ${strip}dp 必须 ≥ 80dp（AC11 保底，会因上限过紧而变红）",
            strip >= ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP,
        )
        assertEquals("预览条 = 保底 80dp", 80f, strip, 0.01f)
        assertEquals("面板 = 固定行 + 保底", 249.6f, panel, 0.01f)
        assertTrue("预览条 ${strip}dp 必须远高于改动前的 ≈16dp 细缝", strip >= 60f)
        val ratio = panel / shortViewport
        assertTrue("面板占比 ${ratio * 100}% 必须高于 52% 起点", ratio >= ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT)
        assertTrue("面板占比 ${ratio * 100}% 必须 ≤ 80% 屏高", ratio <= ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX)
    }

    @Test
    fun `矮视口 fontScale 1_4 下预览条仍足 80dp`() {
        // 大字体下标题两行变高（28.8 × 1.4 × 2 = 80.64dp），固定行 192.64dp，
        // 需求 272.64dp = 75.7% ≤ 80% ⇒ 预览条仍足 80dp（改动前 66% 上限时只剩 39.2dp）
        val strip = shortViewportStripHeight(shortViewport, 812f, fontScale = 1.4f)
        assertTrue(
            "fontScale 1.4 时预览条 ${strip}dp 必须 ≥ 80dp（AC11：大字体下保底也要成立）",
            strip >= ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP,
        )
        val panel = panelHeight(shortViewport, 812f, lineCount = 2, fontScale = 1.4f)
        assertTrue("面板占比 ${panel / shortViewport * 100}% 必须 ≤ 80%", panel <= shortViewport * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f)
        assertEquals("面板 = 固定行 192.64 + 保底 80", 272.64f, panel, 0.01f)
    }

    @Test
    fun `480 与 600dp 高的横屏设备预览条保底 80dp`() {
        // 票 #105 AC11（r5 推广）的保底 + 第 7 轮 spec P1 的分档：480–700dp 高的横屏设备
        // （1024×600 平板、480–520dp 档）此前走「恒 40%」，四条固定行把预览条压到 0–34dp（480–520dp 下为负），
        // 保底项把他们兜到 80dp —— 但**不再**跟着手机竖屏拿大预览（那会把面板抬到 68–80% 屏高，与 AC4 冲突）。
        // 第 9 轮（A 档压缩：底部行 48→36、行距 8→4）后固定行（两行标题）= 24 + 4 + 3 + 57.6 + 12 + 48 + 36 = 184.6dp：
        //   480dp：保底需求 184.6 + 80 = 264.6 > 40% × 480 = 192 ⇒ 面板 264.6dp（55.1%）、预览条 80dp
        //   600dp：基础余量 240 − 155.8 = 84.2 > 80 ⇒ 面板 268.8dp（44.8%）、预览条 84.2dp
        for ((label, height, inner, expectedPanel, expectedStrip) in listOf(
            listOf("480dp 横屏", 480f, 812f, 264.6f, 80f),
            listOf("600dp 横屏（1024×600）", 600f, 984f, 268.8f, 84.2f),
        )) {
            val h = height as Float
            val w = viewportWidth(inner as Float)
            val strip = ReaderMenuLayout.previewStripHeightDp(w, h, titleLine(inner), 2, ReaderOverlayLayout.MIN_BOTTOM_DP)
            assertEquals(
                "$label：预览条必须 ≥ 80dp 档位下限（不得再是 0–34dp / 负数）",
                expectedStrip as Float,
                strip,
                0.01f,
            )
            assertTrue("$label：预览条 $strip dp 不得低于 80dp 保底", strip >= ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP - 0.01f)
            val panel = ReaderMenuLayout.panelHeightDp(w, h, titleLine(inner), 2, ReaderOverlayLayout.MIN_BOTTOM_DP)
            assertEquals("$label：面板高度（A 档压缩后的实测值）", expectedPanel as Float, panel, 0.01f)
            assertTrue("$label：面板占比 ${panel / h} 必须 ≥ 40% 且 ≤ 80%", panel / h >= 0.4f - 0.001f && panel / h <= 0.8f + 0.001f)
        }
    }

    /**
     * 票面第 4 条（第 8 轮 spec P1 的判据）：**任何视口**下标题 1→3 行都只抬高面板，
     * 预览条高度**逐像素相等**（r7 时平板竖屏 229.8→172.2dp、平板横屏 127.4→80.0dp 都会缩）。
     */
    @Test
    fun `任何视口下标题行数都不改预览条高度`() {
        for ((label, inner, height) in listOf(
            Triple("手机竖屏", 365f, 852f),
            Triple("平板竖屏", 728f, 1024f),
            Triple("平板横屏", 984f, 768f),
            Triple("480dp 高横屏", 812f, 480f),
            Triple("600dp 高横屏", 984f, 600f),
            Triple("矮视口 360dp", 812f, 360f),
        )) {
            val strips = (1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES).map { lines ->
                previewStripHeight(height, inner, lineCount = lines)
            }
            assertEquals("$label：2 行标题的预览条必须与 1 行逐像素相等（$strips）", strips[0], strips[1], 0.01f)
            assertEquals("$label：3 行标题的预览条必须与 1 行逐像素相等（$strips）", strips[0], strips[2], 0.01f)
            val target = ReaderMenuLayout.previewStripTargetDp(
                viewportWidth(inner),
                height,
                titleLine(inner),
                ReaderOverlayLayout.MIN_BOTTOM_DP,
            )
            assertEquals("$label：预览条必须等于该视口的目标高度（只由档位决定）", target, strips[0], 0.01f)
            // 面板随行数单调变高，且不越 80% 屏高
            val panels = (1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES).map { lines -> panelHeight(height, inner, lineCount = lines) }
            assertTrue("$label：面板必须随行数变高：$panels", panels[0] < panels[1] && panels[1] < panels[2])
            assertTrue(
                "$label：面板 ${panels[2]}dp 必须 ≤ 80% 屏高",
                panels[2] <= height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f,
            )
        }
    }

    @Test
    fun `预览条保底按视口分档 只有手机竖屏拿大预览`() {
        // 第 7 轮 spec P1 的判据：上一轮把「大预览」施加到「所有非矮视口」，把 480/600dp 高横屏抬到 80%/68.1%、
        // 平板横屏抬到 53%，与 AC4「约 40%」、AC11「竖屏与平板占比逐像素一致」冲突。
        // 本用例把分档钉死：只有手机竖屏取 201dp（第 13 轮 AC19）、其余视口一律 80dp。
        assertTrue("手机竖屏（视口 405 × 852）必须是「手机竖屏」档", ReaderMenuLayout.isPhonePortrait(405f, 852f))
        assertEquals(
            "手机竖屏档的保底 = 201dp（第 13 轮 AC19：224 → 201）",
            ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP,
            ReaderMenuLayout.previewStripMinDp(405f, 852f),
            0.01f,
        )
        for ((label, inner, height) in listOf(
            Triple("平板竖屏", 728f, 1024f),
            Triple("平板横屏", 984f, 768f),
            Triple("480dp 高横屏", 812f, 480f),
            Triple("600dp 高横屏", 984f, 600f),
            Triple("矮视口 360dp", 812f, 360f),
        )) {
            val width = viewportWidth(inner)
            assertTrue("$label（视口 ${width} × ${height}）不是「手机竖屏」档", !ReaderMenuLayout.isPhonePortrait(width, height))
            assertEquals(
                "$label：保底必须回 80dp（不得跟着手机竖屏抬到 201dp）",
                ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP,
                ReaderMenuLayout.previewStripMinDp(width, height),
                0.01f,
            )
        }
        // 分档边界：视口宽 600dp 起算平板档；矮视口（可用高 < 480dp）恒不是手机竖屏档
        assertTrue("599dp 宽仍是手机竖屏档", ReaderMenuLayout.isPhonePortrait(599f, 900f))
        assertTrue("600dp 宽起已是平板档", !ReaderMenuLayout.isPhonePortrait(600f, 900f))
        assertTrue("横屏手机（可用高 440dp）走矮视口 80dp 档，不算手机竖屏", !ReaderMenuLayout.isPhonePortrait(405f, 440f))
    }

    @Test
    fun `分档后的面板占比 手机竖屏约四成 平板逐像素不变`() {
        // 判据 ①：手机竖屏（第 13 轮 AC19 + 裁决 C、第 14 轮限定在该档）—— 预览条 = 基础 40% 扣掉固定行
        // 后的余量（221.9dp ≥ 201dp 保底）⇒ 面板恰好是 40%（不再被抬到 43.8%）；固定行含 28dp 滑条行、
        // 不含底部 inset
        val phoneHeight = 852f
        val phoneInner = 365f
        val phoneTitle = ReaderMenuLayout.titleHeightDp(titleLine(phoneInner), 1)
        val phoneFixed = ReaderMenuLayout.fixedRowsHeightDp(false, phoneTitle, ReaderOverlayLayout.MIN_BOTTOM_DP, true)
        val phonePanel = panelHeight(phoneHeight, phoneInner)
        assertEquals(
            "手机竖屏面板 = 40% × 屏高（AC4 基础值）",
            phoneHeight * ReaderMenuLayout.PANEL_HEIGHT_FRACTION,
            phonePanel,
            0.01f,
        )
        assertTrue("手机竖屏占比 ${phonePanel / phoneHeight} 必须恰好是 0.40（AC4）", phonePanel / phoneHeight in 0.399f..0.401f)
        assertEquals(
            "手机竖屏固定行（1 行标题）= 18 + 3 + 21.9 + 12 + 28 + 36",
            118.9f,
            phoneFixed,
            0.05f,
        )
        assertEquals(
            "手机竖屏预览条 = 40% 扣掉固定行后的余量（221.9dp）",
            221.9f,
            previewStripHeight(phoneHeight, phoneInner),
            0.05f,
        )
        // 判据 ②：平板竖屏 / 平板横屏 —— **一行标题**时面板仍是 40%（AC4 基础值，等值断言，未放宽），
        // 标题变 2/3 行只把面板往上长（票面第 4 条），且始终不越 80% 屏高。
        for ((label, inner, height) in listOf(
            Triple("平板竖屏", 728f, 1024f),
            Triple("平板横屏", 984f, 768f),
        )) {
            assertEquals(
                "$label：一行标题时面板必须仍是 40% × 屏高（逐像素不变）",
                height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION,
                panelHeight(height, inner),
                0.01f,
            )
            val panels = (1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES).map { lines -> panelHeight(height, inner, lineCount = lines) }
            assertTrue("$label：面板必须随行数单调变高：$panels", panels[0] < panels[1] && panels[1] < panels[2])
            assertTrue(
                "$label：3 行标题下面板 ${panels[2]}dp（占比 ${panels[2] / height}）必须 ≤ 80% 屏高",
                panels[2] <= height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f,
            )
        }
    }

    @Test
    fun `矮视口视口够高时预览条仍保底 80dp`() {
        // 视口高 400dp：80% 上限 = 320dp ≥ 固定行 169.6 + 80 ⇒ 保底项取到实际值
        val strip = shortViewportStripHeight(400f, 812f)
        assertEquals(
            "视口高 400dp 时预览条必须正好是矮视口保底 80dp",
            ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP,
            strip,
            0.01f,
        )
        val panel = panelHeight(400f, 812f, lineCount = 2)
        assertTrue(
            "面板占比 ${panel / 400f * 100}% 必须 ≤ 80%",
            panel <= 400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f,
        )
    }

    @Test
    fun `矮视口面板 52% 是公式起点 不是 40%`() {
        // 票 #105 评审 spec P2-1：“52% 起点”必须真的在代码里。用**合成输入**把保底项压小
        // （标题 10dp、无底部 inset）：底部内边距 = max(4, 24 + 0 − 0) = 24dp，固定行 = 24 + 10 + 48 + 36 = 118dp，
        // 保底项 = max(80, 208 − 118 = 90) = 90dp ⇒ 此时面板取 52% 的值（208dp）；若代码误用 40%（= 160dp）本断言变红。
        val panel = ReaderMenuLayout.panelHeightDp(
            viewportWidth(812f),
            400f,
            titleLineHeightDp = 10f,
            titleLineCount = 1,
            bottomInsetDp = 0f,
        )
        assertEquals(400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT, panel, 0.01f)
        assertTrue("52% 起点必须高于 40% 的旧值", panel > 400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION)
    }

    @Test
    fun `矮视口固定行已压扁 标题不留白行距为零`() {
        assertTrue("矮视口标题不留上侧留白", ReaderMenuLayout.panelTitleTopPaddingDp(true) == 0f)
        assertTrue("矮视口行距为零", ReaderMenuLayout.panelRowGapDp(true) == 0f)
        // 底部行不再分「矮视口 36 / 其余 48」两个值：补记 8 的 A 档把全视口统一到 36dp（可见高）
        assertEquals(36f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        // 进度条行：**矮视口属「非手机竖屏档」**，第 14 轮分档后仍保持 48dp 触摸目标下限（改动前口径）
        assertEquals(48f, ReaderMenuLayout.sliderBandHeightDp(phonePortrait = false), 0.01f)
    }

    @Test
    fun `非矮视口的固定行尺寸按 A 档统一 标题留白不缩`() {
        // 补记 8 的 A 档（底部行 36dp、行距 4dp）对**所有视口**生效；矮视口额外把标题留白与行距压到 0。
        // 这里钉住非矮视口的三个尺寸，并验证安卓平板两档的面板仍由基础占比兜底（逐像素不变）。
        for ((label, height) in tallViewports) {
            val panel = panelHeight(height, 400f)
            assertTrue("$label：面板必须 ≥ 四成基线（保底项只会抬高）", panel >= height * 0.4f - 0.01f)
            assertEquals("$label：标题留白不缩", ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP, ReaderMenuLayout.panelTitleTopPaddingDp(false), 0.01f)
            assertEquals("$label：行距 = A 档的 4dp", 4f, ReaderMenuLayout.panelRowGapDp(false), 0.01f)
            assertEquals("$label：底部行可见高 = A 档的 36dp", 36f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        }
        // 面板占比按档断言（等值，未放宽）：平板竖屏/横屏仍是 40%（手机竖屏那一档是保底项抬高的，
        // 由 `分档后的面板占比 手机竖屏约四成 平板逐像素不变` 用等值式钉住，不在这里混着写不等式）
        for ((label, inner, height) in listOf(
            Triple("平板竖屏", 728f, 1024f),
            Triple("平板横屏", 984f, 768f),
        )) {
            val panel = panelHeight(height, inner)
            assertEquals("$label：面板仍须是视口高度的 40%", height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION, panel, 0.01f)
        }
    }

    @Test
    fun `极矮视口面板顶到 80% 上限`() {
        // 240dp 可用高（折叠机外屏/分屏）：内宽 400dp ⇒ 标题两行 48dp、固定行 160dp，
        // 保底需求 = 160 + 80 = 240dp，80% 上限 = 192dp ⇒ 上限生效：面板 192dp（顶满 80%）、预览条 32dp。
        // 这是「极小屏保不住 80dp、但也别让面板吃掉整屏」的兜底场合。
        assertEquals(
            "两行标题预算：内宽 400dp ⇒ 一行 24dp × 2",
            48f,
            ReaderMenuLayout.titleHeightDp(titleLine(400f), 2),
            0.01f,
        )
        val panel = panelHeight(240f, 400f, lineCount = 2)
        assertEquals("面板必须正好顶到 80% 上限", 240f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX, panel, 0.01f)
        val strip = shortViewportStripHeight(240f, 400f)
        assertTrue(
            "此类极小视口下预览条 $strip dp 确实不足矮视口保底 80dp（兜底上限先生效，已在证据里披露）",
            strip < ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP,
        )
        assertTrue("预览条仍必须为正（不得出现负高）", strip > 0f)
    }

    // ---------- 格内页数那一行（批次 6 AC14）----------

    @Test
    fun `缩略图高度等于预览条高减页数行高`() {
        // 页数行高是 **sp**（票 #105 标准轴 P2-5）：字号 × 行高比例
        assertEquals(15f, ReaderMenuLayout.previewLabelHeightSp(12.5f), 0.01f)
        // 加回页数行必须恰好等于预览条高（缩略图 + 页数 = 一整格，不多不少）
        val image = ReaderMenuLayout.previewImageHeightDp(200f, 15f, 365f, 2f / 3f)
        assertEquals(185f, image, 0.01f)
        assertEquals("缩略图 + 页数行 = 预览条高", 200f, image + 15f, 0.01f)
        // 预览条比页数行还矮时不出现负高度
        assertEquals(0f, ReaderMenuLayout.previewImageHeightDp(10f, 15f, 365f, 2f / 3f), 0.01f)
        // 超宽页仍按宽度收口（比例不变）
        assertEquals(182.5f, ReaderMenuLayout.previewImageHeightDp(224f, 22.5f, 365f, 2f), 0.05f)
    }

    @Test
    fun `sp 与 dp 不等价 标题行与页数行都随 fontScale 变大`() {
        // 票 #105 标准轴 P2-5：把 sp 数值当 dp 用会在系统大字体下把固定行算小。
        // 标题：矮视口内宽 812dp ⇒ 字号 24sp ⇒ 一行 28.8sp；fontScale 1.5 时 = 43.2dp
        assertEquals(28.8f, ReaderMenuLayout.titleLineHeightDp(812f, 1f), 0.01f)
        assertEquals(43.2f, ReaderMenuLayout.titleLineHeightDp(812f, 1.5f), 0.01f)
        // 页数行：同一条换算（走 Density.toDp），fontScale 放大时该行也必须变大
        assertEquals(12.775f, ReaderMenuLayout.previewPageLabelSp(365f), 0.001f)
        assertEquals(15.33f, labelHeight(365f), 0.01f)
        val scaled = labelHeight(365f, 1.5f)
        assertTrue("fontScale 1.5 时页数行 $scaled dp 必须明显高于常规字体下的 ${labelHeight(365f)}dp", scaled > labelHeight(365f) * 1.4f)
        assertTrue("fontScale 1.5 时页数行 $scaled dp 不得超出字号×比例的 1.5 倍", scaled <= labelHeight(365f) * 1.5f + 0.01f)
    }

    @Test
    fun `大字体下矮视口面板跟着变高 预览条不缩水`() {
        // 标题总高随 fontScale 变高，面板必须把这一项算进固定行——否则预览条会被静默抽掉。
        // fontScale 1.0：固定行 169.6、面板 249.6、预览条 80dp；fontScale 1.5：标题两行 86.4dp
        // ⇒ 固定行 198.4dp、需求 278.4dp = 77.3% ≤ 80% ⇒ 预览条**仍 80dp**（改动前 66% 时只剩 39.2dp）。
        val stripNormal = shortViewportStripHeight(shortViewport, 812f)
        assertEquals(80f, stripNormal, 0.1f)
        val stripLarge = shortViewportStripHeight(shortViewport, 812f, fontScale = 1.5f)
        assertEquals("大字体下预览条不得缩水（上限从 66% 放到 80% 的目的）", 80f, stripLarge, 0.1f)
        val panelLarge = panelHeight(shortViewport, 812f, lineCount = 2, fontScale = 1.5f)
        assertEquals("面板随标题变高：198.4 固定行 + 80 保底", 278.4f, panelLarge, 0.01f)
        assertTrue("面板占比 ${panelLarge / shortViewport * 100}% 必须 ≤ 80%", panelLarge <= shortViewport * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f)
    }

    // ---------- 横向 inset 的逐行分配（票 #105 AC12）----------

    @Test
    fun `面板内容区宽度扣掉内边距与左右 inset`() {
        // 票 #105 AC12（r5 修订）：面板整块消费 readerPanelInsets()，**四行一致**（标题也在内容区里居中），
        // 与 `docs/SPEC.md` 故事 28「贴底浮层显式消费挖孔 inset」同口径。这里钉的是内容区宽度口径：
        // 屏宽 − 两侧内边距 − 左右 inset；侧边 inset 非 0 时预览区宽度（也就是超宽页的收口算据）必须跟着变窄。
        assertEquals("无 inset：852 − 20 × 2", 812f, ReaderMenuLayout.panelInnerWidthDp(852f, 0f), 0.01f)
        val withCutout = ReaderMenuLayout.panelInnerWidthDp(852f, 44f)
        assertEquals("右侧挖孔 44dp：内容区必须再窄 44dp", 768f, withCutout, 0.01f)
        // 超宽页（宽高比 12:1）按内容区宽收口：宽度口径若退回「未扣 inset 的屏宽」（812dp），
        // 收口高度会是 812/12 = 67.7dp；按内容区宽（768dp）则是 64dp
        val strip = shortViewportStripHeight(shortViewport, withCutout)
        assertEquals(
            "12:1 超宽页收口后高度 = 内容区宽 / 12",
            withCutout / 12f,
            ReaderMenuLayout.previewItemHeight(strip, withCutout, 12f),
            0.01f,
        )
        assertTrue(
            "同一页按未扣 inset 的屏宽收口会得到更大的高度（67.7 > 64）——这就是宽度口径要扣 inset 的理由",
            ReaderMenuLayout.previewItemHeight(strip, 812f, 12f) > ReaderMenuLayout.previewItemHeight(strip, withCutout, 12f),
        )
        assertTrue(
            "内容区必须比不扣 inset 时窄（否则收口算据是错的）",
            withCutout < ReaderMenuLayout.panelInnerWidthDp(852f, 0f),
        )
        assertTrue("内容区宽度不得为负", ReaderMenuLayout.panelInnerWidthDp(320f, 900f) >= 0f)
    }

    // ---------- 预览项尺寸（AC1/AC3）----------

    @Test
    fun `单格宽度等于高度乘真实比例`() {
        // 方案图的手机档：高 240dp 的 2:3 页 → 160dp 宽
        assertEquals(160f, ReaderMenuLayout.previewItemWidth(240f, 2f / 3f), 0.05f)
        // 方案图的平板档：高 310dp 的 2:3 页 → 207dp 宽
        assertEquals(206.7f, ReaderMenuLayout.previewItemWidth(310f, 2f / 3f), 0.05f)
        // 双页跨页（2:1 横向）：同一高度下更宽
        assertEquals(480f, ReaderMenuLayout.previewItemWidth(240f, 2f), 0.05f)
    }

    @Test
    fun `横向页比竖向页宽 上下不留白`() {
        val portrait = ReaderMenuLayout.previewItemWidth(200f, 2f / 3f)
        val landscape = ReaderMenuLayout.previewItemWidth(200f, 1.4f)
        assertTrue("横向页（$landscape）必须比竖向页（$portrait）宽", landscape > portrait)
        assertTrue("竖向页也窄于预览区高度（上下不留白 ⇒ 宽度 ≤ 高度 × 比例）", portrait < 200f)
        assertTrue("横向页比高度还宽", landscape > 200f)
    }

    @Test
    fun `超宽页按预览区宽度收口 比例不变且不靠左贴边`() {
        // 双页跨页（2:1）在「高度撑满」下宽度会超过预览区（224 × 2 = 448 > 365）：
        // 收口后高度降到 365/2 = 182.5dp、宽度 365dp（铺满预览区、整页可见、不靠左贴边）
        val height = ReaderMenuLayout.previewItemHeight(224f, 365f, 2f)
        assertEquals(182.5f, height, 0.05f)
        assertEquals("收口后宽度 = 预览区宽", 365f, ReaderMenuLayout.previewItemWidth(height, 2f), 0.05f)
        // 常见竖版页不受影响：高度仍撑满预览区
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, 2f / 3f), 0.05f)
        // 刚好放得下的横向页也不收口（1.6:1 ⇒ 224 × 1.6 = 358.4 ≤ 365）
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, 1.6f), 0.05f)
        // 比例非法时不收口（回落到占位比例那条路）
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, 0f), 0.05f)
        assertEquals(224f, ReaderMenuLayout.previewItemHeight(224f, 365f, -1f), 0.05f)
    }

    @Test
    fun `非正比例回 0 由占位比例兜底`() {
        assertEquals(0f, ReaderMenuLayout.previewItemWidth(200f, 0f), 0.001f)
        assertEquals(0f, ReaderMenuLayout.previewItemWidth(200f, -1f), 0.001f)
        assertEquals(0f, ReaderMenuLayout.previewItemWidth(0f, 2f / 3f), 0.001f)
    }

    @Test
    fun `占位比例是常见的 2 比 3 且为正`() {
        assertEquals(2f / 3f, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT, 0.0001f)
        assertTrue("占位比例必须为正（0 会让格子先塌成一条线）", ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT > 0f)
    }

    @Test
    fun `位图尺寸换算成宽高比`() {
        assertEquals(2f / 3f, ReaderMenuLayout.previewItemAspect(200, 300)!!, 0.0001f)
        assertEquals(1.4f, ReaderMenuLayout.previewItemAspect(140, 100)!!, 0.0001f)
    }

    @Test
    fun `位图尺寸无效时回 null`() {
        assertNull("宽为 0", ReaderMenuLayout.previewItemAspect(0, 300))
        assertNull("高为 0", ReaderMenuLayout.previewItemAspect(200, 0))
        assertNull("负尺寸", ReaderMenuLayout.previewItemAspect(-1, 5))
    }

    @Test
    fun `解码高度按预览区高度向上分桶 过冲不超过一个桶`() {
        val bucket = CoverDecode.BUCKET_PX
        // 手机预览区约 228dp、密度 2：456px → 向上取到 480px
        val decode = ReaderMenuLayout.previewDecodeHeightPx(456f)
        assertEquals(480, decode)
        assertTrue("解码高度必须 ≥ 预览区高度像素", decode.toFloat() >= 456f)
        assertTrue("过冲不得超过 32px", decode - 456f <= 32f)
        assertEquals("桶的整数倍", 0, decode % bucket)
        // 平板预览区更高 → 解得更高，不是固定高度
        assertTrue(ReaderMenuLayout.previewDecodeHeightPx(580f) > decode)
        // 恰好落在桶上不加码
        assertEquals(480, ReaderMenuLayout.previewDecodeHeightPx(480f))
        // 布局还没就绪（0/负）也给一个桶，避免 0 高度解码
        assertEquals(bucket, ReaderMenuLayout.previewDecodeHeightPx(0f))
    }

    // ---------- 三档字号（AC6）----------

    @Test
    fun `标题字号 内宽乘 0_05 夹 18 到 24`() {
        assertEquals(18.25f, ReaderMenuLayout.panelTitleSp(365f), 0.01f)
        assertEquals(24f, ReaderMenuLayout.panelTitleSp(740f), 0.01f)
        assertEquals("极窄面板夹在下限", 18f, ReaderMenuLayout.panelTitleSp(100f), 0.01f)
        assertEquals("宽面板夹在上限", 24f, ReaderMenuLayout.panelTitleSp(1400f), 0.01f)
        assertEquals(0.05f, ReaderMenuLayout.PANEL_TITLE_SP_RATIO, 0.0001f)
    }

    @Test
    fun `页码字号 内宽乘 0_05 夹 16 到 24`() {
        assertEquals(18.25f, ReaderMenuLayout.panelPageLabelSp(365f), 0.01f)
        assertEquals(24f, ReaderMenuLayout.panelPageLabelSp(740f), 0.01f)
        assertEquals("极窄面板夹在下限", 16f, ReaderMenuLayout.panelPageLabelSp(100f), 0.01f)
        assertEquals("宽面板夹在上限", 24f, ReaderMenuLayout.panelPageLabelSp(1400f), 0.01f)
        assertEquals(0.05f, ReaderMenuLayout.PANEL_PAGE_LABEL_SP_RATIO, 0.0001f)
    }

    @Test
    fun `格内页码字号 内宽乘 0_035 夹 12 到 16`() {
        assertEquals(12.775f, ReaderMenuLayout.previewPageLabelSp(365f), 0.01f)
        assertEquals(16f, ReaderMenuLayout.previewPageLabelSp(740f), 0.01f)
        assertEquals("极窄面板夹在下限", 12f, ReaderMenuLayout.previewPageLabelSp(100f), 0.01f)
        assertEquals("宽面板夹在上限", 16f, ReaderMenuLayout.previewPageLabelSp(1400f), 0.01f)
        assertEquals(0.035f, ReaderMenuLayout.PREVIEW_LABEL_SP_RATIO, 0.0001f)
    }

    @Test
    fun `票面表的两个内宽档取值`() {
        // 手机（内宽 365）：18.3 / 18.3 / 12.8sp
        assertEquals(18.3f, ReaderMenuLayout.panelTitleSp(365f), 0.05f)
        assertEquals(18.3f, ReaderMenuLayout.panelPageLabelSp(365f), 0.05f)
        assertEquals(12.8f, ReaderMenuLayout.previewPageLabelSp(365f), 0.05f)
        // 平板（内宽 740）：24 / 24 / 16sp
        assertEquals(24f, ReaderMenuLayout.panelTitleSp(740f), 0.05f)
        assertEquals(24f, ReaderMenuLayout.panelPageLabelSp(740f), 0.05f)
        assertEquals(16f, ReaderMenuLayout.previewPageLabelSp(740f), 0.05f)
    }

    @Test
    fun `三档字号层级恒为 标题大于等于页码 且 页码大于格内页码`() {
        for (width in listOf(0f, 100f, 240f, 320f, 365f, 480f, 600f, 740f, 920f, 1280f, 1400f)) {
            val title = ReaderMenuLayout.panelTitleSp(width)
            val page = ReaderMenuLayout.panelPageLabelSp(width)
            val label = ReaderMenuLayout.previewPageLabelSp(width)
            assertTrue("内宽 ${width}dp：标题 $title 必须 ≥ 页码 $page", title >= page)
            assertTrue("内宽 ${width}dp：页码 $page 必须 > 格内页码 $label", page > label)
        }
        assertTrue(
            "标题下限必须大于等于页码下限",
            ReaderMenuLayout.PANEL_TITLE_MIN_SP >= ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP,
        )
        assertTrue(
            "标题上限必须大于等于页码上限",
            ReaderMenuLayout.PANEL_TITLE_MAX_SP >= ReaderMenuLayout.PANEL_PAGE_LABEL_MAX_SP,
        )
        // 票面表里页码下限与格内页码上限同为 16sp，层级因此由**比例**保住：格内页码顶到上限时，
        // 页码已经明显更高（下面用生产公式算出那个内宽再比）
        val labelCappedWidth = ReaderMenuLayout.PREVIEW_LABEL_MAX_SP / ReaderMenuLayout.PREVIEW_LABEL_SP_RATIO
        assertTrue(
            "格内页码顶到上限时（内宽 ${labelCappedWidth}dp）页码 ${
                ReaderMenuLayout.panelPageLabelSp(labelCappedWidth)
            }sp 必须高于格内页码上限 ${ReaderMenuLayout.PREVIEW_LABEL_MAX_SP}sp",
            ReaderMenuLayout.panelPageLabelSp(labelCappedWidth) > ReaderMenuLayout.PREVIEW_LABEL_MAX_SP,
        )
        assertTrue(
            "页码下限不得低于格内页码上限（同值时由比例分层）",
            ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP >= ReaderMenuLayout.PREVIEW_LABEL_MAX_SP,
        )
        // 三档都比改动前的字号大（#66/#67 的口径），且标题档整体降下来了（本票 AC6）
        assertTrue("标题上限不得再是 #67 的 32sp", ReaderMenuLayout.PANEL_TITLE_MAX_SP <= 24f)
        assertTrue("格内页码下限不得低于原 labelSmall 的 11sp", ReaderMenuLayout.PREVIEW_LABEL_MIN_SP >= 11f)
    }

    /**
     * 第 11 轮 P1（维护者裁决方向「不截断优先，但层级不许破」）：上面那条不变量读的是**标称**字号
     * （[ReaderMenuLayout.panelPageLabelSp]），而中列渲染用的是 [ReaderMenuLayout.pageLabelSp]（标称 → 按列宽收口 → 夹下限）。
     * 本用例把**渲染值**钉进层级：无论 fontScale 与页码位数如何，`渲染页码 ≥ 格内页码字号` 恒成立。
     * 不覆盖的部分：这是字号（sp）层面的层级，不是渲染后的像素宽度；真机字体度量下是否截断仍归真机。
     */
    @Test
    fun `渲染页码字号恒不低于格内页码`() {
        val cases = listOf(
            12 to 340,
            1234 to 5678,
            99999 to 99999,
        )
        for (inner in listOf(240f, 323f, 365f, 480f, 728f, 984f)) {
            val floor = ReaderMenuLayout.previewPageLabelSp(inner)
            for (fontScale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                for ((displayPage, pageCount) in cases) {
                    val rendered = ReaderMenuLayout.pageLabelSp(displayPage, pageCount, inner, fontScale)
                    assertTrue(
                        "内宽 ${inner}dp / fontScale $fontScale / 页码 $displayPage / $pageCount：" +
                            "渲染字号 ${rendered}sp 不得小于格内页码 ${floor}sp",
                        rendered >= floor - 0.01f,
                    )
                    assertTrue(
                        "内宽 ${inner}dp / fontScale $fontScale：渲染字号 ${rendered}sp 不得高于标称 ${ReaderMenuLayout.panelPageLabelSp(inner)}sp",
                        rendered <= ReaderMenuLayout.panelPageLabelSp(inner) + 0.01f,
                    )
                }
            }
        }
        // 下限本身与格内页码同源（同一条公式、同一个内宽）⇒ 不会出现「下限比格内页码大一个档」的怪事
        for (inner in listOf(0f, 240f, 323f, 728f, 1400f)) {
            assertEquals(
                "内宽 ${inner}dp：下限必须就是格内页码字号",
                ReaderMenuLayout.previewPageLabelSp(inner),
                ReaderMenuLayout.pageLabelSp(1, 1, inner, 100f),
                0.01f,
            )
        }
    }

    @Test
    fun `三档字号都随内宽单调不减`() {
        val widths = listOf(240f, 320f, 365f, 480f, 600f, 740f, 920f, 1280f)
        for (index in 0 until widths.size - 1) {
            val (a, b) = widths[index] to widths[index + 1]
            assertTrue("标题：$a → $b", ReaderMenuLayout.panelTitleSp(a) <= ReaderMenuLayout.panelTitleSp(b))
            assertTrue("页码：$a → $b", ReaderMenuLayout.panelPageLabelSp(a) <= ReaderMenuLayout.panelPageLabelSp(b))
            assertTrue(
                "格内页码：$a → $b",
                ReaderMenuLayout.previewPageLabelSp(a) <= ReaderMenuLayout.previewPageLabelSp(b),
            )
        }
    }

    @Test
    fun `页码行高比例不小于一 放大后的数字不被压`() {
        assertTrue(
            "行高比例 ${ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO} 必须 ≥ 1",
            ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= 1f,
        )
        for (width in listOf(320f, 740f)) {
            val page = ReaderMenuLayout.panelPageLabelSp(width)
            val title = ReaderMenuLayout.panelTitleSp(width)
            val label = ReaderMenuLayout.previewPageLabelSp(width)
            assertTrue("页码行高必须 ≥ 字号", page * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= page)
            assertTrue("标题行高必须 ≥ 字号", title * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= title)
            assertTrue("格内页码行高必须 ≥ 字号", label * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= label)
        }
    }

    @Test
    fun `标题上方留白不超过 8dp 且为正`() {
        val top = ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP
        assertTrue("面板顶边到标题行顶的留白 ${top}dp 必须 ≤ 8dp（票 #67 AC2）", top <= 8f)
        assertTrue("标题不得贴死面板顶边（留白为正）", top > 0f)
        assertTrue("必须比改动前的 16dp 小", top < 16f)
    }

    // ---------- 页位口径（AC9）----------

    @Test
    fun `滑块值四舍五入到最近的页`() {
        assertEquals(150, ReaderMenuLayout.seekTargetPage(150.4f, pageCount = 200))
        assertEquals(151, ReaderMenuLayout.seekTargetPage(150.6f, pageCount = 200))
        assertEquals(0, ReaderMenuLayout.seekTargetPage(0f, pageCount = 200))
        assertEquals(199, ReaderMenuLayout.seekTargetPage(199f, pageCount = 200))
    }

    @Test
    fun `滑块值越界夹到首末页`() {
        assertEquals(0, ReaderMenuLayout.seekTargetPage(-12f, pageCount = 200))
        assertEquals(199, ReaderMenuLayout.seekTargetPage(500f, pageCount = 200))
    }

    @Test
    fun `三页书任意滑块值都落到某一页 没有死区`() {
        // 值域 0f..2f（3 页书的末页页位）：任意位置都四舍五入到最近的页，且三页都够得着
        val hits = mutableSetOf<Int>()
        var previous = -1
        for (step in 0..40) {
            val value = step / 20f // 0f..2f
            val page = ReaderMenuLayout.seekTargetPage(value, pageCount = 3)
            assertTrue("滑块值 $value 落到页 $page，必须在 0..2 内", page in 0..2)
            assertTrue("滑块值单调增时目标页不得回退", page >= previous)
            previous = page
            hits += page
        }
        assertEquals("三页都必须能被点到（不是只有最左/最中/最右三个点）", setOf(0, 1, 2), hits)
        // 每一页都覆盖一段**连续的值区间**（点击位置有容差，不必压在像素边界上）
        assertEquals("左段", 0, ReaderMenuLayout.seekTargetPage(0.4f, pageCount = 3))
        assertEquals("中段", 1, ReaderMenuLayout.seekTargetPage(0.6f, pageCount = 3))
        assertEquals("中段", 1, ReaderMenuLayout.seekTargetPage(1.4f, pageCount = 3))
        assertEquals("右段", 2, ReaderMenuLayout.seekTargetPage(1.6f, pageCount = 3))
    }

    @Test
    fun `三页书按下位置超出轨道两端仍夹到首末页`() {
        assertEquals(0, ReaderMenuLayout.seekTargetPage(-0.7f, pageCount = 3))
        assertEquals(2, ReaderMenuLayout.seekTargetPage(2.4f, pageCount = 3))
    }

    @Test
    fun `末页页位 单页书与空书都是 0`() {
        assertEquals(199, ReaderMenuLayout.lastPage(200))
        assertEquals(0, ReaderMenuLayout.lastPage(1))
        assertEquals(0, ReaderMenuLayout.lastPage(0))
        assertEquals(0, ReaderMenuLayout.seekTargetPage(1f, pageCount = 1))
    }

    @Test
    fun `页位夹取在 0 到末页之间`() {
        assertEquals(0, ReaderMenuLayout.clampPage(-3, pageCount = 10))
        assertEquals(9, ReaderMenuLayout.clampPage(99, pageCount = 10))
        assertEquals(5, ReaderMenuLayout.clampPage(5, pageCount = 10))
        assertEquals(0, ReaderMenuLayout.clampPage(3, pageCount = 0))
    }

    @Test
    fun `格上显示的页码是格位加一`() {
        assertEquals(1, ReaderMenuLayout.previewPageLabel(0))
        assertEquals(10, ReaderMenuLayout.previewPageLabel(9))
        for (cell in 0 until 20) {
            assertEquals("格上显示的页码", cell + 1, ReaderMenuLayout.previewPageLabel(cell))
        }
    }

    @Test
    fun `跳页目标与格上显示的页码一致`() {
        // 同一处换算：点击第 1 格跳到页位 0、显示「1」；末页同理
        assertEquals(1, ReaderMenuLayout.previewPageLabel(ReaderMenuLayout.seekTargetPage(0f, pageCount = 200)))
        assertEquals(200, ReaderMenuLayout.previewPageLabel(ReaderMenuLayout.seekTargetPage(199f, pageCount = 200)))
        assertEquals(
            "三页书点最右",
            3,
            ReaderMenuLayout.previewPageLabel(ReaderMenuLayout.seekTargetPage(1.6f, pageCount = 3)),
        )
    }

    @Test
    fun `预览条间隙与原实现同量级`() {
        assertTrue("间隙必须为正（否则格子连成一片）", ReaderMenuLayout.PREVIEW_GAP_DP > 0f)
        assertTrue("间隙不得大于格宽的量级", ReaderMenuLayout.PREVIEW_GAP_DP <= 16f)
    }
}
