package com.cc3301.comicviewer.core.sort

import com.cc3301.comicviewer.core.source.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全局排序设置纯逻辑（票 #29 / 票 #80，spec 故事 10-14）：默认方向 = 现状（名称升序、时间类新→旧）、
 * 三个类别各自记方向、选一项即类别与方向同时生效（票 #80 起不再有「点当前类别即反向」）、
 * 方向在展示层整份翻转。纯函数，不依赖 Android。
 */
class SortSettingTest {

    @Test
    fun `默认设置 名称正向 三个类别默认都是正向即现状`() {
        val setting = SortSetting()

        assertEquals(SortMode.NAME, setting.mode)
        assertEquals(SortDirection.FORWARD, setting.directionOf(SortMode.NAME))     // 名称升序
        assertEquals(SortDirection.FORWARD, setting.directionOf(SortMode.MODIFIED_TIME)) // 新→旧
        assertEquals(SortDirection.FORWARD, setting.directionOf(SortMode.RELEASE_TIME))  // 新→旧
    }

    @Test
    fun `选类别与方向 一步到位`() {
        val setting = SortSetting().select(SortMode.MODIFIED_TIME, SortDirection.REVERSE)

        assertEquals(SortMode.MODIFIED_TIME, setting.mode)
        assertEquals(SortDirection.REVERSE, setting.directionOf(SortMode.MODIFIED_TIME))
        assertEquals("只有被选的那一格变：名称仍是升序", SortDirection.FORWARD, setting.directionOf(SortMode.NAME))
    }

    @Test
    fun `方向按类别各记一份 切走再选回来仍是原方向`() {
        // 名称降序 → 切到修改时间（新→旧）→ 名称那格仍是降序（票 #80 AC）
        val away = SortSetting(mode = SortMode.NAME, nameDirection = SortDirection.REVERSE)
            .select(SortMode.MODIFIED_TIME, SortDirection.FORWARD)

        assertEquals(SortMode.MODIFIED_TIME, away.mode)
        assertEquals(SortDirection.FORWARD, away.directionOf(SortMode.MODIFIED_TIME))
        assertEquals(SortDirection.REVERSE, away.directionOf(SortMode.NAME))
    }

    @Test
    fun `三个类别各自记方向 互不影响`() {
        val setting = SortSetting()
            .select(SortMode.NAME, SortDirection.REVERSE)           // 名称 → 降序
            .select(SortMode.MODIFIED_TIME, SortDirection.REVERSE)  // 修改时间 → 旧→新
            .select(SortMode.RELEASE_TIME, SortDirection.FORWARD)   // 切到发布时间：前两类的方向都还留着

        assertEquals(SortMode.RELEASE_TIME, setting.mode)
        assertEquals(SortDirection.REVERSE, setting.directionOf(SortMode.NAME))
        assertEquals(SortDirection.REVERSE, setting.directionOf(SortMode.MODIFIED_TIME))
        assertEquals(SortDirection.FORWARD, setting.directionOf(SortMode.RELEASE_TIME))
    }

    @Test
    fun `落盘键解析 未知或缺失回退正向`() {
        assertEquals(SortDirection.FORWARD, SortDirection.fromKey(null))
        assertEquals(SortDirection.FORWARD, SortDirection.fromKey("bogus"))
        assertEquals(SortDirection.REVERSE, SortDirection.fromKey("REVERSE"))
    }

    @Test
    fun `展示层施加方向 正向原样 反向整份翻转`() {
        val entries = listOf("cbz", "ep 2", "ep 10", "series-a")

        assertEquals(entries, entries.applySortDirection(SortDirection.FORWARD))
        assertEquals(listOf("series-a", "ep 10", "ep 2", "cbz"), entries.applySortDirection(SortDirection.REVERSE))
    }
}
