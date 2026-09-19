package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.sort.SortDirection
import com.cc3301.comicviewer.core.sort.SortSetting
import com.cc3301.comicviewer.core.source.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 浏览页两档滚动状态的复位键（票 #58）：排序设置变化后回到顶部。
 *
 * 键只取排序设置里决定条目顺序的那两个值（类别 + 该类方向）——排序设置变化是唯一触发源。
 * 这里只钉纯函数（不依赖 Android）：什么变化换键（= 复位），什么变化不换键（= 不复位）。
 */
class BrowseScrollResetTest {

    private fun key(setting: SortSetting = SortSetting()) = browseScrollResetKey(setting)

    @Test
    fun `排序类别互切换键`() {
        assertNotEquals(
            "名称 → 修改时间：换类别，顺序会变",
            key(SortSetting()),
            key(SortSetting(mode = SortMode.MODIFIED_TIME)),
        )
        assertNotEquals(
            "修改时间 → 发布时间：三档之间互切同理",
            key(SortSetting(mode = SortMode.MODIFIED_TIME)),
            key(SortSetting(mode = SortMode.RELEASE_TIME)),
        )
    }

    @Test
    fun `点当前类别再点一次（同类别反向）换键`() {
        val reversed = SortSetting().select(SortMode.NAME)

        assertEquals("前置：确实只是把当前类别反向", SortDirection.REVERSE, reversed.directionOf(SortMode.NAME))
        assertNotEquals("名称 A→Z 点成 Z→A：展示顺序整份翻转", key(SortSetting()), key(reversed))
    }

    @Test
    fun `只翻别的类别方向不换键`() {
        val otherReversed = SortSetting(releaseDirection = SortDirection.REVERSE)

        assertEquals(
            "展示顺序由当前类别（名称）决定：其它类别那档的方向不参与",
            key(SortSetting()),
            key(otherReversed),
        )
    }

    /**
     * 守卫票面「**非排序变化**不触发复位」这条验收口径。
     *
     * 键只由排序设置的两个值构成，因此重新进屏时枚举从 null 落地、下拉更新重列、切视图档位、
     * 进子目录/返回上级、从阅读器返回——这些时刻键都不会跳变，`rememberSaveable` 的位置恢复
     * 因此不会被新状态冲掉（键里若混进条目顺序，就会出现「从阅读器返回掉回顶部」的回归）。
     * 第一个断言把键的构成钉死：加了任何随枚举/视图跳变的字段，这条构造与相等断言都立不住。
     */
    @Test
    fun `复位键只由排序设置决定 与条目枚举结果无关（守卫 非排序变化不得复位）`() {
        assertEquals(
            "键 = （排序类别, 当前类别方向）：条目序列、枚举是否落地、视图档位都不在键里",
            BrowseScrollResetKey(SortMode.NAME, SortDirection.FORWARD),
            key(SortSetting()),
        )
        assertEquals(
            "同一份排序设置重复取键恒等（非排序变化的重组不换键）",
            key(SortSetting()),
            key(SortSetting()),
        )
    }
}
