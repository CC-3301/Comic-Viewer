package com.cc3301.comicviewer.core.sort

import com.cc3301.comicviewer.core.source.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全局排序设置纯逻辑（票 #29，spec 故事 10-14）：默认方向 = 现状（名称 A→Z、时间类新→旧）、
 * 三个类别各自记方向、点当前类别即反向、方向在展示层整份翻转。纯函数，不依赖 Android。
 */
class SortSettingTest {

    @Test
    fun `默认设置 名称正向 三个类别默认都是正向即现状`() {
        val setting = SortSetting()

        assertEquals(SortMode.NAME, setting.mode)
        assertEquals(SortDirection.FORWARD, setting.directionOf(SortMode.NAME))     // 名称 A→Z
        assertEquals(SortDirection.FORWARD, setting.directionOf(SortMode.MODIFIED_TIME)) // 新→旧
        assertEquals(SortDirection.FORWARD, setting.directionOf(SortMode.RELEASE_TIME))  // 新→旧
    }

    @Test
    fun `点当前类别再点一次即反向`() {
        val once = SortSetting(mode = SortMode.MODIFIED_TIME).select(SortMode.MODIFIED_TIME)
        assertEquals(SortMode.MODIFIED_TIME, once.mode)
        assertEquals(SortDirection.REVERSE, once.directionOf(SortMode.MODIFIED_TIME))

        // 再点一次转回正向，不会一路往下转
        val twice = once.select(SortMode.MODIFIED_TIME)
        assertEquals(SortDirection.FORWARD, twice.directionOf(SortMode.MODIFIED_TIME))
    }

    @Test
    fun `点别的类别 切过去并用该类自己记住的方向`() {
        val reversed = SortSetting(mode = SortMode.NAME)
            .select(SortMode.NAME)              // 名称 → 反向
            .select(SortMode.RELEASE_TIME)      // 切到发布时间：仍是它自己的默认正向

        assertEquals(SortMode.RELEASE_TIME, reversed.mode)
        assertEquals(SortDirection.FORWARD, reversed.directionOf(SortMode.RELEASE_TIME))
        assertEquals(SortDirection.REVERSE, reversed.directionOf(SortMode.NAME))
    }

    @Test
    fun `三个类别各自记方向 互不影响`() {
        val setting = SortSetting()
            .select(SortMode.NAME)              // 名称 → 反向
            .select(SortMode.MODIFIED_TIME)     // 切到修改时间（默认正向）
            .select(SortMode.MODIFIED_TIME)     // 修改时间 → 反向
            .select(SortMode.RELEASE_TIME)      // 切到发布时间：前两类的方向都还留着

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
