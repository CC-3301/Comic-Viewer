package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视图档位（票 #53 AC）：四档集合、默认网格 2 列、非法/缺失值回落网格 2 列。 */
class ViewModeTest {

    @Test
    fun `缺失或非法值回落网格 2 列`() {
        assertEquals(ViewMode.GRID_2, ViewMode.fromKey(null))
        assertEquals(ViewMode.GRID_2, ViewMode.fromKey(""))
        assertEquals(ViewMode.GRID_2, ViewMode.fromKey("bogus"))
        assertEquals(ViewMode.GRID_2, ViewMode.fromKey("GRID"))
        assertEquals(ViewMode.GRID_2, ViewMode.fromKey("grid_2"))
    }

    @Test
    fun `四档落盘键往返一致`() {
        ViewMode.entries.forEach { mode ->
            assertEquals(mode, ViewMode.fromKey(mode.name))
        }
    }

    @Test
    fun `档位与列数一一对应 列表没有列数`() {
        assertNull(ViewMode.LIST.columns)
        assertEquals(2, ViewMode.GRID_2.columns)
        assertEquals(3, ViewMode.GRID_3.columns)
        assertEquals(4, ViewMode.GRID_4.columns)
        assertFalse(ViewMode.LIST.isGrid)
        assertTrue(ViewMode.GRID_2.isGrid)
        assertTrue(ViewMode.GRID_3.isGrid)
        assertTrue(ViewMode.GRID_4.isGrid)
    }

    @Test
    fun `网格列数列表档回落 2 列`() {
        // 票 #124 C 组：界面三处（滑条分母 / 格宽 / 格子）都问 `gridColumns` 而不总是先问 `isGrid`，
        // 这个回落值因此只能有一份。
        assertEquals(2, ViewMode.LIST.gridColumns)
        ViewMode.entries.filter { it.isGrid }.forEach {
            assertEquals("网格档的 gridColumns 就是它自己的 columns", it.columns, it.gridColumns)
        }
        assertEquals(listOf(2, 3, 4), ViewMode.entries.filter { it.isGrid }.map { it.gridColumns })
    }

    @Test
    fun `恰好四档 不多不少`() {
        assertEquals(4, ViewMode.entries.size)
        assertEquals(listOf(2, 3, 4), ViewMode.entries.mapNotNull { it.columns })
    }
}
