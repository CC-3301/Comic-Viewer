package com.cc3301.comicviewer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 滚动活动键（票 #109 r5，量测窗口的开关信号）：**可见区变化** 或 **滚动偏移变化** 都算一次活动。
 *
 * 锁的是**两档滚动状态上的取值属性本身**（[LazyListState.scrollActivity] / [LazyGridState.scrollActivity]），
 * 不是某个中间工厂：任一属性漏掉 `firstVisibleItemScrollOffset`（正是本轮要防的回归）这里就红。
 *
 * 未组合时可见区是空集（`layoutInfo` 没有内容），因此这两个算例只让**偏移**变一个变量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseScrollTest {

    private fun listAt(scrollOffset: Int) =
        LazyListState(firstVisibleItemIndex = 3, firstVisibleItemScrollOffset = scrollOffset)

    private fun gridAt(scrollOffset: Int) =
        LazyGridState(firstVisibleItemIndex = 3, firstVisibleItemScrollOffset = scrollOffset)

    @Test
    fun `列表档偏移变化也算一次滚动活动`() {
        assertNotEquals(
            "慢拖时可见区几乎不变、偏移每帧在变：属性漏了偏移，窗口就会在滚动中途被切开",
            listAt(0).scrollActivity,
            listAt(40).scrollActivity,
        )
    }

    @Test
    fun `网格档偏移变化也算一次滚动活动`() {
        assertNotEquals(
            "网格档与列表档同一口径（两处各写一份取值，各自都得带上偏移）",
            gridAt(0).scrollActivity,
            gridAt(40).scrollActivity,
        )
    }

    @Test
    fun `两者都不变时键不变`() {
        assertEquals(
            "键不变 snapshotFlow 不重复发：静止期不会被当成滚动活动",
            listAt(40).scrollActivity,
            listAt(40).scrollActivity,
        )
        assertEquals(gridAt(40).scrollActivity, gridAt(40).scrollActivity)
    }
}
