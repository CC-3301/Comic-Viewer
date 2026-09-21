package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面预取窗口（票 #108 E2-B）：可见区 ±1 屏，夹在列表两端。
 * 「±1 屏」按**当前可见条目数**折算（见 [CoverPrefetch] 的口径），因此这些算例里的可见条目数就是那一屏的规模。
 */
class CoverPrefetchTest {

    @Test
    fun `列表顶部 上侧夹到 0 下侧扩一屏`() {
        // 可见 7 条（0..6）→ 上侧只剩 0，下侧扩到 6 + 7 = 13
        assertEquals("顶部首屏：0..13", 0..13, CoverPrefetch.window(firstVisible = 0, lastVisible = 6, total = 100))
    }

    @Test
    fun `列表中部 上下各扩一屏`() {
        assertEquals("中间屏：3..23", 3..23, CoverPrefetch.window(firstVisible = 10, lastVisible = 16, total = 100))
    }

    @Test
    fun `列表尾部 下侧夹到最后一条`() {
        // 可见 6 条（94..99）→ 下侧只剩 99，上侧扩到 94 − 6 = 88
        assertEquals("尾部屏：88..99", 88..99, CoverPrefetch.window(firstVisible = 94, lastVisible = 99, total = 100))
    }

    @Test
    fun `一屏装得下整份列表时窗口就是全列表`() {
        assertEquals("单条：0..0", 0..0, CoverPrefetch.window(firstVisible = 0, lastVisible = 0, total = 1))
        assertEquals("5 条全可见：0..4", 0..4, CoverPrefetch.window(firstVisible = 0, lastVisible = 4, total = 5))
    }

    @Test
    fun `空列表或非法区间不预取`() {
        assertNull("空列表", CoverPrefetch.window(firstVisible = 0, lastVisible = 0, total = 0))
        assertNull("区间反了（布局未就绪）", CoverPrefetch.window(firstVisible = 5, lastVisible = 4, total = 10))
        assertNull("可见区越过末尾（换列表那一帧）", CoverPrefetch.window(firstVisible = 10, lastVisible = 12, total = 10))
    }

    @Test
    fun `并发上界是 4`() {
        assertEquals("预取同时向来源要的封面数上限", 4, CoverPrefetch.MAX_CONCURRENT_LOADS)
    }
}
