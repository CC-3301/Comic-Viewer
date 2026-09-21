package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.ui.ENTRY_NAME_MAX_LINES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * 阅读菜单的布局口径（票 #105 一次重定 #42/#62/#65/#66/#67）。
 *
 * 覆盖：
 * - 面板高度占比（AC4）与「三种视口下预览区都吃得到高度」（AC5：底部行不被挤出面板）；
 * - 预览项尺寸（AC1/AC3）：高度撑满预览条减页数那一行、宽度 = 高度 × 该页真实比例、一屏几格由屏幕宽度决定
 *   （**实测值 3.4 张 / 5.0 张**，容差 ±0.3——票面的 2.5 / 3.5 是「滑动条叠放 + 页数叠在图上」
 *   那个已被 D2-A/D3-A 推翻的几何下的数字，见 evidence-impl.md 与 `ReaderMenuLayout` 头部说明）；
 * - 四行结构（批次 6 AC13）：进度条**独占一行**、不在预览条里（遮挡恒 0，旧「遮挡 ≤25%」预算作废）；
 * - 矮视口（批次 6 AC11 + 裁定 A）：面板按需加高（52% 公式起点、**80dp 预览条保底**、80% 屏高上限），
 *   固定行按**两行标题**预算（裁定 A：不截断、不省略号）；
 * - 三档字号（AC6）：票面表的 `内宽 × 0.05 / 0.05 / 0.035` 与 18–24 / 16–24 / 12–16sp 上下限，
 *   不变量 **标题 ≥ 页码 > 格内页码**；
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
     * [fontScale] 显式传入（1 = 常规字体）。标题**所有视口都是「最多两行、不省略号」**（裁定 A），
     * 面板的固定行按两行预算，见 [titleHeight]。
     */
    private fun titleLine(innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.titleLineHeightDp(innerWidthDp, fontScale)

    /**
     * 预览条高度（dp）= 面板高度 − 固定行合计（生产口径：[ReaderMenuLayout.previewStripHeightDp]）。
     *
     * 固定行含四项（票 #105 批次 6 AC13 的四行结构）：标题行、**进度条行**（改前它叠在预览条上、不占行）、
     * 底部行、行距与内边距；底部 inset 那一项不能漏：面板用 `windowInsetsPadding`（逐行加），
     * 而阅读器是沉浸态，底部由 [ReaderOverlayLayout.MIN_BOTTOM_DP]（24dp）兜底。
     */
    private fun previewStripHeight(viewportHeightDp: Float, innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.previewStripHeightDp(
            viewportHeightDp,
            titleLine(innerWidthDp, fontScale),
            ReaderOverlayLayout.MIN_BOTTOM_DP,
        )

    /**
     * 标题**全部行**的总高（dp）：裁定 A 之后矮视口不截断、不省略号，面板的固定行必须按**两行**预算
     * （与 `ReaderMenu` 生产调用点同一条算式：`titleLineHeightDp × ENTRY_NAME_MAX_LINES`）。
     * 只有矮视口（AC11）的算例用它；张数算例（AC1）用一行标题那一档——那是票面 3.4 / 5.0 的来源口径。
     */
    private fun titleHeight(innerWidthDp: Float, fontScale: Float = 1f): Float =
        titleLine(innerWidthDp, fontScale) * ENTRY_NAME_MAX_LINES

    /** 矮视口预览条高度（dp）：固定行按两行标题预算（裁定 A 的面板口径） */
    private fun shortViewportStripHeight(viewportHeightDp: Float, innerWidthDp: Float, fontScale: Float = 1f): Float =
        ReaderMenuLayout.previewStripHeightDp(
            viewportHeightDp,
            titleHeight(innerWidthDp, fontScale),
            ReaderOverlayLayout.MIN_BOTTOM_DP,
        )

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
     * 手机竖屏一屏张数：**AC1 原值 2.5**，本批次因 AC13（进度条独占一行）+ AC14（页数占一行）
     * 两条独占行把预览条吃掉了 48 + 8 + 15dp，几何上必然变大（容差 0.3 未放宽）。
     * 维护者的方案图把这两条假设成 12dp / 0dp，实际是 48dp / 15dp；差值写进 evidence-impl.md。
     */
    @Test
    fun `手机竖屏一屏约 3_4 张`() {
        val (height, inner) = viewports[0].second
        val visible = visibleItems(inner, imageHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("手机一屏 $visible 张，必须落在 3.4 ± 0.3（AC1 几何修正值）", kotlin.math.abs(visible - 3.4f) <= 0.3f)
    }

    /** 平板竖屏一屏张数：AC1 原值 3.5，几何修正后为 5.0（容差 0.3 未放宽），理由同手机竖屏那条 */
    @Test
    fun `平板竖屏一屏约 5 张`() {
        val (height, inner) = viewports[1].second
        val visible = visibleItems(inner, imageHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("平板一屏 $visible 张，必须落在 5.0 ± 0.3（AC1 几何修正值）", kotlin.math.abs(visible - 5.0f) <= 0.3f)
    }

    /**
     * 两行书名下的张数（票 #105 AC1，r4 补齐）：书名叫两行时固定行多一整行标题，
     * 预览条随之变矮、一屏张数变大——**用同一个几何模型算**（[titleHeight] + [labelHeight] + [previewStripHeight]），
     * 不硬凑票面数字。票面值：手机竖屏 3.98、平板竖屏 5.77。
     */
    @Test
    fun `两行书名下手机竖屏一屏约 3_98 张`() {
        val (height, inner) = viewports[0].second
        val strip = ReaderMenuLayout.previewStripHeightDp(height, titleHeight(inner), ReaderOverlayLayout.MIN_BOTTOM_DP)
        val image = ReaderMenuLayout.previewImageHeightDp(
            strip,
            labelHeight(inner),
            inner,
            ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT,
        )
        val visible = visibleItems(inner, image, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("两行书名下手机一屏 $visible 张，必须落在 3.98 ± 0.3（AC1 两行档）", kotlin.math.abs(visible - 3.98f) <= 0.3f)
        assertTrue("两行书名下张数必须多于单行（固定行多一行标题）", visible > visibleItems(inner, imageHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT))
    }

    @Test
    fun `两行书名下平板竖屏一屏约 5_77 张`() {
        val (height, inner) = viewports[1].second
        val strip = ReaderMenuLayout.previewStripHeightDp(height, titleHeight(inner), ReaderOverlayLayout.MIN_BOTTOM_DP)
        val image = ReaderMenuLayout.previewImageHeightDp(
            strip,
            labelHeight(inner),
            inner,
            ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT,
        )
        val visible = visibleItems(inner, image, ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("两行书名下平板一屏 $visible 张，必须落在 5.77 ± 0.3（AC1 两行档）", kotlin.math.abs(visible - 5.77f) <= 0.3f)
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
        // 票 #105 批次 6 AC13：改前滑动条叠在预览条下缘（48dp 高）——真机 18.jpg 里它盖住了缩略图；
        // 旧断言是「遮挡 ≤ 25%」，四行结构下遮挡恒为 0，这里给它的**等价替代**：
        // 面板内容高 = 标题行 + 预览条 + 进度条行 + 底部行 + 行距 + 内边距 —— 每一项都在测试里独立写出，
        // 因此 `fixedRowsHeightDp` 若把进度条行算漏/算重（或又把它塞回预览条）这条等式就穿。
        for ((label, viewport) in viewports) {
            val (height, inner) = viewport
            val short = ReaderMenuLayout.isShortViewport(height)
            val panel = ReaderMenuLayout.panelHeightDp(height, titleLine(inner), ReaderOverlayLayout.MIN_BOTTOM_DP)
            val strip = previewStripHeight(height, inner)
            val chrome = ReaderOverlayLayout.MIN_BOTTOM_DP + ReaderMenuLayout.PANEL_BOTTOM_PADDING_DP +
                ReaderMenuLayout.panelTitleTopPaddingDp(short)
            val gaps = ReaderMenuLayout.panelRowGapDp(short) * 3
            val rows = titleLine(inner) + ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP +
                ReaderMenuLayout.panelFooterHeightDp(short)
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
    fun `底部行与滑动条都是 48dp 的触摸目标`() {
        assertEquals(48f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        assertEquals(48f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP, 0.01f)
        assertTrue("行距不得为负", ReaderMenuLayout.PANEL_ROW_GAP_DP >= 0f)
        assertTrue("左右内边距不得为负", ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP >= 0f)
        assertTrue("底部内边距不得为负", ReaderMenuLayout.PANEL_BOTTOM_PADDING_DP >= 0f)
    }

    @Test
    fun `上下一本按钮的可见本体不小于 96 乘 48dp`() {
        // 票 #105 AC7「按钮加大」的可见尺寸下限（不只可点区域）；96×48 也覆盖了触摸目标下限
        assertTrue("按钮可见宽度 ${ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP}dp 必须 ≥ 96dp", ReaderMenuLayout.BOOK_STEP_MIN_WIDTH_DP >= 96f)
        assertTrue("按钮可见高度 ${ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP}dp 必须 ≥ 48dp", ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP >= 48f)
        // 按钮本体不得高过底部行（否则会被行高裁掉）
        assertTrue(
            "按钮本体不得高过底部行",
            ReaderMenuLayout.BOOK_STEP_MIN_HEIGHT_DP <= ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP,
        )
    }

    @Test
    fun `预览区高度必须扣掉面板必然占用的底部 inset`() {
        // 沉浸态下面板底部恒有一份 inset 兜底（ReaderOverlayLayout.MIN_BOTTOM_DP）：
        // 漏掉它算出来的预览区会比真机高 24dp、一屏张数会比真机少 ~15%（评审 r1 的 P2）
        assertEquals(24f, ReaderOverlayLayout.MIN_BOTTOM_DP, 0.01f)
        for ((label, viewport) in viewports) {
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
        val panel = ReaderMenuLayout.panelHeightDp(shortViewport, titleHeight(812f), ReaderOverlayLayout.MIN_BOTTOM_DP)
        assertTrue(
            "360dp 视口预览条 ${strip}dp 必须 ≥ 80dp（AC11 保底，会因上限过紧而变红）",
            strip >= ReaderMenuLayout.SHORT_VIEWPORT_MIN_PREVIEW_DP,
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
            strip >= ReaderMenuLayout.SHORT_VIEWPORT_MIN_PREVIEW_DP,
        )
        val panel = ReaderMenuLayout.panelHeightDp(shortViewport, titleHeight(812f, 1.4f), ReaderOverlayLayout.MIN_BOTTOM_DP)
        assertTrue("面板占比 ${panel / shortViewport * 100}% 必须 ≤ 80%", panel <= shortViewport * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f)
        assertEquals("面板 = 固定行 192.64 + 保底 80", 272.64f, panel, 0.01f)
    }

    @Test
    fun `480 与 600dp 高的横屏设备预览条也保底 80dp`() {
        // 票 #105 AC11（r5 推广）：保底公式不再只对矮视口生效——480–700dp 高的横屏设备
        // （1024×600 平板、480–520dp 档）此前走「恒 40%」，四条固定行把预览条压到 0–34dp（480–520dp 下为负）。
        // 480dp（非矮视口：留白 3dp、行距 8dp×3、底部行 48dp、内宽 812dp）：
        //   固定行 = 24 + 4 + 3 + 57.6(两行标题) + 24 + 48 + 48 = 208.6 ⇒ 保底需求 288.6 > 40% × 480 = 192 ⇒ 面板 288.6、预览条 80dp
        // 600dp（横屏平板，内宽 984dp ⇒ 标题 24sp 顶上限）：
        //   固定行 = 208.6 ⇒ 需求 288.6 ≤ 80% × 600 = 480 ⇒ 面板 288.6（48.1%）、预览条 80dp
        for ((label, height, inner) in listOf(
            Triple("480dp 横屏", 480f, 812f),
            Triple("600dp 横屏（1024×600）", 600f, 984f),
        )) {
            val strip = previewStripHeight(height, inner)
            assertTrue(
                "$label：预览条 ${strip}dp 必须 ≥ 80dp（保底公式推广后不得再是 0–34dp / 负数；" +
                    "0.01dp 容差只为吸收浮点误差）",
                strip >= ReaderMenuLayout.SHORT_VIEWPORT_MIN_PREVIEW_DP - 0.01f,
            )
            val panel = ReaderMenuLayout.panelHeightDp(height, titleLine(inner) * ENTRY_NAME_MAX_LINES, ReaderOverlayLayout.MIN_BOTTOM_DP)
            assertTrue("$label：面板 ${panel}dp 必须 ≤ 80% 屏高", panel <= height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f)
            assertTrue("$label：面板必须高于 40% 基线（保底项在起作用）", panel > height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION + 0.01f)
        }
    }

    @Test
    fun `竖屏与常规平板的面板高度不因保底公式推广而变`() {
        // 40% 已大于「固定行 + 80dp」的那些视口必须逐像素不变：面板仍 = 40% × 屏高。
        // 内宽取各自真实档：手机竖屏 365、平板竖屏 728、横屏平板 984
        for ((label, height, inner) in listOf(
            Triple("手机竖屏", 852f, 365f),
            Triple("平板竖屏", 1024f, 728f),
            Triple("平板横屏", 768f, 984f),
        )) {
            val panel = ReaderMenuLayout.panelHeightDp(height, titleLine(inner) * ENTRY_NAME_MAX_LINES, ReaderOverlayLayout.MIN_BOTTOM_DP)
            assertEquals("$label：面板必须仍是 40% × 屏高", height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION, panel, 0.01f)
            val needed = ReaderMenuLayout.fixedRowsHeightDp(
                shortViewport = false,
                titleHeightDp = titleLine(inner) * ENTRY_NAME_MAX_LINES,
                bottomInsetDp = ReaderOverlayLayout.MIN_BOTTOM_DP,
            ) + ReaderMenuLayout.SHORT_VIEWPORT_MIN_PREVIEW_DP
            assertTrue("$label：40% 必须已大于保底需求（$needed dp），这才是「不变」的依据", height * ReaderMenuLayout.PANEL_HEIGHT_FRACTION > needed)
        }
    }

    @Test
    fun `矮视口视口够高时预览条仍保底 80dp`() {
        // 视口高 400dp：80% 上限 = 320dp ≥ 固定行 169.6 + 80 ⇒ 保底项取到实际值
        val strip = shortViewportStripHeight(400f, 812f)
        assertEquals(
            "视口高 400dp 时预览条必须正好是保底 80dp",
            ReaderMenuLayout.SHORT_VIEWPORT_MIN_PREVIEW_DP,
            strip,
            0.01f,
        )
        val panel = ReaderMenuLayout.panelHeightDp(400f, titleHeight(812f), ReaderOverlayLayout.MIN_BOTTOM_DP)
        assertTrue(
            "面板占比 ${panel / 400f * 100}% 必须 ≤ 80%",
            panel <= 400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX + 0.01f,
        )
    }

    @Test
    fun `矮视口面板 52% 是公式起点 不是 40%`() {
        // 票 #105 评审 spec P2-1：“52% 起点”必须真的在代码里。用**合成输入**把保底项压小
        // （标题 10dp、无底部 inset）：固定行 = 4 + 10 + 48 + 36 = 98dp，保底项 = 178dp < 52% × 400 = 208dp
        // ⇒ 此时面板取比例值；若代码误用 40%（= 160dp）本断言变红。
        val panel = ReaderMenuLayout.panelHeightDp(400f, titleHeightDp = 10f, bottomInsetDp = 0f)
        assertEquals(400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT, panel, 0.01f)
        assertTrue("52% 起点必须高于 40% 的旧值", panel > 400f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION)
    }

    @Test
    fun `矮视口固定行已压扁 标题不留白行距为零底部行 36dp`() {
        assertTrue("矮视口标题不留上侧留白", ReaderMenuLayout.panelTitleTopPaddingDp(true) == 0f)
        assertTrue("矮视口行距为零", ReaderMenuLayout.panelRowGapDp(true) == 0f)
        assertEquals(36f, ReaderMenuLayout.panelFooterHeightDp(true), 0.01f)
        // 进度条行**不压**：Material3 的 Slider 最小高 44dp，压不到方案图假设的 12dp
        assertEquals(48f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP, 0.01f)
    }

    @Test
    fun `竖屏与平板的面板占比与固定行尺寸与改动前逐像素一致`() {
        for ((label, height) in tallViewports) {
            val panel = ReaderMenuLayout.panelHeightDp(height, titleLine(400f), ReaderOverlayLayout.MIN_BOTTOM_DP)
            assertEquals("$label：面板仍须是视口高度的 40%", height * 0.4f, panel, 0.01f)
            assertEquals("$label：标题留白不变", ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP, ReaderMenuLayout.panelTitleTopPaddingDp(false), 0.01f)
            assertEquals("$label：行距不变", ReaderMenuLayout.PANEL_ROW_GAP_DP, ReaderMenuLayout.panelRowGapDp(false), 0.01f)
            assertEquals("$label：底部行仍 48dp", ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, ReaderMenuLayout.panelFooterHeightDp(false), 0.01f)
        }
    }

    @Test
    fun `极矮视口面板顶到 80% 上限`() {
        // 240dp 可用高（折叠机外屏/分屏）：内宽 400dp ⇒ 标题两行 48dp、固定行 160dp，
        // 保底需求 = 160 + 80 = 240dp，80% 上限 = 192dp ⇒ 上限生效：面板 192dp（顶满 80%）、预览条 32dp。
        // 这是「极小屏保不住 80dp、但也别让面板吃掉整屏」的兜底场合。
        val titleHeight = titleHeight(400f)
        assertEquals("两行标题预算：内宽 400dp ⇒ 一行 24dp × 2", 48f, titleHeight, 0.01f)
        val panel = ReaderMenuLayout.panelHeightDp(240f, titleHeight, ReaderOverlayLayout.MIN_BOTTOM_DP)
        assertEquals("面板必须正好顶到 80% 上限", 240f * ReaderMenuLayout.PANEL_HEIGHT_FRACTION_SHORT_MAX, panel, 0.01f)
        val strip = shortViewportStripHeight(240f, 400f)
        assertTrue("此类极小视口下预览条 $strip dp 确实不足 80dp（兜底上限先生效，已在证据里披露）", strip < ReaderMenuLayout.SHORT_VIEWPORT_MIN_PREVIEW_DP)
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
        val panelLarge = ReaderMenuLayout.panelHeightDp(shortViewport, titleHeight(812f, 1.5f), ReaderOverlayLayout.MIN_BOTTOM_DP)
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
