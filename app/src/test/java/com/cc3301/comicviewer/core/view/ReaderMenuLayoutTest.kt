package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读菜单预览格尺寸（票 #42 AC）：5 格铺满面板内宽、单格明显大于原来的 42×58dp。
 * 预览格改成竖版更大的格子（票 #62 AC）：格高 ≥ 改动前 1.25 倍、缩略图解码宽度 ≥ 格宽像素、格内页码字号随格宽放大。
 * 预览窗口页码（票 #87 AC）：页位 → 哪些格要画、高亮格是哪一格。
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
}
