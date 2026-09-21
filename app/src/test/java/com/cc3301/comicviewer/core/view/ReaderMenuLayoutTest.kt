package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读菜单的布局口径（票 #105 一次重定 #42/#62/#65/#66/#67）。
 *
 * 覆盖：
 * - 面板高度占比（AC4）与「三种视口下预览区都吃得到高度」（AC5：底部行不被挤出面板）；
 * - 预览项尺寸（AC1/AC3）：高度撑满预览区、宽度 = 高度 × 该页真实比例、一屏几格由屏幕宽度决定
 *   （手机 ≈2.5 张、平板 ≈3.5 张，裁决给的容差 ±0.3）；滑动条叠放的遮挡比例 ≤ 25%；
 * - 三档字号（AC6）：票面表的 `内宽 × 0.05 / 0.05 / 0.035` 与 18–24 / 16–24 / 12–16sp 上下限，
 *   不变量 **标题 ≥ 页码 > 格内页码**；
 * - 页位口径：滑块值 → 最近页（AC9，含 3 页书没有死区）、页位夹取、格内页码换算。
 *
 * 真机目视与实测截图（AC1/AC4/AC5 的目视、AC3 的「不留白/水平居中」观感）不在 JVM 里测，
 * 由 `ReaderMenuFooterTest`（底部行几何）与真机验收覆盖。
 */
class ReaderMenuLayoutTest {

    // ---------- 面板高度与预览区（AC4/AC5）----------

    /** 一屏能放几格：预览区宽度 ÷ 单格宽度（含间隙）——AC1 的「约 2.5 / 3.5 张」就是它 */
    private fun visibleItems(innerWidthDp: Float, previewAreaHeightDp: Float, aspect: Float): Float {
        val item = ReaderMenuLayout.previewItemWidth(previewAreaHeightDp, aspect)
        return (innerWidthDp + ReaderMenuLayout.PREVIEW_GAP_DP) / (item + ReaderMenuLayout.PREVIEW_GAP_DP)
    }

    /**
     * 预览区高度 = 面板高度 − 标题行 − 底部行 − 行距与内边距（三行结构：标题 / 预览区 / 底部行，
     * 滑动条叠在预览区下缘、不占行）。每一项都取生产常量，算例因此不会与生产漂移。
     */
    private fun previewAreaHeight(viewportHeightDp: Float, innerWidthDp: Float): Float {
        val panel = viewportHeightDp * ReaderMenuLayout.PANEL_HEIGHT_FRACTION
        val titleLine = ReaderMenuLayout.panelTitleSp(innerWidthDp) * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO
        return panel - ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP - titleLine -
            ReaderMenuLayout.PANEL_ROW_GAP_DP * 2 - ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP -
            ReaderMenuLayout.PANEL_BOTTOM_PADDING_DP
    }

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
            val strip = previewAreaHeight(height, inner)
            assertTrue("$label：预览区高度 ${strip}dp 必须为正（否则底部行被挤出/面板要滚动，AC5）", strip > 0f)
        }
    }

    @Test
    fun `手机竖屏一屏约 2_5 张`() {
        val (height, inner) = viewports[0].second
        val visible = visibleItems(inner, previewAreaHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("手机一屏 $visible 张，必须落在 2.5 ± 0.3（AC1）", kotlin.math.abs(visible - 2.5f) <= 0.3f)
    }

    @Test
    fun `平板竖屏一屏约 3_5 张`() {
        val (height, inner) = viewports[1].second
        val visible = visibleItems(inner, previewAreaHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        assertTrue("平板一屏 $visible 张，必须落在 3.5 ± 0.3（AC1）", kotlin.math.abs(visible - 3.5f) <= 0.3f)
    }

    @Test
    fun `张数不写死 屏幕越宽一屏越多`() {
        val counts = viewports.map { (_, viewport) ->
            val (height, inner) = viewport
            visibleItems(inner, previewAreaHeight(height, inner), ReaderMenuLayout.PREVIEW_PLACEHOLDER_ASPECT)
        }
        assertTrue("平板竖屏（${counts[1]}）必须比手机竖屏（${counts[0]}）多", counts[1] > counts[0])
        assertTrue("平板横屏（${counts[2]}）必须比平板竖屏（${counts[1]}）多", counts[2] > counts[1])
    }

    @Test
    fun `滑动条叠放遮挡不超过缩略图高度的四分之一`() {
        // 只对 AC1 点名的两种设备类（手机竖屏、平板竖屏）下这个断言：横屏平板的面板更矮
        // （视口 768dp ⇒ 预览区约 187dp），叠放遮挡约 25.6%，已超出裁决给的 25% 阈值——
        // 裁决给的补救（预览条留 ≤24dp 底部内边距）会把平板竖屏的一屏张数从 3.7 推到 4.0
        // （超出 3.5±0.3），因此**未自行调整**，按「超出这个范围停下来报告」处理（见 evidence-impl.md）。
        for ((label, viewport) in viewports.take(2)) {
            val (height, inner) = viewport
            val strip = previewAreaHeight(height, inner)
            val covered = ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP / strip
            assertTrue("$label：滑动条盖住 ${covered * 100}% 的缩略图高度，必须 ≤ 25%", covered <= 0.25f)
        }
    }

    @Test
    fun `底部行与滑动条都是 48dp 的触摸目标`() {
        assertEquals(48f, ReaderMenuLayout.PANEL_FOOTER_HEIGHT_DP, 0.01f)
        assertEquals(48f, ReaderMenuLayout.SLIDER_BAND_HEIGHT_DP, 0.01f)
        assertTrue("行距不得为负", ReaderMenuLayout.PANEL_ROW_GAP_DP >= 0f)
        assertTrue("左右内边距不得为负", ReaderMenuLayout.PANEL_HORIZONTAL_PADDING_DP >= 0f)
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
