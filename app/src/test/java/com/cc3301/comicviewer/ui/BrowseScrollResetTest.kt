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
 * 键由两半组成——「排序设置里决定顺序的那部分」（类别 + 该类当前方向）与「当前展示的条目 id 序列」。
 * 前一半管排序设置本身（类别切换、同类反向）；后一半管异步那一段：换排序类别会重新枚举，旧顺序的条目
 * 会先在屏上停一帧，光看设置的话新顺序落地时视口会按「原先可见的那一项」重新锚定、又被带走。
 *
 * 这里只钉纯函数（不依赖 Android）：什么变化换键（= 复位），什么变化不换键（= 不复位）。
 */
class BrowseScrollResetTest {

    /** 当前展示的条目顺序（两档共用同一份枚举结果，方向已由展示层施加） */
    private val displayed = listOf("第10话", "第2话", "封面")

    private fun key(setting: SortSetting = SortSetting(), ids: List<String>? = displayed) =
        browseScrollResetKey(setting, ids)

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
        val otherReversed = SortSetting(modifiedDirection = SortDirection.REVERSE)

        assertEquals(
            "展示顺序由当前类别（名称）决定：修改时间那档的方向不参与",
            key(SortSetting()),
            key(otherReversed),
        )
    }

    @Test
    fun `当前展示的顺序变了就换键（新顺序落地的那一帧）`() {
        assertNotEquals(
            "重新枚举拿到了另一份顺序",
            key(SortSetting(), displayed),
            key(SortSetting(), displayed.reversed()),
        )
    }

    @Test
    fun `同一顺序再次枚举不换键（下拉更新不被额外复位）`() {
        assertEquals(
            "下拉更新重列同一目录：顺序一模一样，只是换了一份列表实例",
            key(SortSetting(), listOf("第10话", "第2话", "封面")),
            key(SortSetting(), listOf("第10话", "第2话", "封面")),
        )
    }
}
