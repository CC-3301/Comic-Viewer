package com.cc3301.comicviewer.core.data

import com.cc3301.comicviewer.core.source.displayFraction
import com.cc3301.comicviewer.core.source.isCompleted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表进度条取值（票 17 验收标准 4：与阅读进度一致；spec 故事 41/45） */
class ProgressProjectionTest {

    @Test
    fun `进行中的书部分填充`() {
        // displayFraction = (pageIndex + 1) / totalPages（进度按已读页数计）
        val progress = progressByBook(listOf(ReadingProgressEntity("a", 1, 4, 10L)))["a"]

        assertEquals(0.5f, progress!!.displayFraction, 0.0001f)
        assertFalse(progress.isCompleted)
        assertEquals(10L, progress.updatedAtMs)
    }

    @Test
    fun `读到最后一页满格`() {
        val progress = progressByBook(listOf(ReadingProgressEntity("a", 3, 4, 20L)))["a"]

        assertEquals(1.0f, progress!!.displayFraction, 0.0001f)
        assertTrue(progress.isCompleted)
    }

    @Test
    fun `没有进度的书不显示进度条`() {
        val byBook = progressByBook(listOf(ReadingProgressEntity("a", 1, 4, 10L)))

        assertNull(byBook["b"])
        assertEquals(setOf("a"), byBook.keys)
    }

    @Test
    fun `多本书各取其进度`() {
        val byBook = progressByBook(
            listOf(ReadingProgressEntity("a", 0, 10, 1L), ReadingProgressEntity("b", 9, 10, 2L)),
        )

        assertFalse(byBook.getValue("a").isCompleted)
        assertTrue(byBook.getValue("b").isCompleted)
    }
}
