package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读菜单预览格尺寸（票 #42 AC）：5 格铺满面板内宽、单格明显大于原来的 42×58dp。
 * 预览格改成竖版更大的格子（票 #62 AC）：格高 ≥ 改动前 1.25 倍、缩略图解码宽度 ≥ 格宽像素、格内页码字号随格宽放大。
 * 预览窗口页码（票 #87 + 票 #65 AC）：页位 → 哪些格要画、高亮格是哪一格；总页数 ≥ 5 时窗口整体平移到合法区间、永远 5 格。
 * 滑块值 → 跳页目标（票 #63）：四舍五入到最近的页并夹到首末页。
 * 格内显示页码（票 #64）：0-based 页位 → 1-based 页码（跳页用的仍是同一个页位，靠 clampPage 夹取）。
 * 面板底部页码字号（票 #66）：随面板内宽放大、夹在上下限之间，平板不低于现值 bodyMedium(14sp) 的 1.3 倍。
 * 菜单标题字号与顶部留白（票 #67）：标题不低于现值 titleMedium(16sp) 的 1.2 倍、面板内三档字号层级恒为
 * 标题 > 面板页码 > 格内页码、标题上方留白 ≤ 8dp。
 */
class ReaderMenuLayoutTest {

    /** 改动前的格高宽比（58:42）——票 #62 的两条「与改动前比」的用例都以它为基线 */
    private val oldAspect = 58f / 42f

    /** 改动前 360dp 屏的单格尺寸：内宽 320dp 等分 5 格 */
    private val oldCellHeight = oldAspect * ((320f - ReaderMenuLayout.PREVIEW_GAP_DP * 4) / 5f)

    @Test
    fun `360dp 屏面板内宽约 320dp 时单格约 59dp`() {
        // 面板内宽 = 360 − 两侧 20dp 内边距 = 320dp；五格等分：(320 − 4×6) / 5 = 59.2dp
        val width = ReaderMenuLayout.previewCellWidth(320f)
        assertEquals(59.2f, width, 0.05f)
        assertTrue("单格必须明显大于改动前的 42dp", width > 42f)
    }

    @Test
    fun `五格加间隙正好铺满面板内宽 两侧不留大空档`() {
        val inner = 320f
        val width = ReaderMenuLayout.previewCellWidth(inner)
        val used = width * ReaderMenuLayout.PREVIEW_CELLS + ReaderMenuLayout.PREVIEW_GAP_DP * 4
        assertEquals("合计必须等于面板内宽", inner, used, 0.01f)
        assertTrue("两侧留白不得超过一个间隙宽", inner - used <= ReaderMenuLayout.PREVIEW_GAP_DP)
    }

    @Test
    fun `格比例改为竖版 7 比 4`() {
        assertEquals(7f / 4f, ReaderMenuLayout.PREVIEW_ASPECT, 0.0001f)
        assertTrue("必须比改动前的 58:42 更竖（页面/封面的量级）", ReaderMenuLayout.PREVIEW_ASPECT > oldAspect)
        assertEquals(73.5f, ReaderMenuLayout.previewCellHeight(42f), 0.01f)
        assertEquals(103.6f, ReaderMenuLayout.previewCellHeight(59.2f), 0.05f)
    }

    @Test
    fun `360dp 屏格高不低于改动前的 1_25 倍`() {
        val height = ReaderMenuLayout.previewCellHeight(ReaderMenuLayout.previewCellWidth(320f))
        assertTrue(
            "格高 $height dp 必须 ≥ 改动前 $oldCellHeight dp 的 1.25 倍（票 #62 AC1）",
            height >= 1.25f * oldCellHeight,
        )
    }

    @Test
    fun `缩略图解码宽度按格宽像素向上分桶 过冲不超过一个桶`() {
        val bucket = CoverDecode.BUCKET_PX
        // 360dp 屏、密度 2：格宽 118.4px → 向上取到 128px
        val decode = ReaderMenuLayout.previewDecodeWidthPx(118.4f)
        assertEquals(128, decode)
        assertTrue("解码宽度必须 ≥ 格宽像素（票 #62 AC2）", decode.toFloat() >= 118.4f)
        assertTrue("过冲不得超过 32px（票 #62 AC2）", decode - 118.4f <= 32f)
        assertEquals("桶的整数倍", 0, decode % bucket)
        // 平板格更宽 → 解得更宽，不是固定宽度
        assertTrue(ReaderMenuLayout.previewDecodeWidthPx(148f) > decode)
        // 恰好落在桶上不加码
        assertEquals(128, ReaderMenuLayout.previewDecodeWidthPx(128f))
    }

