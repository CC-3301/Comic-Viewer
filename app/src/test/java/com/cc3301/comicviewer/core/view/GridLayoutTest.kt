package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `网格项高度上限只扣上下留白`() {
        // 票 #106：可视高度 320dp、contentPadding 12dp → 格子（封面 + 间距 + 名字块）最多占 296dp。
        // 名字块与格子内间距不在这里扣：由布局真量名字块后让出（见 BrowserGridCell）
        assertEquals(296f, gridCellMaxHeight(320f, 12f), 0.01f)
    }

    @Test
    fun `可视高度小于上下留白时上限为 0 不为负`() {
        assertEquals(0f, gridCellMaxHeight(20f, 12f), 0.01f)
        assertEquals(0f, gridCellMaxHeight(0f, 12f), 0.01f)
    }

    @Test
    fun `名字行宽度等于封面宽度 且与封面同中线`() {
        // 票 #106 r2（维护者拍板 D6-A）：名字行宽 = 封面宽、左缘与封面左缘对齐（封面水平居中 ⇒ 同中线）
        val shrunk = gridNameRow(cellWidth = 400f, coverWidth = 300f)
        assertEquals(300f, shrunk.width, 0.01f)
        assertEquals(50f, shrunk.left, 0.01f)
        // 名字行完全落在格子内：左缘不为负、右缘不越界
        assertTrue("名字行不得越出格子右缘", shrunk.left + shrunk.width <= 400f)
        assertTrue("名字行左缘不得为负", shrunk.left >= 0f)
    }

    @Test
    fun `封面未收缩时名字行等于格宽 左缘为零`() {
        // 票 #106 AC8：竖屏 2/3/4 格不收缩（封面宽 = 格宽）⇒ 名字行 = 格宽、左缘 0（与改动前逐像素一致）
        listOf(165f, 117f, 79.5f).forEach { cell ->
            val row = gridNameRow(cellWidth = cell, coverWidth = cell)
            assertEquals("格宽 $cell 时名字行宽 = 格宽", cell, row.width, 0.0001f)
            assertEquals("格宽 $cell 时名字行左缘 = 格左缘", 0f, row.left, 0.0001f)
        }
    }

    @Test
    fun `封面宽大于格宽时不把名字行推出格右缘`() {
        // 兜底：封面宽理论上不超过格宽；真越界时也不得让名字行左缘为负（宁可压在格左缘）
        val row = gridNameRow(cellWidth = 300f, coverWidth = 320f)
        assertEquals(0f, row.left, 0.0001f)
    }

    @Test
    fun `封面宽为零时名字行宽为零 且仍在格内`() {
        val row = gridNameRow(cellWidth = 300f, coverWidth = 0f)
        assertEquals(0f, row.width, 0.0001f)
        assertEquals(150f, row.left, 0.0001f)
    }

    @Test
    fun `非法列数不产生负宽度`() {
        assertEquals(0f, gridCellWidth(360f, 0, 12f, 6f), 0.01f)
        assertEquals(0f, gridCellWidth(360f, -2, 12f, 6f), 0.01f)
    }
}
