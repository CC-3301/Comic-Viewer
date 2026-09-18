package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Test

/** 网格格子宽度（票 #50 AC 的纯函数落点：封面宽度只能等于格子宽度，两侧不留白）。 */
class GridLayoutTest {

    @Test
    fun `360dp 两列 格子宽度与实测一致`() {
        // 360dp 屏、内容边距 12dp、列间距 6dp：两列各 165dp（维护者截图里格子约 165dp）
        assertEquals(165f, gridCellWidth(360f, 2, 12f, 6f), 0.01f)
    }

    @Test
    fun `列数越多格子越窄 且始终占满可用宽度`() {
        val two = gridCellWidth(360f, 2, 12f, 6f)
        val three = gridCellWidth(360f, 3, 12f, 6f)
        val four = gridCellWidth(360f, 4, 12f, 6f)
        assertEquals(108f, three, 0.01f)
        assertEquals(79.5f, four, 0.01f)
        // 两列 > 三列 > 四列，且每档的总占用正好等于可用宽度（不留白）
        assertEquals(2 * two + 2 * 12f + 1 * 6f, 360f, 0.01f)
        assertEquals(3 * three + 2 * 12f + 2 * 6f, 360f, 0.01f)
        assertEquals(4 * four + 2 * 12f + 3 * 6f, 360f, 0.01f)
    }

    @Test
    fun `横屏宽屏同样成立`() {
        // 800dp 宽、3 列：800 - 24 - 12 = 764 / 3
        assertEquals(764f / 3f, gridCellWidth(800f, 3, 12f, 6f), 0.01f)
    }

    @Test
    fun `宽度小到装不下时不为负`() {
        assertEquals(0f, gridCellWidth(20f, 4, 12f, 6f), 0.01f)
    }

    @Test
    fun `非法列数不产生负宽度`() {
        assertEquals(0f, gridCellWidth(360f, 0, 12f, 6f), 0.01f)
        assertEquals(0f, gridCellWidth(360f, -2, 12f, 6f), 0.01f)
    }
}
