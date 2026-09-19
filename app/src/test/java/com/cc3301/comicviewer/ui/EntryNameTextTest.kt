package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目名称块的高度口径（票 #94）。
 *
 * 口径：网格档的名称块**固定两行高**——1 行名也占满两行，格子高度因此不随名称行数变化，
 * 同一排里「1 行名」与「2 行名」两格同高（封面顶边、名称首行基线、格底三者因此逐格对齐）。
 * 短名称下方的留白就是第二行本身（文本行高），不是硬编码像素值。列表档不受影响（最小行数 1，
 * 行高随名称行数变化是既有行为）。
 *
 * 本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），因此「1 行名与 2 行名的格子
 * 同高」无法在 JVM 单测里直接量：它由 `minLines = 行数上限 = 2` 保证 —— Compose 的文本布局在
 * `minLines` 与 `maxLines` 相等时，任何名称都恰好占两行（行高取自 [androidx.compose.ui.text.TextStyle]
 * 的 lineHeight/字体度量）。这里的断言钉住这个等式的两边，任一边被改动都会红。真机目视为最终证据。
 */
class EntryNameTextTest {

    @Test
    fun `名称行数上限是两行`() {
        assertEquals("票 #47 口径：最多两行、不省略号", 2, ENTRY_NAME_MAX_LINES)
    }

    @Test
    fun `网格档名称块的最小行数等于行数上限 1 行名与 2 行名因此同高`() {
        assertEquals(2, GRID_ENTRY_NAME_MIN_LINES)
        assertEquals(
            "网格档名称块占满行数上限：min < max 会让 1 行名只占 1 行，同排格子高度就会不一致",
            ENTRY_NAME_MAX_LINES,
            GRID_ENTRY_NAME_MIN_LINES,
        )
    }

    @Test
    fun `列表档不固定两行 行高仍随名称行数变化`() {
        assertTrue(
            "列表档的固定行数是 1（不传 minLines 的默认值），列表不存在同排对齐诉求",
            ENTRY_NAME_MIN_LINES < GRID_ENTRY_NAME_MIN_LINES,
        )
        assertEquals(1, ENTRY_NAME_MIN_LINES)
    }
}