    @Test
    fun `格内页码字号随格宽放大 且不小于原字号`() {
        // 360dp 屏 59.2dp 格 → 14.8sp，是原 labelSmall(11sp) 的 1.35 倍（票 #66 口径 ≥1.3 倍）
        assertEquals(14.8f, ReaderMenuLayout.previewPageLabelSp(59.2f), 0.01f)
        assertTrue(
            "页码字号必须 ≥ 原字号的 1.3 倍",
            ReaderMenuLayout.previewPageLabelSp(59.2f) >= 1.3f * 11f,
        )
        assertTrue(
            "格更宽（平板）字号跟着更大",
            ReaderMenuLayout.previewPageLabelSp(67.2f) > ReaderMenuLayout.previewPageLabelSp(59.2f),
        )
        assertEquals("极窄格也不小于原字号", 11f, ReaderMenuLayout.previewPageLabelSp(10f), 0.01f)
    }

    @Test
    fun `宽面板下页码字号被上限夹住`() {
        // 面板是 fillMaxWidth：873dp 宽的横屏 → 格宽 161.8dp，不夹的话是 40.5sp（比面板标题 16sp 套大）
        val wide = ReaderMenuLayout.previewPageLabelSp(161.8f)
        assertEquals(ReaderMenuLayout.PREVIEW_LABEL_MAX_SP, wide, 0.01f)
        assertTrue("上限不得低于 360dp 屏的实际字号", wide >= ReaderMenuLayout.previewPageLabelSp(59.2f))
        assertTrue("仍不小于下限", wide >= ReaderMenuLayout.PREVIEW_LABEL_MIN_SP)
        // 1280dp 宽屏同样被夹住
        assertEquals(ReaderMenuLayout.PREVIEW_LABEL_MAX_SP, ReaderMenuLayout.previewPageLabelSp(243.2f), 0.01f)
        // 上限夹到面板标题（titleMedium）量级，不超过它
        assertEquals(16f, ReaderMenuLayout.PREVIEW_LABEL_MAX_SP, 0.01f)
    }

    @Test
    fun `横屏宽面板同样按内宽等分`() {
        val width = ReaderMenuLayout.previewCellWidth(500f)
        assertEquals((500f - 24f) / 5f, width, 0.01f)
    }

    @Test
    fun `极窄面板不出现负宽度`() {
        assertEquals(0f, ReaderMenuLayout.previewCellWidth(10f), 0.01f)
    }

    @Test
    fun `仍是五格`() {
        assertEquals(5, ReaderMenuLayout.PREVIEW_CELLS)
    }

    // ---------- 预览窗口页码（票 #87 + 票 #65：页位 → 高亮格，窗口整体平移凑满 5 格）----------

    @Test
    fun `首页窗口平移到前五格 高亮格就是首页`() {
        val window = ReaderMenuLayout.previewWindow(target = 0, pageCount = 10)
        assertEquals(listOf(0, 1, 2, 3, 4), window)
        assertTrue("首页必须在窗口内（高亮格）", window.contains(0))
    }

    @Test
    fun `末页窗口平移到后五格 高亮格就是末页`() {
        val window = ReaderMenuLayout.previewWindow(target = 9, pageCount = 10)
        assertEquals(listOf(5, 6, 7, 8, 9), window)
        assertEquals("高亮格 = 目标页", 9, window.last())
    }

    @Test
    fun `中间页窗口是目标页正负二`() {
        assertEquals(listOf(2, 3, 4, 5, 6), ReaderMenuLayout.previewWindow(target = 4, pageCount = 10))
    }

