package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读菜单预览格尺寸（票 #42 AC）：5 格铺满面板内宽、单格明显大于原来的 42×58dp。
 * 预览窗口页码（票 #87 AC）：页位 → 哪些格要画、高亮格是哪一格。
 */
class ReaderMenuLayoutTest {

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
    fun `高度沿用既有高宽比`() {
        assertEquals(58f / 42f, ReaderMenuLayout.PREVIEW_ASPECT, 0.0001f)
        assertEquals(58f, ReaderMenuLayout.previewCellHeight(42f), 0.01f)
        assertEquals(81.8f, ReaderMenuLayout.previewCellHeight(59.2f), 0.05f)
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
