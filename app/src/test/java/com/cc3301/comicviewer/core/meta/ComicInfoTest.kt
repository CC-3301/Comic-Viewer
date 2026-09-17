package com.cc3301.comicviewer.core.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

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

    @Test
    fun `发布日期排序键在不同时区下顺序不变`() {
        // 票 #22：文件源的发布时间排序把 LocalDate 转成「本地时区当日 00:00」的毫秒（见 toEpochMillis），
        // 日期先后关系与时区无关，因此设备时区不会把两本书排反；无元数据的书仍按 mtime 回退。
        val dates = listOf(ReleaseDate(2019, 12, 31), ReleaseDate(2020, 1, 1), ReleaseDate(2020, 1, 2))
        val zones = listOf(ZoneId.of("UTC"), ZoneId.of("Asia/Shanghai"), ZoneId.of("America/New_York"))

        assertEquals(listOf(dates, dates, dates), zones.map { zone -> dates.sortedBy { it.toEpochMillis(zone) } })
    }
}