    @Test
    fun `窗口起点 首页为 0 末页为总页数减格数 中间页为目标页减二`() {
        assertEquals(0, ReaderMenuLayout.previewWindowStart(target = 0, pageCount = 200))
        assertEquals(195, ReaderMenuLayout.previewWindowStart(target = 199, pageCount = 200))
        assertEquals(2, ReaderMenuLayout.previewWindowStart(target = 4, pageCount = 200))
    }

    @Test
    fun `六页书首页与末页都正好五格 且平移到合法区间`() {
        // 首页：起点夹在 0（不能是 −2）
        assertEquals(listOf(0, 1, 2, 3, 4), ReaderMenuLayout.previewWindow(target = 0, pageCount = 6))
        // 末页：起点夹在 6 − 5 = 1
        assertEquals(listOf(1, 2, 3, 4, 5), ReaderMenuLayout.previewWindow(target = 5, pageCount = 6))
    }

    @Test
    fun `五页书从首页到末页都是同一份窗口`() {
        val all = listOf(0, 1, 2, 3, 4)
        for (target in 0..4) {
            assertEquals("第 ${target + 1} 页", all, ReaderMenuLayout.previewWindow(target = target, pageCount = 5))
        }
    }

    @Test
    fun `总页数不足五页时按实际页数显示 不补空格`() {
        assertEquals(listOf(0), ReaderMenuLayout.previewWindow(target = 0, pageCount = 1))
        assertEquals(listOf(0, 1), ReaderMenuLayout.previewWindow(target = 0, pageCount = 2))
        assertEquals(listOf(0, 1), ReaderMenuLayout.previewWindow(target = 1, pageCount = 2))
        assertEquals(listOf(0, 1, 2), ReaderMenuLayout.previewWindow(target = 0, pageCount = 3))
        assertEquals(listOf(0, 1, 2, 3), ReaderMenuLayout.previewWindow(target = 3, pageCount = 4))
        assertEquals("合法区间为空时起点恒为 0", 0, ReaderMenuLayout.previewWindowStart(target = 3, pageCount = 2))
    }

    @Test
    fun `任意目标页都正好五格 且包含目标页本身`() {
        for (pageCount in ReaderMenuLayout.PREVIEW_CELLS..20) {
            for (target in 0 until pageCount) {
                val window = ReaderMenuLayout.previewWindow(target = target, pageCount = pageCount)
                assertEquals(
                    "${pageCount} 页书的第 ${target + 1} 页必须五格",
                    ReaderMenuLayout.PREVIEW_CELLS,
                    window.size,
                )
                assertTrue("窗口必须包含目标页（高亮格）", window.contains(target))
            }
        }
    }

    @Test
    fun `空书没有格`() {
        assertEquals(emptyList<Int>(), ReaderMenuLayout.previewWindow(target = 0, pageCount = 0))
        assertEquals(0, ReaderMenuLayout.previewWindowStart(target = 0, pageCount = 0))
    }

    // ---------- 滑块值 → 跳页目标（票 #63：点击轨道与拖动等价）----------

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
    fun `末页页位 单页书与空书都是 0`() {
        assertEquals(199, ReaderMenuLayout.lastPage(200))
        assertEquals(0, ReaderMenuLayout.lastPage(1))
        assertEquals(0, ReaderMenuLayout.lastPage(0))
        // 单页书的 Slider 值域是 0f..1f，滑到 1 也只能落到第 1 页
        assertEquals(0, ReaderMenuLayout.seekTargetPage(1f, pageCount = 1))
    }

    // ---------- 格内显示页码（票 #64）----------

    @Test
    fun `格上显示的页码是格位加一`() {
        // 0-based 页位 → 1-based 页码只有 previewPageLabel 一处换算；跳页用的仍是同一个页位（clampPage 夹取）
        for (cell in ReaderMenuLayout.previewWindow(target = 4, pageCount = 10)) {
            assertEquals("格上显示的页码", cell + 1, ReaderMenuLayout.previewPageLabel(cell))
        }
        assertEquals(1, ReaderMenuLayout.previewPageLabel(0))
        assertEquals(10, ReaderMenuLayout.previewPageLabel(9))
    }

