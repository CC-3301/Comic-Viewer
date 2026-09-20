package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读菜单预览格尺寸（票 #42 AC）：5 格铺满面板内宽、单格明显大于原来的 42×58dp。
 * 预览格改成竖版更大的格子（票 #62 AC）：格高 ≥ 改动前 1.25 倍、缩略图解码宽度 ≥ 格宽像素、格内页码字号随格宽放大。
 * 预览窗口页码（票 #87 AC）：页位 → 哪些格要画、高亮格是哪一格。
 * 滑块值 → 跳页目标（票 #63）：四舍五入到最近的页并夹到首末页。
 * 点击预览格 → 跳页目标（票 #64）：格上显示的页码与点击后跳到的页位是同一个页位（不差一格）。
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

    // ---------- 预览窗口页码（票 #87：页位 → 高亮格）----------

    @Test
    fun `末页窗口到末页为止 高亮格就是末页`() {
        val window = ReaderMenuLayout.previewWindow(target = 9, pageCount = 10)
        assertEquals(listOf(7, 8, 9), window)
        assertEquals("高亮格 = 目标页", 9, window.last())
    }

    @Test
    fun `中间页窗口是目标页正负二`() {
        assertEquals(listOf(2, 3, 4, 5, 6), ReaderMenuLayout.previewWindow(target = 4, pageCount = 10))
    }

    @Test
    fun `书首窗口从第一页开始`() {
        assertEquals(listOf(0, 1, 2), ReaderMenuLayout.previewWindow(target = 0, pageCount = 10))
        assertEquals(listOf(0, 1), ReaderMenuLayout.previewWindow(target = 0, pageCount = 2))
    }

    @Test
    fun `空书没有格`() {
        assertEquals(emptyList<Int>(), ReaderMenuLayout.previewWindow(target = 0, pageCount = 0))
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

    // ---------- 点击预览格跳页（票 #64）----------

    @Test
    fun `点击第 N 格跳到该格显示的页码那一页`() {
        val pageCount = 10
        // 中间页窗口 2..6（目标 4）：每格的显示页码 = 格位 + 1，点击该格跳到的页位必须是同一个页位（不差一格）
        for (cell in ReaderMenuLayout.previewWindow(target = 4, pageCount = pageCount)) {
            assertEquals("格上显示的页码", cell + 1, ReaderMenuLayout.previewPageLabel(cell))
            assertEquals(
                "点击该格跳到的页位必须与它显示的页码一致（不差一格）",
                ReaderMenuLayout.previewPageLabel(cell) - 1,
                ReaderMenuLayout.previewTapTarget(cell, pageCount),
            )
        }
    }

    @Test
    fun `点击高亮的当前页那格 与其它格同一条换算`() {
        val current = 4
        val pageCount = 10
        // 高亮格的判据是 `index == target`（previewWindow 的说明），点击它必须落到当前页、不跳到别页
        assertEquals(current, ReaderMenuLayout.previewTapTarget(current, pageCount))
        assertEquals("首格", 0, ReaderMenuLayout.previewTapTarget(0, pageCount))
        assertEquals("末格", 9, ReaderMenuLayout.previewTapTarget(9, pageCount))
    }

    @Test
    fun `点击目标夹到合法页位`() {
        assertEquals(0, ReaderMenuLayout.previewTapTarget(-1, pageCount = 10))
        assertEquals(9, ReaderMenuLayout.previewTapTarget(99, pageCount = 10))
        assertEquals(0, ReaderMenuLayout.previewTapTarget(0, pageCount = 0))
    }

    @Test
    fun `跳页目标就是预览窗口的高亮格`() {
        // 与 previewWindow（票 #87）同一口径：末页时高亮格是末页、首页时是第一格
        val last = ReaderMenuLayout.seekTargetPage(199f, pageCount = 200)
        assertEquals(last, ReaderMenuLayout.previewWindow(last, pageCount = 200).last())
        val first = ReaderMenuLayout.seekTargetPage(0f, pageCount = 200)
        assertEquals(first, ReaderMenuLayout.previewWindow(first, pageCount = 200).first())
    }
}
