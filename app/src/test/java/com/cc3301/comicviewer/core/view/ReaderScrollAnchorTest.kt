package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `随位置保存的页比例覆盖锚点页及其前后各若干页`() {
        val window = readerSavedAspectPages(anchorPage = 10)
        assertTrue("锚点页自己的比例必须保存", 10 in window)
        assertEquals("前若干页", 10 - READER_SAVED_ASPECT_REACH, window.first)
        assertEquals("后若干页", 10 + READER_SAVED_ASPECT_REACH, window.last)
        assertTrue("范围外的页不保存", window.last + 1 !in window)
    }

    @Test
    fun `保存的页比例范围不越到书首之前`() {
        assertEquals("页号没有负数", 0, readerSavedAspectPages(anchorPage = 1).first)
        assertEquals(1 + READER_SAVED_ASPECT_REACH, readerSavedAspectPages(anchorPage = 1).last)
    }

    @Test
    fun `保存窗口取位置页与当前页两侧区间的并集`() {
        // 一屏装很多短页时位置锚点页（顶部可见页）与当前页（视口中线页）可以相距超过两个窗口
        assertTrue("位置页自己的窗口 7..13", readerAspectInSavedWindow(index = 7, anchorPage = 10, currentPage = 18))
        assertTrue(readerAspectInSavedWindow(index = 13, anchorPage = 10, currentPage = 18))
        assertTrue("当前页自己的窗口 15..21", readerAspectInSavedWindow(index = 15, anchorPage = 10, currentPage = 18))
        assertTrue(readerAspectInSavedWindow(index = 21, anchorPage = 10, currentPage = 18))
        assertTrue("位置页本身", readerAspectInSavedWindow(index = 10, anchorPage = 10, currentPage = 18))
        assertTrue("当前页本身", readerAspectInSavedWindow(index = 18, anchorPage = 10, currentPage = 18))
        assertFalse("两段之间的空档不算（不是从 7 到 21 整段）", readerAspectInSavedWindow(index = 14, anchorPage = 10, currentPage = 18))
        assertFalse(readerAspectInSavedWindow(index = 6, anchorPage = 10, currentPage = 18))
        assertFalse(readerAspectInSavedWindow(index = 22, anchorPage = 10, currentPage = 18))
    }

    @Test
    fun `保存窗口两侧区间重叠时是同一扇窗口`() {
        // 10 与 12 相距 2：两个窗口 7..13 与 9..15 重叠成 7..15
        assertTrue(readerAspectInSavedWindow(index = 7, anchorPage = 10, currentPage = 12))
        assertTrue(readerAspectInSavedWindow(index = 15, anchorPage = 10, currentPage = 12))
        assertFalse(readerAspectInSavedWindow(index = 6, anchorPage = 10, currentPage = 12))
        assertFalse(readerAspectInSavedWindow(index = 16, anchorPage = 10, currentPage = 12))
    }

    @Test
    fun `没有记住的位置时从入口页页首开始 不沿用旧像素偏移`() {
        // 旧像素偏移是按离开那一刻的页高记的：套在 400dp 占位页上会越界、把顶部页整页顶掉
        assertEquals("页号落到入口页", 4, readerRestorePage(anchor = null, startIndex = 4))
        assertEquals("偏移归零", 0, readerRestoreOffsetPx(anchor = null, pageHeightPx = 1200))
    }

    @Test
    fun `有记住的位置时按记住的页高折回偏移`() {
        val anchor = readerScrollAnchor(page = 5, offsetPx = 1500, pageHeightPx = 3000)
        assertEquals("页号用记住的那一页，不用入口页", 5, readerRestorePage(anchor, startIndex = 0))
        assertEquals("页内偏移按记住的页高折回", 1500, readerRestoreOffsetPx(anchor, pageHeightPx = 3000))
        assertEquals("页高只剩占位高时也落在这一页里", 150, readerRestoreOffsetPx(anchor, pageHeightPx = 300))
    }
}
