package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * 阅读菜单的档位几何用例（本文件即「档位表」）。
 *
 * 每条用例先取一次档位几何（唯一出口 [ReaderMenuLayout.tierGeometry]），再断言它的字段与派生值
 * （面板基础高与占比、行距、标题上留白、滑条行高、预览条保底 / 目标高度 / 高度、固定行合计）。
 * 断言值都是与视口无关的字面量。
 *
 * 覆盖：
 * - 面板高度占比与「三种视口下预览区都吃得到高度」（底部行不被挤出面板）；
 * - 预览条保底按视口分档：只有手机竖屏取 201dp，其余视口一律 80dp（含 599/600dp 宽、440 × 480dp 两条边界）；
 * - 四行结构与固定行合计（标题按实际行数预算、滑条行与底部行各占一行）、底部行上下两段间距；
 * - 矮视口：面板按需加高（52% 公式起点、80dp 预览条保底、80% 屏高上限）；
 * - 档位几何逐档表（[TierExpectation] / [tierExpectations]）：八档逐值。
 *
 * 与档位无关的独立算式（预览项尺寸、三档字号、页位口径、滑条数学与居中偏移）在 `ReaderMenuLayoutTest`。
 *
 * 设备目视与截图（面板高度占比、预览区高度的目视、「不留白 / 水平居中」观感）
 * 不在 JVM 里测，由 `ReaderMenuFooterTest`（底部行几何）与设备验收覆盖。
 */
class ReaderMenuTierGeometryTest {

    /**
     * 该视口的档位几何（唯一出口 [ReaderMenuLayout.tierGeometry]）：本文件的用例只读它的字段与派生方法。
     * [bottomInsetDp] 缺省取沉浸态兜底值（[ReaderOverlayLayout.MIN_BOTTOM_DP]，与生产同一份）。
     */
    private fun tierGeometry(
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        bottomInsetDp: Float = ReaderOverlayLayout.MIN_BOTTOM_DP,
    ): ReaderMenuTierGeometry = ReaderMenuLayout.tierGeometry(viewportWidthDp, viewportHeightDp, bottomInsetDp)

    // ---------- 面板高度与预览区 ----------

    /** 一屏能放几格：预览条宽度 ÷ 单格宽度（含间隙） */
    private fun visibleItems(innerWidthDp: Float, imageHeightDp: Float, aspect: Float): Float {
        val item = ReaderMenuLayout.previewItemWidth(imageHeightDp, aspect)
        return (innerWidthDp + ReaderMenuLayout.PREVIEW_GAP_DP) / (item + ReaderMenuLayout.PREVIEW_GAP_DP)
    }

