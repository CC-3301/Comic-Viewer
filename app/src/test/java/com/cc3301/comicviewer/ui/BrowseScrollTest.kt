package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 滚动活动键（票 #109 r5，量测窗口的开关信号）：**可见区变化** 或 **滚动偏移变化** 都算一次活动。
 *
 * 这条口径直接决定真机上的行数与 `itemsComposed`：`snapshotFlow` 只在键变化时发一次，
 * 键漏了偏移 ⇒ 慢拖期间窗口被静止判据切开（一段连续滚动落成多行、滚动期间的条目重组落在窗口外计不到）。
 */
class BrowseScrollTest {

    @Test
    fun `偏移变化也算一次滚动活动`() {
        assertNotEquals(
            "慢拖时可见区几乎不变、偏移每帧在变：漏了偏移窗口就会在滚动中途被切开",
            browseScrollActivityKey(visible = listOf(3, 4, 5), scrollOffset = 0),
            browseScrollActivityKey(visible = listOf(3, 4, 5), scrollOffset = 40),
        )
    }

    @Test
    fun `可见区变化也算一次滚动活动`() {
        assertNotEquals(
            "跨过一格、偏移恰好归零时也得算一次活动",
            browseScrollActivityKey(visible = listOf(3, 4, 5), scrollOffset = 40),
            browseScrollActivityKey(visible = listOf(4, 5, 6), scrollOffset = 0),
        )
    }

    @Test
    fun `两者都不变时键不变`() {
        assertEquals(
            "键不变 snapshotFlow 不重复发：静止期不会被当成滚动活动",
            browseScrollActivityKey(visible = listOf(3, 4, 5), scrollOffset = 40),
            browseScrollActivityKey(visible = listOf(3, 4, 5), scrollOffset = 40),
        )
    }
}
