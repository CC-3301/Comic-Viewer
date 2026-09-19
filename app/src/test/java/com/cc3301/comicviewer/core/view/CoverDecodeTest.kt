package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面解码宽度与解码缓存键（票 #56）。
 *
 * 票面验收口径（原根因：解码宽度写死 128px，网格 2 列格宽数百 px 被放大数倍）：
 * - 解码宽度 ≥ 本次实际显示宽度，分桶过冲 ≤32px（列表档 = 56dp 对应像素宽）
 * - 缓存键含真实目标宽度：列表档与网格档不得互相串图
 * - 换列数（3/4 列）解码宽度随之变小
 * - ±1px 抖动落在同一桶，不为同一张封面留多份缓存
 */
class CoverDecodeTest {

    /**
     * 格宽取自仓库唯一来源 [gridCellWidth]（360dp 屏、外边距 12dp、列间距 6dp，与 [GridLayoutTest] 同参数）：
     * 浏览器里的 `GRID_CONTENT_PADDING`/`GRID_HORIZONTAL_SPACING` 一旦改动，这里跟着变，而不会自己算一套。
     */
    private fun cellDp(columns: Int): Float = gridCellWidth(360f, columns, 12f, 6f)

    /** 列表档行内封面列宽（BrowserScreen.LIST_COVER_WIDTH） */
    private val listCoverDp = 56f

    /** 常见屏幕密度（mdpi/hdpi/xhdpi/420dpi/xxhdpi/560dpi） */
    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f)

    private fun assertCoversDisplayWidth(displayDp: Float, density: Float, label: String) {
        val displayPx = displayDp * density
        val target = CoverDecode.targetWidthPx(displayPx)
        assertTrue(
            "$label density=$density: 解码宽度 $target 必须 ≥ 显示宽度 $displayPx（否则又要靠拉伸）",
            target >= displayPx,
        )
        assertTrue(
            "$label density=$density: 分桶过冲 ${target - displayPx} 必须 ≤32px（票面口径）",
            target - displayPx <= 32f,
        )
    }

    @Test
    fun `网格 2 列各密度下解码宽度不小于格宽且过冲不超过 32px`() {
        densities.forEach { assertCoversDisplayWidth(cellDp(2), it, "网格2列") }
    }

    @Test
    fun `列表档各密度下解码宽度不小于 56dp 对应像素`() {
        densities.forEach { assertCoversDisplayWidth(listCoverDp, it, "列表档") }
    }

    @Test
    fun `3 列 4 列的解码宽度随列数变小且各自不小于格宽`() {
        val density = 3f
        val two = CoverDecode.targetWidthPx(cellDp(2) * density)
        val three = CoverDecode.targetWidthPx(cellDp(3) * density)
        val four = CoverDecode.targetWidthPx(cellDp(4) * density)
        assertTrue("列数越多格宽越小：解码宽度必须跟着变小（2列 $two / 3列 $three / 4列 $four）", three < two && four < three)
        assertTrue(three >= cellDp(3) * density)
        assertTrue(four >= cellDp(4) * density)
    }

    @Test
    fun `亚像素抖动落在同一个桶里 不会为同一张封面多解`() {
        // 布局约束抖动几百分之一 px 不能让缓存键漂移
        assertEquals(CoverDecode.targetWidthPx(470f), CoverDecode.targetWidthPx(470.6f))
        assertEquals(CoverDecode.targetWidthPx(330f), CoverDecode.targetWidthPx(330.4f))
    }

    @Test
    fun `解码宽度始终是桶的整数倍`() {
        listOf(1f, 31.9f, 32f, 32.1f, 128f, 495.4f, 1080f).forEach { width ->
            assertEquals("width=$width", 0, CoverDecode.targetWidthPx(width) % CoverDecode.BUCKET_PX)
        }
    }

    @Test
    fun `布局未就绪的零宽或负宽不会产生 0 解码宽度`() {
        // 0 会成为 BitmapFactory 子采样循环的除零/失控输入
        assertTrue(CoverDecode.targetWidthPx(0f) >= CoverDecode.BUCKET_PX)
        assertTrue(CoverDecode.targetWidthPx(-10f) >= CoverDecode.BUCKET_PX)
    }

    @Test
    fun `缓存键含真实目标宽度 异宽异键同宽同键`() {
        assertEquals(
            "同条目同宽度同重取键必须同键（否则每次都要重解）",
            CoverDecode.key("entry-1", 0, 512),
            CoverDecode.key("entry-1", 0, 512),
        )
        assertNotEquals(
            "列表档与网格档宽度不同，键必须不同（不得互相串图）",
            CoverDecode.key("entry-1", 0, 192),
            CoverDecode.key("entry-1", 0, 512),
        )
    }

    @Test
    fun `缓存键仍按条目与重取键区分`() {
        assertNotEquals(CoverDecode.key("entry-1", 0, 512), CoverDecode.key("entry-2", 0, 512))
        assertNotEquals(CoverDecode.key("entry-1", 0, 512), CoverDecode.key("entry-1", 1, 512))
    }
}
