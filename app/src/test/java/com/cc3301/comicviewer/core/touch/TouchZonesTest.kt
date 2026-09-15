package com.cc3301.comicviewer.core.touch

import org.junit.Assert.assertEquals
import org.junit.Test

/** 触摸区域类型 3 纯函数（票 05，AC：分区判定有单元测试） */
class TouchZonesTest {

    private val w = 1080f

    // ---------- 三等分判定 ----------

    @Test
    fun `左中右三分边界`() {
        assertEquals(TouchZone.LEFT, touchZoneAt(0f, w))
        assertEquals(TouchZone.LEFT, touchZoneAt(359.9f, w))
        assertEquals(TouchZone.CENTER, touchZoneAt(360f, w))
        assertEquals(TouchZone.CENTER, touchZoneAt(719.9f, w))
        assertEquals(TouchZone.RIGHT, touchZoneAt(720f, w))
        assertEquals(TouchZone.RIGHT, touchZoneAt(1079f, w))
    }

    @Test
    fun `非法宽度防御返回中区`() {
        assertEquals(TouchZone.CENTER, touchZoneAt(0f, 0f))
        assertEquals(TouchZone.CENTER, touchZoneAt(100f, -5f))
    }

    // ---------- 条漫滚动目标 ----------

    @Test
    fun `左区上一张 越界钳到首页`() {
        assertEquals(2, webtoonPrevTarget(3, 10))
        assertEquals(0, webtoonPrevTarget(0, 10))
        assertEquals(0, webtoonPrevTarget(1, 10))
    }

    @Test
    fun `右区下一张 越界钳到末页`() {
        assertEquals(4, webtoonNextTarget(3, 10))
        assertEquals(9, webtoonNextTarget(9, 10))
        assertEquals(9, webtoonNextTarget(8, 10))
    }

    @Test
    fun `单页书两区都停在0`() {
        assertEquals(0, webtoonPrevTarget(0, 1))
        assertEquals(0, webtoonNextTarget(0, 1))
    }
}
