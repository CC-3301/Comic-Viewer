package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条漫阅读位置的换算：页号 + 该页内偏移百分比（spec §重建后的位置恢复）。
 *
 * 判据：记录时按**当时的页高**折成百分比，恢复时再按**恢复那一刻的页高**折回像素。
 * 重建后页还没解码、页高是占位高，与记录时的真实页高不同；像素偏移会因此落到下一页，百分比不会。
 */
class ReaderScrollAnchorTest {

    @Test
    fun `页高相同时记录与恢复互为逆运算`() {
        val anchor = readerScrollAnchor(page = 5, offsetPx = 1500, pageHeightPx = 3000)
        assertEquals("页号照抄", 5, anchor.page)
        assertEquals("页内滚了一半", 0.5f, anchor.percent, 1e-6f)
        assertEquals("页高没变 ⇒ 回到同一像素", 1500, readerScrollOffsetPx(anchor, 3000))
    }

    @Test
    fun `恢复时页高变小也不落到下一页`() {
        // 记录：真实页高 3000px，页内已滚 1500px ⇒ 50%
        val anchor = readerScrollAnchor(page = 5, offsetPx = 1500, pageHeightPx = 3000)
        // 恢复：页未解码，占位高只有 600px（记住的比例推出来的，小于真实页高）
        val offset = readerScrollOffsetPx(anchor, 600)
        assertEquals("按占位高折回一半", 300, offset)
        assertTrue("偏移必须仍落在这一页里（< 页高），否则锚点会顶到下一页", offset < 600)
    }

    @Test
    fun `页首与页尾`() {
        assertEquals(0f, readerScrollAnchor(page = 2, offsetPx = 0, pageHeightPx = 800).percent, 1e-6f)
        // 偏移超出页高（极端布局）：夹到 1，恢复时停在页尾
        val past = readerScrollAnchor(page = 2, offsetPx = 1200, pageHeightPx = 800)
        assertEquals(1f, past.percent, 1e-6f)
        assertEquals(800, readerScrollOffsetPx(past, 800))
        // 负偏移（布局首帧）夹到 0
        assertEquals(0f, readerScrollAnchor(page = 2, offsetPx = -40, pageHeightPx = 800).percent, 1e-6f)
    }

    @Test
    fun `页高还没量到时落在页首`() {
        // 占位高还没测到（0）：没有可折的分母，百分比记 0
        assertEquals(0f, readerScrollAnchor(page = 7, offsetPx = 0, pageHeightPx = 0).percent, 1e-6f)
    }
}
