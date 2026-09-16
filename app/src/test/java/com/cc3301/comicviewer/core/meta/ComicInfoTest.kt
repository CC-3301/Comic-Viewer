package com.cc3301.comicviewer.core.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ComicInfo.xml 发布时间解析（票 10，spec 故事 12/13） */
class ComicInfoTest {

    private fun xml(body: String) = body.toByteArray()

    @Test
    fun `完整年月日`() {
        val date = parseReleaseDate(xml("<ComicInfo><Year>2020</Year><Month>7</Month><Day>15</Day></ComicInfo>"))
        assertEquals(ReleaseDate(2020, 7, 15), date)
        assertEquals(20200715L, date!!.sortKey)
    }

    @Test
    fun `仅年份时月日按1`() {
        assertEquals(ReleaseDate(2021, 1, 1), parseReleaseDate(xml("<ComicInfo><Year>2021</Year></ComicInfo>")))
    }

    @Test
    fun `缺年份返回空由调用方回退修改时间`() {
        assertNull(parseReleaseDate(xml("<ComicInfo><Month>7</Month><Day>1</Day></ComicInfo>")))
        assertNull(parseReleaseDate(xml("<ComicInfo><Title>无日期</Title></ComicInfo>")))
    }

    @Test
    fun `损坏或非 XML 返回空`() {
        assertNull(parseReleaseDate(xml("not xml at all")))
        assertNull(parseReleaseDate(ByteArray(0)))
    }

    @Test
    fun `非数字或越界月份被容错`() {
        assertNull(parseReleaseDate(xml("<ComicInfo><Year>abc</Year></ComicInfo>")))
        assertEquals(ReleaseDate(2020, 12, 31), parseReleaseDate(xml("<ComicInfo><Year>2020</Year><Month>13</Month><Day>40</Day></ComicInfo>")))
    }

    @Test
    fun `可比较以用于发布时间排序`() {
        val a = parseReleaseDate(xml("<ComicInfo><Year>2019</Year><Month>12</Month></ComicInfo>"))!!
        val b = parseReleaseDate(xml("<ComicInfo><Year>2020</Year><Month>1</Month></ComicInfo>"))!!
        assertTrue(a < b)
        assertEquals(0, a.compareTo(a))
    }
}
