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
 * 键 =（排序类别 + 该类方向）+「旧序残留」＝当前展示顺序还不是当前设置的产物时的条目 id 序列。
 * 排序设置变化是唯一触发源，但它会在**两个**时刻各造成一次重排：点排序那一刻，以及换类别后
 * 新顺序异步落地那一帧——两次都得换键，否则后一次重排会带着旧锚点把视口带走（r2 的 F1）。
 *
 * 这里只钉纯函数（不依赖 Android）：什么变化换键（= 复位），什么变化不换键（= 不复位）。
 * 参数含义：[enumeratedMode] 是当前条目按哪个类别列的（还没列出来传 null）。
 */
class BrowseScrollResetTest {

    /** 当前展示的条目顺序（方向已由展示层施加） */
    private val displayed = listOf("第10话", "第2话", "封面")

    private fun key(
        setting: SortSetting = SortSetting(),
        enumeratedMode: SortMode? = setting.mode,
        ids: List<String>? = displayed,
    ) = browseScrollResetKey(setting, enumeratedMode, ids)

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
        assertNotEquals("名称 A→Z 点成 Z→A：展示顺序整份翻转（同步重排）", key(SortSetting()), key(reversed))
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
     * 守卫验收第 2 条（换排序类别也回到顶部）——r2 的 F1：换类别是**异步重排**，
     * `produceState` 保留旧值，落地那一帧必须再换一次键，否则旧锚点会把视口带到「旧第一项」的新下标。
     */
    @Test
    fun `换类别后新顺序落地那一帧换键（守卫验收第2条）`() {
        val switched = SortSetting(mode = SortMode.MODIFIED_TIME)
        val clicked = key(setting = switched, enumeratedMode = SortMode.NAME, ids = listOf("第2话", "封面"))
        val landed = key(setting = switched, enumeratedMode = SortMode.MODIFIED_TIME, ids = listOf("封面", "第2话"))

        assertNotEquals("点击帧：设置已换档但仍停着旧顺序", key(SortSetting()), clicked)
        assertNotEquals("落地帧：新顺序落地必须再复位一次，视口才不会被旧锚点带走", clicked, landed)
    }

    /** 守卫「从阅读器返回」「子目录返回上级」：重新进屏时枚举从 null 落地，键不得跳变。 */
    @Test
    fun `重新进屏枚举从 null 落地不换键（从阅读器返回、子目录返回上级）`() {
        val setting = SortSetting()

        assertEquals(
            "进屏第一帧（还没列出来）与枚举落地帧是同一个键：rememberSaveable 的位置恢复不被冲掉",
            key(setting, enumeratedMode = null, ids = null),
            key(setting, enumeratedMode = SortMode.NAME, ids = displayed),
        )
    }

    /** 守卫「下拉更新」：重列同一档的条目不换键，条目增删也不复位（旧序残留为空）。 */
    @Test
    fun `下拉更新重列不换键（条目增删也不复位）`() {
        val setting = SortSetting()

        assertEquals(
            "同一顺序的新列表实例",
            key(setting, ids = listOf("第10话", "第2话", "封面")),
            key(setting, ids = listOf("第10话", "第2话", "封面")),
        )
        assertEquals(
            "重列后条目多了/少了：仍是当前档的产物，不进键",
            key(setting, ids = displayed),
            key(setting, ids = listOf("第10话", "第2话", "封面", "新加的一本")),
        )
    }

    /** 守卫「切视图档位」：视图档位不在键里（键的构成固定为这三个值）。 */
    @Test
    fun `切视图档位不换键（键里没有视图档位）`() {
        assertEquals(
            "键 = （排序类别, 当前类别方向, 旧序残留）：视图档位与条目顺序都不在键里",
            BrowseScrollResetKey(SortMode.NAME, SortDirection.FORWARD, staleIds = null),
            key(SortSetting()),
        )
    }
}
