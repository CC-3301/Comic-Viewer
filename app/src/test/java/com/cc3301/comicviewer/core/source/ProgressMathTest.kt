package com.cc3301.comicviewer.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 进度展示与打开定位纯函数（票 05；票 31 加条目进度条门控） */
class ProgressMathTest {

    private fun progress(pageIndex: Int, totalPages: Int) =
        ReadingProgress(pageIndex, totalPages, updatedAtMs = 0L)

    // ---------- displayFraction / isCompleted ----------

    @Test
    fun `未读完部分填充`() {
        assertEquals(0.1f, progress(0, 10).displayFraction, 0.0001f)
        assertEquals(0.6f, progress(5, 10).displayFraction, 0.0001f)
        assertFalse(progress(0, 10).isCompleted)
    }

    @Test
    fun `读到最后一页即满格读完`() {
        assertEquals(1.0f, progress(9, 10).displayFraction, 0.0001f)
        assertTrue(progress(9, 10).isCompleted)
    }

    @Test
    fun `单页书读完`() {
        assertTrue(progress(0, 1).isCompleted)
        assertEquals(1.0f, progress(0, 1).displayFraction, 0.0001f)
    }

    @Test
    fun `越界页码钳制`() {
        assertEquals(1.0f, progress(15, 10).displayFraction, 0.0001f)
        assertTrue(progress(15, 10).isCompleted)
    }

    @Test
    fun `totalPages 防御为零`() {
        assertTrue(progress(0, 0).isCompleted)
    }

    // ---------- progressForEntry（列表/柜内进度条门控，spec 故事 16/45） ----------

    private fun book(id: String = "b") = BrowseEntry(id, id, isBook = true, coverUri = null)

    private fun container(id: String = "d") = BrowseEntry(id, id, isBook = false, coverUri = null)

    @Test
    fun `书条目已读显示进度条`() {
        assertEquals(progress(2, 5), progressForEntry(book(), progress(2, 5)))
    }

    @Test
    fun `容器有进度行也不显示进度条`() {
        assertNull("文件夹与系列不存在「读到第几页」", progressForEntry(container(), progress(2, 5)))
    }

    @Test
    fun `书条目未读不显示进度条`() {
        assertNull(progressForEntry(book(), null))
        assertNull(progressForEntry(container(), null))
    }

    // ---------- openStartIndex（始终从第一页打开） ----------

    @Test
    fun `开关开启无视已有进度固定第1页`() {
        assertEquals(0, openStartIndex(progress(7, 10), alwaysFirstPage = true, pageCount = 10))
        assertEquals(0, openStartIndex(null, alwaysFirstPage = true, pageCount = 10))
    }

    @Test
    fun `开关关闭未读返回0`() {
        assertEquals(0, openStartIndex(null, alwaysFirstPage = false, pageCount = 10))
    }

    @Test
    fun `开关关闭时定位到上次页码`() {
        assertEquals(5, openStartIndex(progress(5, 10), alwaysFirstPage = false, pageCount = 10))
    }

    @Test
    fun `开关关闭越界进度钳制到末页`() {
        assertEquals(9, openStartIndex(progress(12, 10), alwaysFirstPage = false, pageCount = 10))
    }
}