    /**
     * 标题一行的 dp 高。`sp ≠ dp`：换算在 [ReaderMenuLayout.titleLineHeightDp] 里，
     * [fontScale] 显式传入（1 = 常规字体）。标题的行数上限见 [ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES]
     * （1–3 行、不省略号），行数由 `ReaderMenu` 从 `onTextLayout` 量到后回传。
     */
    private fun titleLine(innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.titleLineHeightDp(innerWidthDp, fontScale)

    /**
     * 预览条高度（dp）= 面板高度 − 固定行合计（档位几何的派生值：[ReaderMenuTierGeometry.previewStripHeightDp]）。
     *
     * 固定行含四项（四行结构）：标题行、**进度条行**（改前它叠在预览条上、不占行）、
     * 底部行、行距与内边距；底部 inset 那一项不能漏：面板整块消费 `readerPanelInsets()`，
     * 而阅读器是沉浸态，底部由 [ReaderOverlayLayout.MIN_BOTTOM_DP]（24dp）兜底。
     */
    private fun previewStripHeight(
        viewportHeightDp: Float,
        innerWidthDp: Float,
        fontScale: Float = 1f,
        lineCount: Int = 1,
    ): Float =
        tierGeometry(viewportWidth(innerWidthDp), viewportHeightDp)
            .previewStripHeightDp(titleLine(innerWidthDp, fontScale), lineCount)

    /**
     * 视口宽（dp）= 面板内宽 + 两侧内边距（预览条保底按视口宽分档，见
     * [ReaderMenuLayout.isPhonePortrait]）。测试里各档的「内宽」都来自设备（365/728/984/812），
     * 加回 2 × 20dp 就是该设备真实的屏宽。
     */
    private fun viewportWidth(innerWidthDp: Float): Float =
        innerWidthDp + ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP * 2

    /**
     * 面板高度（dp）：档位几何的派生值（[ReaderMenuTierGeometry.panelHeightDp]）。
     * [lineCount] 是标题的**量到的行数**（1–3）；矮视口的算例按**两行**建模。
     */
    private fun panelHeight(
        viewportHeightDp: Float,
        innerWidthDp: Float,
        lineCount: Int = 1,
        fontScale: Float = 1f,
    ): Float =
        tierGeometry(viewportWidth(innerWidthDp), viewportHeightDp)
            .panelHeightDp(titleLine(innerWidthDp, fontScale), lineCount)

    /** 矮视口预览条高度（dp）：固定行按**两行**标题建模（矮视口恒走 80dp 档） */
    private fun shortViewportStripHeight(viewportHeightDp: Float, innerWidthDp: Float, fontScale: Float = 1f): Float =
        previewStripHeight(viewportHeightDp, innerWidthDp, fontScale, lineCount = 2)

    /**
     * 页数那一行的 dp 高：字号 sp × 行高比例 × fontScale——
     * 走生产的同一条换算（`Density.toDp()`），因此 fontScale ≠ 1 时两个数字能对上。
     */
    private fun labelHeight(innerWidthDp: Float, fontScale: Float = 1f): Float = with(Density(1f, fontScale)) {
        ReaderMenuLayout.previewLabelHeightSp(ReaderMenuLayout.previewPageLabelSp(innerWidthDp)).sp.toDp().value
    }

    /**
     * 缩略图（图片本体）高度（dp）：预览条高再扣掉页数那一行。
     * 一屏张数由它决定（张数 = 预览条宽度 ÷ 格子宽度，而格子宽度 = 图片高 × 页面比例）。
     */
    private fun imageHeight(viewportHeightDp: Float, innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.previewImageHeightDp(
            previewStripHeight(viewportHeightDp, innerWidthDp, fontScale),
            labelHeight(innerWidthDp, fontScale),
            innerWidthDp,
            ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT,
        )

    /** 三种视口：手机竖屏、平板竖屏、平板横屏（高、面板内宽） */
    private val viewports = listOf(
        "手机竖屏" to (852f to 365f),
        "平板竖屏" to (1024f to 728f),
        "平板横屏" to (768f to 984f),
    )

    @Test
    fun `面板高度是视口高度的四成`() {
        assertEquals(0.4f, ReaderMenuLayout.PANEL_HEIGHT_FRACTION, 0.0001f)
        for ((label, viewport) in viewports) {
            val (height, inner) = viewport
            val geometry = tierGeometry(viewportWidth(inner), height)
            assertEquals("$label：面板应占视口 40%", height * 0.4f, geometry.panelBaseHeightDp, 0.01f)
        }
        // 改动前是「不超过视口 60%」且整体可滚动：现在必须真的统一到 40%
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
     * 手机竖屏一屏张数：一屏约 2.5–3 张（不再是「固定 4 张」）、
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
        // 分档后手机竖屏的实际预览条 = 「基础 40% 扣掉固定行后的余量」（221.9dp，> 保底 201dp）：
        // 等值断言钉的是该档目标高度（同源），下限断言钉的是档位保底本身
        assertEquals(
            "手机竖屏预览条必须等于该档目标高度（基础占比扣掉固定行后的余量）",
            tierGeometry(viewportWidth(inner), height).previewStripTargetDp(titleLine(inner)),
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
     * 平板竖屏一屏张数：原值 3.5，几何修正后为 5.0（容差 0.3 未放宽），理由同手机竖屏那条。
     * **底部行 48→36dp、行距 8→4dp 后变成 4.52**：面板基础占比的余量被放大，
     * 平板竖屏预览条 229.8 → **253.8dp**，图片变大、一屏从 5.01 降到 4.52 张（面板仍是 40%）。
     */
    @Test
    fun `平板竖屏一屏约 4_5 张`() {
        val (height, inner) = viewports[1].second
        val visible = visibleItems(inner, imageHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("平板一屏 $visible 张，必须落在 4.5 ± 0.3（A 档压缩后的实测值）", kotlin.math.abs(visible - 4.5f) <= 0.3f)
    }

    /**
     * 算例：363 × 800dp、内宽 323dp、2:3 页 —— 面板 320dp（40%）、预览条 201.4dp、
     * 缩略图 ≈124.7 × 187.0dp、一屏 ≈2.52 张（目标值是 201 / 124×186 / 2.54，差在整数除法与「页数那一行
     * 按 sp 换算」两处零头）。
     *
     * 与 [viewports] 里那台 405dp 宽机只差内宽：一屏张数**随屏宽变**（这台更窄 ⇒ 2.52 张、405dp 机 2.58 张），
     * 两者都在「2.5–2.8 可接受」区间内（见 `手机竖屏一屏两到三张`）。
     */
    @Test
    fun `票面算例 363 乘 800 内宽 323 面板四成 预览条 201dp`() {
        val height = 800f
        val inner = 323f
        assertEquals("面板 = 40% × 屏高（AC19 的面板 320dp）", 320f, panelHeight(height, inner), 0.05f)
        assertEquals("面板占比 = 40%（裁决 C 后手机竖屏不再被抬到 43.8%）", 0.40f, panelHeight(height, inner) / height, 0.001f)
        assertEquals("预览条 = 201.4dp（AC19 的 201dp）", 201.4f, previewStripHeight(height, inner), 0.01f)
        val image = imageHeight(height, inner)
        assertEquals("缩略图高 ≈187dp（AC19 的 186dp）", 187.0f, image, 0.01f)
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
     * **标题行数只让面板变高，不影响预览条**（「动态加长菜单，不要影响到预览图区域」）。
     *
     * 手机竖屏档：预览条恒等于**该档目标高度** —— 基础 40% 扣掉固定行后的
     * 余量（852dp 机 = 340.8 − 118.9 = 221.9dp），**保底 201dp 在这一档并不生效**（只在余量更小的视口上兜底，
     * 见 `手机竖屏档的保底项在余量更小的视口上生效`）。因此 1/2/3 行标题下预览条逐值相等、一屏张数一样，
     * 面板则逐行变高。本用例钉的就是这两条（等值 + 单调）。
     */
    @Test
    fun `标题变多行只抬高面板不改预览条高度`() {
        val (height, inner) = viewports[0].second
        val stripForLines = (1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES).map { lines ->
            previewStripHeight(height, inner, lineCount = lines)
        }
        val phoneTarget = tierGeometry(viewportWidth(inner), height).previewStripTargetDp(titleLine(inner))
        assertTrue(
            "各行数下预览条 $stripForLines 必须全都等于手机竖屏档目标高度 ${phoneTarget}dp（标题变长不吃预览图）",
            stripForLines.all { kotlin.math.abs(it - phoneTarget) <= 0.01f },
        )
        val panels = (1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES).map { lines ->
            panelHeight(height, inner, lineCount = lines)
        }
        assertTrue("标题每多一行，面板必须跟着变高：$panels", panels[0] < panels[1] && panels[1] < panels[2])
    }

    /**
     * [ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP]（201dp）
     * 必须**真的被用到** —— 手机竖屏档在「基础 40% 扣掉固定行后的余量」**小于**它时取它。
     * 本用例用合成视口把余量压到 71dp（440 × 480dp、内宽 400dp：基础 192、固定行 121），
     * 于是目标高度 = 保底常量本身。
     * 判别力：把该常量调小（如 100f）或让它不再进 [ReaderMenuTierGeometry.previewStripTargetDp] 的 `maxOf`，
     * 下面两条等值断言立刻变红；把 [ReaderMenuTierGeometry.previewStripMinDp] 的分档写反（非手机竖屏档也拿 201）同样会红
     * （本用例同时断言该视口属于手机竖屏档）。
     */
    @Test
    fun `手机竖屏档的保底项在余量更小的视口上生效`() {
        val height = 480f
        val inner = 400f
        assertTrue("440 × 480dp 必须判为手机竖屏档", ReaderMenuLayout.isPhonePortrait(viewportWidth(inner), height))
        val geometry = tierGeometry(viewportWidth(inner), height)
        val fixed = geometry.fixedRowsHeightDp(titleLine(inner))
        val remainder = height * geometry.panelHeightFraction - fixed
        assertTrue(
            "本构造的前提是「余量 $remainder dp < 保底 ${ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP}dp」",
            remainder < ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP,
        )
        val target = geometry.previewStripTargetDp(titleLine(inner))
        assertEquals(
            "余量更小时预览条目标高度必须取手机竖屏档保底常量本身",
            ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP,
            target,
            0.01f,
        )
        assertEquals("该视口下的预览条（面板 − 固定行）也必须等于保底常量", ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP, previewStripHeight(height, inner), 0.01f)
        assertEquals(
            "手机竖屏档的保底常量就是 201dp（AC19）",
            201f,
            ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP,
            0.01f,
        )
    }

    @Test
    fun `标题行数夹在 1 到 3 行`() {
        // 短标题 1 行、超长最多 3 行（不省略号），超过上限的行数一律夹回 3
        assertEquals(10f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 0), 0.01f)
        assertEquals(10f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 1), 0.01f)
        assertEquals(30f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 3), 0.01f)
        assertEquals("超过 3 行一律夹回 3 行（不省略号，只是不再加高面板）", 30f, ReaderMenuLayout.titleHeightDp(lineHeightDp = 10f, lineCount = 9), 0.01f)
    }

    @Test
    fun `滑动条是 2dp 细线加 8dp 圆球 滑条行按档取高`() {
        // 学 PV 做成「一条线 + 一个圆球」，命中行仍要 48dp（非手机竖屏档，改动前口径）
        assertEquals(2f, ReaderMenuLayout.SLIDER_TRACK_HEIGHT_DP, 0.01f)
        assertEquals(8f, ReaderMenuLayout.SLIDER_THUMB_DIAMETER_DP, 0.01f)
        assertEquals("非手机竖屏档（平板/矮视口）保持 48dp，逐像素不变", 48f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP, 0.01f)
        assertEquals("第 13 轮 AC19：手机竖屏档压到 28dp", 28f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_PHONE_PORTRAIT_DP, 0.01f)
        assertEquals("档位取值：手机竖屏 → 28dp", 28f, tierGeometry(405f, 852f).sliderBandHeightDp, 0.01f)
        assertEquals("档位取值：其余视口 → 48dp", 48f, tierGeometry(768f, 1024f).sliderBandHeightDp, 0.01f)
        assertTrue("圆球必须比线粗（否则看不出来）", ReaderMenuLayout.SLIDER_THUMB_DIAMETER_DP > ReaderMenuLayout.SLIDER_TRACK_HEIGHT_DP)
    }

    @Test
    fun `两行书名下平板竖屏一屏仍约 4_5 张`() {
        // 标题行数**不改**预览条，因此两行书名下的一屏张数与一行完全相同
        // （旧口径下它是 5.77 张——那是「行数吃掉预览条」的算法）；
        // 底部行与行距压缩后平板竖屏预览条抬到 253.8dp，数值随之落到 4.52（容差 0.3 未放宽）
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

    @Test
    fun `进度条独占一行 不遮挡缩略图`() {
        // 改前滑动条叠在预览条下缘（48dp 高）——设备截图里它盖住了缩略图；
        // 旧断言是「遮挡 ≤ 25%」，四行结构下遮挡恒为 0，这里给它的**等价替代**：
        // 面板内容高 = 标题行 + 预览条 + 进度条行 + 底部行 + 行距 + 内边距 —— 每一项都在测试里独立写出，
        // 因此把进度条行算漏/算重（或又把它塞回预览条）这条等式就穿。
        for ((label, viewport) in viewports) {
            val (height, inner) = viewport
            val panel = panelHeight(height, inner)
            val strip = previewStripHeight(height, inner)
            // 几何按档：消费底部 inset 的档把 24dp 兜底算进固定行，滑条行高与底内边距都读几何
            val geometry = tierGeometry(viewportWidth(inner), height)
            val chrome = (if (geometry.consumesBottomInset) ReaderOverlayLayout.MIN_BOTTOM_DP else 0f) +
                geometry.panelBottomPaddingDp +
                geometry.titleTopPaddingDp
            val gaps = geometry.rowGapDp * 3
            val rows = titleLine(inner) + geometry.sliderBandHeightDp +
                ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP
            // **本用例的唯一判据**：面板内容高展开成四条固定行 + 行距 + 内边距 + 预览条。
            // chrome / rows / gaps 都在测试里独立写出（不读几何的固定行合计），因此把进度条行算漏、
            // 算重、或又把它塞回预览条，这条等式就穿。
            assertEquals(
                "$label：面板内容高必须 = 预览条 + 四条固定行 + 行距 + 内边距",
                panel,
                chrome + rows + gaps + strip,
                0.01f,
            )
        }
        // 页数那一行**真的**占掉预览条的高度：行高为正、且严格小于预览条高。
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
        // 底部行**可见**高 48 → 36dp（省出的高度给预览条），但触区不许缩：
        // 上一本/下一本那两列的可点高度仍是 48dp（触摸目标下限，命中区溢出到行外）
        assertEquals(36f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        assertEquals(48f, ReaderMenuLayout.PANEL_FOOTER_HIT_HEIGHT_DP, 0.01f)
        assertEquals(48f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP, 0.01f)
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
        // 命中带几何（「只向下挂」后的实情，改成真算式）：
        //   命中带 = [行顶, 行顶 + 48dp] ⇒ 向上溢出 0（这一条的行为守卫在 `ReaderMenuFooterTest`：
        //   行顶上方 1dp / 5dp 点不中）、向下溢出 = 48 − 36 = 12dp。
        //   行下方空白 = 底部内边距 P + 面板必然扣掉的底部 inset（沉浸态下限 24dp）⇒ inset = 24 时 P = 4，
        //   即 12dp 里 **8dp 伸进底部 inset（系统手势带）**。口径集不可满足：要让 12dp 全落在内边距里得
        //   `P ≥ 48 − 36 = 12dp`，而 `P = 28 − inset = 4`（行距在底部行**上方**，不进这条式子）。
        //   命中带恒 48dp，越界量由设备目视判。
        // 下面两条都读真实算式 ⇒ HIT / 行高 / P 任一改动都会变红（旧版用「对称溢出 (48−36)/2」建模，恒绿）。
        // 非手机竖屏档取平板竖屏（768 × 1024）：行距与底内边距只看档位，与视口宽无关
        val geometry = tierGeometry(768f, 1024f)
        val rowGap = geometry.rowGapDp
        val bottomPadding = geometry.panelBottomPaddingDp
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
     * 底部行的**上下间距必须相等**（口径是「中心到中心」）：
     * 上间距 = 滑条行高/2 + 行距 + 底部行可见高/2；下间距 = 底部行可见高/2 + 底部内边距 + 实际底部 inset。
     * [ReaderMenuTierGeometry.panelBottomPaddingDp] 就是解出「两者相等」的那个内边距（纯函数），
     * 并在解为负数时回落到下限 4dp。四档 inset 逐档复算这条等式（测试自己写两个间距，不读被测函数的中间量）。
     */
    @Test
    fun `非手机竖屏档底部行上下间距相等 四档 inset`() {
        assertEquals("补记 8 ③ 的下限", 4f, ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP, 0.01f)
        for (inset in listOf(0f, 12.6f, 24f, 48f)) {
            val geometry = tierGeometry(768f, 1024f, inset)
            val gap = geometry.rowGapDp
            val footerHalf = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP / 2f
            val padding = geometry.panelBottomPaddingDp
            val spacingAbove = ReaderMenuLayout.SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP / 2f + gap + footerHalf
            val spacingBelow = footerHalf + padding + inset
            assertTrue("inset $inset：底部内边距 $padding 不得低于下限", padding >= ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP - 0.01f)
            if (inset <= ReaderMenuLayout.SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP / 2f + gap - ReaderMenuLayout.PANEL_BOTTOM_PADDING_MIN_DP) {
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
        // 改动前是定值 4dp + 行距 8：inset 24 时相差 16dp（56.5 vs 40.6），本函数把它收干
        assertEquals(
            "矮视口（行距 0）下也收干：24 + 0 − 24 = 0 → 下限 4",
            4f,
            tierGeometry(852f, 360f, 24f).panelBottomPaddingDp,
            0.01f,
        )
        assertEquals(
            "无 inset 时行距全给底部内边距",
            28f,
            tierGeometry(768f, 1024f, 0f).panelBottomPaddingDp,
            0.01f,
        )
    }

    /**
     * **手机竖屏档**：底部行**上下两段各 36dp** ——
     * 上段 = 滑条行半高 14 + 行距 4 + 底行半高 18；下段 = 底行半高 18 + 面板底内边距 **18** + 实际底部 inset **0**
     * （面板不消费底部 inset，所以判据里那一项为 0）。两段都读真实算式：把滑条行改回 48dp、
     * 或让面板重新消费 inset，本用例立刻变红。
     * 矮视口（行距 0）属**非手机竖屏档**，其两段值由上面那条用例的 inset 档位覆盖（24 + 0 + 18 = 42 / 18 + P + inset）。
     */
    @Test
    fun `手机竖屏档底部行上下两段各 36dp`() {
        val geometry = tierGeometry(405f, 852f, 24f)
        val gap = geometry.rowGapDp
        val sliderHalf = geometry.sliderBandHalfHeightDp
        val footerHalf = ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP / 2f
        assertEquals("手机竖屏档滑条行半高 = 14dp", 14f, sliderHalf, 0.01f)
        val padding = geometry.panelBottomPaddingDp
        assertEquals("手机竖屏档底部内边距 = 滑条行半高 + 行距 = 18dp（不扣 inset）", 18f, padding, 0.01f)
        val spacingAbove = sliderHalf + gap + footerHalf
        val spacingBelow = footerHalf + padding + 0f
        assertEquals("上段 = 14 + 4 + 18 = 36dp", 36f, spacingAbove, 0.01f)
        assertEquals("下段 = 18 + 18 + 0 = 36dp", 36f, spacingBelow, 0.01f)
        assertEquals("两段必须相等", spacingAbove, spacingBelow, 0.01f)
        // inset 真值对手机竖屏档**不影响结果**（面板不消费它）——这一条也是「分档」的守卫
        assertEquals(
            "手机竖屏档的内边距与 inset 无关（inset 从 0 换到 48dp 仍 18dp）",
            tierGeometry(405f, 852f, 0f).panelBottomPaddingDp,
            tierGeometry(405f, 852f, 48f).panelBottomPaddingDp,
            0.01f,
        )
    }

    @Test
    fun `预览区高度必须扣掉面板必然占用的底部 inset`() {
        // 沉浸态下面板底部恒有一份 inset 兜底（ReaderOverlayLayout.MIN_BOTTOM_DP）：
        // 漏掉它算出来的预览区会高 24dp、一屏张数会少 ~15%。
        // 分档：**手机竖屏档例外**（面板不消费底部 inset）⇒ 只对非手机竖屏档成立，
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
        // 手机竖屏档：固定行**不含**底部 inset —— 这一条是可失败的：
        // 把 `resolveTierGeometry` 里的 `if (phonePortrait) 0f else bottomInsetDp` 删掉、或让手机竖屏档
        // 重新消费 inset，下面两条等值断言立刻变红（同一档传 0 / 24dp 两个实参，结果必须逐值相等）
        val (phoneHeight, phoneInner) = viewports[0].second
        val phoneTitle = titleLine(phoneInner)
        val phoneWithoutInset = tierGeometry(viewportWidth(phoneInner), phoneHeight, 0f).fixedRowsHeightDp(phoneTitle)
        val phoneWithInset = tierGeometry(viewportWidth(phoneInner), phoneHeight, 24f).fixedRowsHeightDp(phoneTitle)
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
            0.01f,
        )
        // 反方向守卫：非手机竖屏档**必须**消费 inset —— 判据是「底部 inset 与内边距之和」：
        // inset ≤ 滑条行半高 + 行距（28dp）时内边距把它吸收掉（和恒为 28dp），inset 再大则内边距落到下限 4dp、
        // 和随 inset 增长。把 else 分支的 `- bottomInsetDp` 删掉、或误用手机竖屏档分支，下面两条都红。
        val tabletInset0 = tierGeometry(768f, 1024f, 0f).fixedRowsHeightDp(phoneTitle)
        val tabletInset24 = tierGeometry(768f, 1024f, 24f).fixedRowsHeightDp(phoneTitle)
        val tabletInset48 = tierGeometry(768f, 1024f, 48f).fixedRowsHeightDp(phoneTitle)
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

    // ---------- 矮视口（横屏手机）版式 ----------

    /** 矮视口（841×393dp 横屏手机的可用高按口径取 360dp）与两个必须不受影响的视口 */
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
        // 固定行按两行标题预算：内宽 812dp ⇒ 标题一行 28.8dp × 2 = 57.6dp，
        // 固定行 = 24(inset) + 4 + 0 + 57.6 + 0 + 48(进度条行) + 36(底部行) = 169.6dp。
        // 360dp 视口：固定行 + 80dp = 249.6dp（= 69.3%）≤ 80% × 360 = 288dp ⇒ **保底项先生效**，
        // 预览条足 80dp（改动前 66% 上限时会掉到 68dp —— 上限已放到 80%）。
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
        // 保底项按视口分档：480–700dp 高的横屏设备
        // （1024×600 平板、480–520dp 档）此前走「恒 40%」，四条固定行把预览条压到 0–34dp（480–520dp 下为负），
        // 保底项把他们兜到 80dp —— 但**不再**跟着手机竖屏拿大预览（那会把面板抬到 68–80% 屏高，与「约 40%」冲突）。
        // 底部行 48→36、行距 8→4 后固定行（两行标题）= 24 + 4 + 3 + 57.6 + 12 + 48 + 36 = 184.6dp：
        //   480dp：保底需求 184.6 + 80 = 264.6 > 40% × 480 = 192 ⇒ 面板 264.6dp（55.1%）、预览条 80dp
        //   600dp：基础余量 240 − 155.8 = 84.2 > 80 ⇒ 面板 268.8dp（44.8%）、预览条 84.2dp
        for ((label, height, inner, expectedPanel, expectedStrip) in listOf(
            listOf("480dp 横屏", 480f, 812f, 264.6f, 80f),
            listOf("600dp 横屏（1024×600）", 600f, 984f, 268.8f, 84.2f),
        )) {
            val h = height as Float
            val w = viewportWidth(inner as Float)
            val strip = tierGeometry(w, h).previewStripHeightDp(titleLine(inner), 2)
            assertEquals(
                "$label：预览条必须 ≥ 80dp 档位下限（不得再是 0–34dp / 负数）",
                expectedStrip as Float,
                strip,
                0.01f,
            )
            assertTrue("$label：预览条 $strip dp 不得低于 80dp 保底", strip >= ReaderMenuLayout.PREVIEW_STRIP_MIN_OTHER_VIEWPORT_DP - 0.01f)
            val panel = tierGeometry(w, h).panelHeightDp(titleLine(inner), 2)
            assertEquals("$label：面板高度（A 档压缩后的实测值）", expectedPanel as Float, panel, 0.01f)
            assertTrue("$label：面板占比 ${panel / h} 必须 ≥ 40% 且 ≤ 80%", panel / h >= 0.4f - 0.001f && panel / h <= 0.8f + 0.001f)
        }
    }

    /**
     * **任何视口**下标题 1→3 行都只抬高面板，
     * 预览条高度**逐像素相等**（旧口径下平板竖屏 229.8→172.2dp、平板横屏 127.4→80.0dp 都会缩）。
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
            val target = tierGeometry(viewportWidth(inner), height).previewStripTargetDp(titleLine(inner))
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
        // 分档的来由：把「大预览」施加到「所有非矮视口」会把 480/600dp 高横屏抬到 80%/68.1%、
        // 平板横屏抬到 53%，与「约 40%」「竖屏与平板占比逐像素一致」冲突。
        // 本用例把分档钉死：只有手机竖屏取 201dp、其余视口一律 80dp。
        assertTrue("手机竖屏（视口 405 × 852）必须是「手机竖屏」档", ReaderMenuLayout.isPhonePortrait(405f, 852f))
        assertEquals(
            "手机竖屏档的保底 = 201dp（第 13 轮 AC19：224 → 201）",
            ReaderMenuLayout.PREVIEW_STRIP_MIN_PHONE_PORTRAIT_DP,
            tierGeometry(405f, 852f).previewStripMinDp,
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
                tierGeometry(width, height).previewStripMinDp,
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
        // 判据 ①：手机竖屏 —— 预览条 = 基础 40% 扣掉固定行
        // 后的余量（221.9dp ≥ 201dp 保底）⇒ 面板恰好是 40%（不再被抬到 43.8%）；固定行含 28dp 滑条行、
        // 不含底部 inset
        val phoneHeight = 852f
        val phoneInner = 365f
        val phoneTitle = ReaderMenuLayout.titleHeightDp(titleLine(phoneInner), 1)
        val phoneFixed = tierGeometry(viewportWidth(phoneInner), phoneHeight).fixedRowsHeightDp(phoneTitle)
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
            0.01f,
        )
        assertEquals(
            "手机竖屏预览条 = 40% 扣掉固定行后的余量（221.9dp）",
            221.9f,
            previewStripHeight(phoneHeight, phoneInner),
            0.01f,
        )
        // 判据 ②：平板竖屏 / 平板横屏 —— **一行标题**时面板仍是 40%（等值断言，未放宽），
        // 标题变 2/3 行只把面板往上长，且始终不越 80% 屏高。
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
        // “52% 起点”必须真的在代码里。用**合成输入**把保底项压小
        // （标题 10dp、无底部 inset）：底部内边距 = max(4, 24 + 0 − 0) = 24dp，固定行 = 24 + 10 + 48 + 36 = 118dp，
        // 保底项 = max(80, 208 − 118 = 90) = 90dp ⇒ 此时面板取 52% 的值（208dp）；若代码误用 40%（= 160dp）本断言变红。
        val panel = tierGeometry(viewportWidth(812f), 400f, 0f).panelHeightDp(10f, 1)
        assertEquals(400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT, panel, 0.01f)
        assertTrue("52% 起点必须高于 40% 的旧值", panel > 400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION)
    }

    @Test
    fun `矮视口固定行已压扁 标题不留白行距为零`() {
        val geometry = tierGeometry(852f, 360f)
        assertTrue("矮视口标题不留上侧留白", geometry.titleTopPaddingDp == 0f)
        assertTrue("矮视口行距为零", geometry.rowGapDp == 0f)
        // 底部行不再分「矮视口 36 / 其余 48」两个值：全视口统一到 36dp（可见高）
        assertEquals(36f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        // 进度条行：**矮视口属「非手机竖屏档」**，分档后仍保持 48dp 触摸目标下限（改动前口径）
        assertEquals(48f, geometry.sliderBandHeightDp, 0.01f)
    }

    @Test
    fun `非矮视口的固定行尺寸按 A 档统一 标题留白不缩`() {
        // 底部行 36dp、行距 4dp 对**所有视口**生效；矮视口额外把标题留白与行距压到 0。
        // 这里钉住非矮视口的三个尺寸，并验证安卓平板两档的面板仍由基础占比兜底（逐像素不变）。
        for ((label, height) in tallViewports) {
            val panel = panelHeight(height, 400f)
            val geometry = tierGeometry(viewportWidth(400f), height)
            assertTrue("$label：面板必须 ≥ 四成基线（保底项只会抬高）", panel >= height * 0.4f - 0.01f)
            assertEquals("$label：标题留白不缩", ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP, geometry.titleTopPaddingDp, 0.01f)
            assertEquals("$label：行距 = A 档的 4dp", 4f, geometry.rowGapDp, 0.01f)
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
        // 这是「极小屏保不住 80dp、但也不让面板吃掉整屏」的兜底场合。
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

    // ---------- 档位几何（纯重构，逐档逐值不变）----------

    /**
     * 档位几何的**期望值一行**（「行为不变」逐值表）。
     * 每一列都独立写出字面值：不改读被测函数、不复用它们的算式。
     */
    private data class TierExpectation(
        val label: String,
        val viewportWidthDp: Float,
        val viewportHeightDp: Float,
        val bottomInsetDp: Float,
        val phonePortrait: Boolean,
        val shortViewport: Boolean,
        val panelHeightFraction: Float,
        val rowGapDp: Float,
        val titleTopPaddingDp: Float,
        val sliderBandHeightDp: Float,
        val previewStripMinDp: Float,
        val panelBaseHeightDp: Float,
        val panelBottomPaddingDp: Float,
        /** 固定行里除标题以外的部分（dp）：`(手机竖屏 ? 0 : inset) + 底内边距 + 标题上留白 + 行距 × 3 + 滑条行 + 底部行 36` */
        val fixedChromeHeightDp: Float,
    )

    /**
     * 八档几何（手机竖屏 363×800 / 405×852 · 平板竖屏 · 平板横屏 · 480dp 高横屏 · 600dp 高横屏 ·
     * 矮视口 360dp · 矮视口 fontScale 1.4）逐档的档位几何期望值。
     *
     * 矮视口 fontScale 1.4 与矮视口 360dp 是同一组几何值——fontScale 只进标题行高（`titleLineHeightDp`），
     * **不进**档位几何；这一条正是「字体放大不得被当成换档」的守卫。
     */
    private fun tierExpectations(): List<TierExpectation> = listOf(
        TierExpectation("手机竖屏 363×800", 363f, 800f, ReaderOverlayLayout.MIN_BOTTOM_DP, true, false, 0.4f, 4f, 3f, 28f, 201f, 320f, 18f, 97f),
        TierExpectation("手机竖屏 405×852", 405f, 852f, ReaderOverlayLayout.MIN_BOTTOM_DP, true, false, 0.4f, 4f, 3f, 28f, 201f, 340.8f, 18f, 97f),
        // 手机竖屏档不消费底部 inset ⇒ inset 从 24dp 换到 48dp，本行每一列都必须与上一行相同
        TierExpectation("手机竖屏 405×852（底部 inset 48dp）", 405f, 852f, 48f, true, false, 0.4f, 4f, 3f, 28f, 201f, 340.8f, 18f, 97f),
        TierExpectation("平板竖屏 768×1024", 768f, 1024f, ReaderOverlayLayout.MIN_BOTTOM_DP, false, false, 0.4f, 4f, 3f, 48f, 80f, 409.6f, 4f, 127f),
        TierExpectation("平板横屏 1024×768", 1024f, 768f, ReaderOverlayLayout.MIN_BOTTOM_DP, false, false, 0.4f, 4f, 3f, 48f, 80f, 307.2f, 4f, 127f),
        // 480dp 高是矮视口阈值的**边界外**（判据是「可用高 < 480dp」）⇒ 仍走常规档
        TierExpectation("480dp 高横屏 852×480", 852f, 480f, ReaderOverlayLayout.MIN_BOTTOM_DP, false, false, 0.4f, 4f, 3f, 48f, 80f, 192f, 4f, 127f),
        TierExpectation("600dp 高横屏 1024×600", 1024f, 600f, ReaderOverlayLayout.MIN_BOTTOM_DP, false, false, 0.4f, 4f, 3f, 48f, 80f, 240f, 4f, 127f),
        TierExpectation("矮视口 360dp 852×360", 852f, 360f, ReaderOverlayLayout.MIN_BOTTOM_DP, false, true, 0.52f, 0f, 0f, 48f, 80f, 187.2f, 4f, 112f),
        TierExpectation("矮视口 360dp fontScale 1.4（几何同上一行）", 852f, 360f, ReaderOverlayLayout.MIN_BOTTOM_DP, false, true, 0.52f, 0f, 0f, 48f, 80f, 187.2f, 4f, 112f),
    )

    /**
     * 主用例：**档位几何逐档逐值等于改动前**（「每一档的取值与改动前逐值相等」）。
     *
     * 判别力（「改哪一处会红」，判据写在断言上、不靠注释）：
     * - 把 [ReaderMenuTierGeometry.sliderBandHeightDp] 的非手机竖屏档改成 28dp（「分档漏改一处
     *   ⇒ 其它视口被带跑」）⇒ 五项非手机竖屏档的「滑条行高」「底部内边距」两列变红；
     * - 把 [ReaderMenuTierGeometry.rowGapDp] / [ReaderMenuTierGeometry.titleTopPaddingDp]
     *   的矮视口支去掉 ⇒ 矮视口两行的对应列变红；
     * - 把 [ReaderMenuTierGeometry.previewStripMinDp] 的分档写反（非手机竖屏也拿 201dp）⇒
     *   五项非手机竖屏档的「预览条保底」列变红；
     * - 让手机竖屏档重新消费底部 inset ⇒ 上面第 3 行（inset 48dp）与第 2 行不再相等、变红；
     * - 换掉 [ReaderMenuTierGeometry.panelHeightFraction] / [ReaderMenuTierGeometry.panelBaseHeightDp]
     *   任一档 ⇒ 表里「占比」「面板基础高」两列变红（矮视口那两行尤其）。
     */
    @Test
    fun `档位几何逐档取值与改动前逐值相等`() {
        for (e in tierExpectations()) {
            val geometry = ReaderMenuLayout.tierGeometry(e.viewportWidthDp, e.viewportHeightDp, e.bottomInsetDp)
            assertEquals("${e.label}：手机竖屏判据", e.phonePortrait, geometry.phonePortrait)
            assertEquals("${e.label}：矮视口判据", e.shortViewport, geometry.shortViewport)
            assertEquals("${e.label}：是否消费底部 inset（手机竖屏档不消费）", !e.phonePortrait, geometry.consumesBottomInset)
            assertEquals("${e.label}：面板占比", e.panelHeightFraction, geometry.panelHeightFraction, 0.0001f)
            assertEquals("${e.label}：面板基础高", e.panelBaseHeightDp, geometry.panelBaseHeightDp, 0.01f)
            assertEquals("${e.label}：行距", e.rowGapDp, geometry.rowGapDp, 0.01f)
            assertEquals("${e.label}：标题上留白", e.titleTopPaddingDp, geometry.titleTopPaddingDp, 0.01f)
            assertEquals("${e.label}：滑条行高", e.sliderBandHeightDp, geometry.sliderBandHeightDp, 0.01f)
            assertEquals("${e.label}：预览条保底", e.previewStripMinDp, geometry.previewStripMinDp, 0.01f)
            assertEquals("${e.label}：面板底内边距", e.panelBottomPaddingDp, geometry.panelBottomPaddingDp, 0.01f)
        }
    }

    /**
     * 档位几何的**固定行 / 面板 / 预览条**三条派生口径等于**字面期望值**。
     *
     * 期望值由 [tierExpectations] 的**字面表**按规格公式算出（`fixedChromeHeightDp` 列是逐档字面值，
     * 其余列都被上面那条用例用字面值钉住），实际值取几何对象的派生方法：两侧不同源，任一侧被单独改动都会红。
     *
     * 判别力：把 `fixedChromeHeightDp` 的「底部 inset 只有非手机竖屏档消费」那一项写反（手机竖屏也扣 inset）
     * ⇒ 手机竖屏三行的固定行合计 / 面板高 / 预览条高三列一起红；把 `previewStripTargetDp` 的 `maxOf` 取成
     * `minOf` ⇒ 预览条目标高度与面板高变红；把 `panelHeightCapDp` 的 0.8 改成 0.6 ⇒ 手机竖屏与平板竖屏的面板高变红。
     */
    @Test
    fun `档位几何的固定行与高度口径等于字面期望值`() {
        for (e in tierExpectations()) {
            val geometry = ReaderMenuLayout.tierGeometry(e.viewportWidthDp, e.viewportHeightDp, e.bottomInsetDp)
            val innerWidth = e.viewportWidthDp - ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP * 2
            val lineHeight = titleLine(innerWidth)
            val cap = e.viewportHeightDp * 0.8f
            // 预览条目标高度只由档位决定（基准取一行标题的余量）
            val target = maxOf(e.previewStripMinDp, e.panelBaseHeightDp - (e.fixedChromeHeightDp + lineHeight))
            for (lineCount in 1..ReaderMenuLayout.READER_MENU_TITLE_MAX_LINES) {
                val fixedRows = e.fixedChromeHeightDp + lineHeight * lineCount
                val panel = maxOf(e.panelBaseHeightDp, fixedRows + target).coerceAtMost(cap)
                assertEquals(
                    "${e.label}：${lineCount} 行标题的固定行合计",
                    fixedRows,
                    geometry.fixedRowsHeightDp(lineHeight * lineCount),
                    0.01f,
                )
                assertEquals(
                    "${e.label}：${lineCount} 行标题的面板高度",
                    panel,
                    geometry.panelHeightDp(lineHeight, lineCount),
                    0.01f,
                )
                assertEquals(
                    "${e.label}：${lineCount} 行标题的预览条高度",
                    panel - fixedRows,
                    geometry.previewStripHeightDp(lineHeight, lineCount),
                    0.01f,
                )
            }
            assertEquals(
                "${e.label}：预览条目标高度（与行数无关）",
                target,
                geometry.previewStripTargetDp(lineHeight),
                0.01f,
            )
        }
    }
}
