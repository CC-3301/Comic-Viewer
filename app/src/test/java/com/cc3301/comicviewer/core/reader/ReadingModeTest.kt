package com.cc3301.comicviewer.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读模式与单页方向（票 07）：持久化键解析容错 + 方向语义 */
class ReadingModeTest {

    @Test
    fun `模式默认条漫 未知键回退条漫`() {
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromKey(null))
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromKey("nonsense"))
        assertEquals(ReadingMode.PAGED, ReadingMode.fromKey(ReadingMode.PAGED.key))
        assertEquals(ReadingMode.WEBTOON, ReadingMode.fromKey(ReadingMode.WEBTOON.key))
    }

    @Test
    fun `方向默认左到右 未知键回退`() {
        assertEquals(PageDirection.LTR, PageDirection.fromKey(null))
        assertEquals(PageDirection.LTR, PageDirection.fromKey("xxx"))
        assertEquals(PageDirection.RTL, PageDirection.fromKey(PageDirection.RTL.key))
    }

    @Test
    fun `仅右到左反转横向布局`() {
        assertFalse(PageDirection.LTR.reverseLayout)
        assertTrue(PageDirection.RTL.reverseLayout)
    }
}