    @Test
    fun `跳页目标就是预览窗口的高亮格`() {
        // 与 previewWindow（票 #87）同一口径：末页时高亮格是末页、首页时是第一格
        val last = ReaderMenuLayout.seekTargetPage(199f, pageCount = 200)
        assertEquals(last, ReaderMenuLayout.previewWindow(last, pageCount = 200).last())
        val first = ReaderMenuLayout.seekTargetPage(0f, pageCount = 200)
        assertEquals(first, ReaderMenuLayout.previewWindow(first, pageCount = 200).first())
    }

    // ---------- 面板底部页码字号（票 #66：与上/下一本同行，字号明显放大）----------

    /** 改动前面板页码的字号：`bodyMedium` 的默认 14sp */
    private val oldPageLabelSp = 14f

    @Test
    fun `平板面板页码字号不低于现值的一点三倍`() {
        // 10 英寸平板横屏约 960dp 宽 → 面板内宽 ≈ 920dp（面板是 fillMaxWidth）
        val tablet = ReaderMenuLayout.panelPageLabelSp(920f)
        assertTrue(
            "平板页码 $tablet sp 必须 ≥ 现值 ${oldPageLabelSp}sp 的 1.3 倍（票 #66 AC2）",
            tablet >= 1.3f * oldPageLabelSp,
        )
        // 手机（360dp 屏 → 内宽 320dp）：也要比现值大，但必须比平板小
        val phone = ReaderMenuLayout.panelPageLabelSp(320f)
        assertTrue("手机页码 $phone sp 也必须比现值 ${oldPageLabelSp}sp 大（票 #66：明显放大）", phone > oldPageLabelSp)
        assertTrue("平板必须比手机大（这是本票要修的那件事）", tablet > phone)
    }

    @Test
    fun `页码字号随面板内宽单调放大 且夹在上下限之间`() {
        val widths = listOf(240f, 320f, 480f, 600f, 920f, 1280f)
        val sizes = widths.map { ReaderMenuLayout.panelPageLabelSp(it) }
        for (i in 0 until sizes.size - 1) {
            assertTrue(
                "内宽 ${widths[i]}dp → ${widths[i + 1]}dp 时页码不得变小",
                sizes[i] <= sizes[i + 1],
            )
        }
        assertEquals("极窄面板夹在下限", ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP, sizes.first(), 0.01f)
        assertEquals("宽面板夹在上限", ReaderMenuLayout.PANEL_PAGE_LABEL_MAX_SP, sizes.last(), 0.01f)
        assertTrue(
            "下限不得低于现值 1.3 倍（极窄面板也保持「明显放大」）",
            ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP >= 1.3f * oldPageLabelSp,
        )
        assertTrue("上限必须大于下限", ReaderMenuLayout.PANEL_PAGE_LABEL_MAX_SP > ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP)
    }

    @Test
    fun `360dp 屏页码 19_2sp 是现值的 1_37 倍`() {
        // 内宽 320dp × 比例 0.06 = 19.2sp，正好在改动前 14sp 的 1.3 倍之上
        assertEquals(19.2f, ReaderMenuLayout.panelPageLabelSp(320f), 0.05f)
        assertEquals(0.06f, ReaderMenuLayout.PANEL_PAGE_LABEL_SP_RATIO, 0.0001f)
    }

    @Test
    fun `页码行高比例不小于一 放大后的数字不被压`() {
        // 面板页码与格内页码共用这一处行高比例；行高 < 字号时数字会被压扁
        assertTrue(
            "行高比例 ${ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO} 必须 ≥ 1",
            ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= 1f,
        )
        val labelSp = ReaderMenuLayout.panelPageLabelSp(920f)
        assertTrue(
            "面板页码行高（${labelSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO}dp）必须 ≥ 字号",
            labelSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= labelSp,
        )
        val titleSp = ReaderMenuLayout.panelTitleSp(920f)
        assertTrue(
            "菜单标题行高（${titleSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO}dp）必须 ≥ 字号",
            titleSp * ReaderMenuLayout.PANEL_TEXT_LINE_HEIGHT_RATIO >= titleSp,
        )
    }

    // ---------- 菜单标题字号与顶部留白（票 #67）----------

    /** 改动前菜单标题的字号：`titleMedium` 的默认 16sp（票 #67 的「与现值比」用例以它为基线） */
    private val oldTitleSp = 16f

    @Test
    fun `菜单标题字号不低于现值的一点二倍`() {
        // 360dp 屏（面板内宽 320dp）：22.4sp = 现值 16sp 的 1.4 倍
        val phone = ReaderMenuLayout.panelTitleSp(320f)
        assertTrue(
            "标题 $phone sp 必须 ≥ 现值 ${oldTitleSp}sp 的 1.2 倍（票 #67 AC1）",
            phone >= 1.2f * oldTitleSp,
        )
        assertTrue(
            "极窄面板也要守住 AC1（下限 ${ReaderMenuLayout.PANEL_TITLE_MIN_SP}sp ≥ 1.2 倍）",
            ReaderMenuLayout.PANEL_TITLE_MIN_SP >= 1.2f * oldTitleSp,
        )
        // 起点就是 titleLarge 的量级（票面建议「从 titleLarge 起步」）
        assertTrue("手机标题不得小于 titleLarge 的 22sp", phone >= 22f)
    }

    @Test
    fun `菜单标题字号随面板内宽放大 且夹在上下限之间`() {
        val widths = listOf(240f, 320f, 480f, 600f, 920f, 1280f)
        val sizes = widths.map { ReaderMenuLayout.panelTitleSp(it) }
        for (i in 0 until sizes.size - 1) {
            assertTrue(
                "内宽 ${widths[i]}dp → ${widths[i + 1]}dp 时标题不得变小",
                sizes[i] <= sizes[i + 1],
            )
        }
        assertEquals("极窄面板夹在下限", ReaderMenuLayout.PANEL_TITLE_MIN_SP, sizes.first(), 0.01f)
        assertEquals("宽面板夹在上限", ReaderMenuLayout.PANEL_TITLE_MAX_SP, sizes.last(), 0.01f)
        assertEquals("360dp 屏（内宽 320dp）", 22.4f, ReaderMenuLayout.panelTitleSp(320f), 0.05f)
        assertTrue("上限必须大于下限", ReaderMenuLayout.PANEL_TITLE_MAX_SP > ReaderMenuLayout.PANEL_TITLE_MIN_SP)
    }

    @Test
    fun `面板内三档字号层级恒为 标题 大于 页码 大于 格内页码`() {
        // 任意面板内宽下比值与上下限都逐层收窄，因此没有「有意破例」的例外
        for (width in listOf(100f, 240f, 320f, 480f, 600f, 920f, 1280f, 1400f)) {
            val title = ReaderMenuLayout.panelTitleSp(width)
            val page = ReaderMenuLayout.panelPageLabelSp(width)
            assertTrue("内宽 ${width}dp：标题 $title sp 必须大于面板页码 $page sp", title > page)
        }
        assertTrue(
            "标题上限必须大于面板页码上限",
            ReaderMenuLayout.PANEL_TITLE_MAX_SP > ReaderMenuLayout.PANEL_PAGE_LABEL_MAX_SP,
        )
        assertTrue(
            "标题下限必须大于面板页码下限",
            ReaderMenuLayout.PANEL_TITLE_MIN_SP > ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP,
        )
        // 格内页码是三层里最小的一层（票 #67 改口径：不再拿「面板标题」当它的上限依据）
        assertTrue(
            "面板页码下限必须大于格内页码上限 ${ReaderMenuLayout.PREVIEW_LABEL_MAX_SP}sp",
            ReaderMenuLayout.PANEL_PAGE_LABEL_MIN_SP > ReaderMenuLayout.PREVIEW_LABEL_MAX_SP,
        )
    }

    @Test
    fun `标题上方留白不超过 8dp 且为正`() {
        val top = ReaderMenuLayout.PANEL_TITLE_TOP_PADDING_DP
        assertTrue("面板顶边到标题行顶的留白 ${top}dp 必须 ≤ 8dp（票 #67 AC2）", top <= 8f)
        assertTrue("标题不得贴死面板顶边（留白为正）", top > 0f)
        // 改动前的上侧留白是 16dp：本票必须真的收紧
        assertTrue("必须比改动前的 16dp 小（这是本票要修的那件事）", top < 16f)
    }
}
