package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条漫滚动预取的窗口（[PagePrefetch.pages]）与记帐本（[PagePrefetchLedger]）。
 *
 * 窗口口径：当前页之后 N 页 + 之前 M 页，随当前页滑动；滚到下一页只多出末尾一页。
 * 记帐本口径：同一页只领一次、在屏页与已有位图的页不领、窗口一变只丢「还没开始」的那批。
 */
class PagePrefetchTest {

    @Test
    fun `窗口 = 当前页之后 N 页 + 之前 M 页 当前页自己不在窗里`() {
        assertEquals(
            "第 10 页：先备后面 7 页（11..17），再备前面 2 页（8..9）",
            listOf(11, 12, 13, 14, 15, 16, 17, 8, 9),
            PagePrefetch.pages(currentPage = 10, pageCount = 269, after = 7, before = 2),
        )
    }

    @Test
    fun `滚到下一页只多出末尾一页`() {
        val first = PagePrefetch.pages(currentPage = 10, pageCount = 269, after = 7, before = 2)
        val next = PagePrefetch.pages(currentPage = 11, pageCount = 269, after = 7, before = 2)

        // 后面那段（当前页之后）：10 时是 11..17，11 时是 12..18 ⇒ 只多解 N+8 这一页
        assertEquals("后面那段只多出 N+8", setOf(18), next.filter { it > 11 }.toSet() - first.filter { it > 10 }.toSet())
        // 前面那段（当前页之前）：刚看过的那一页回到窗口里，但它已经在手（位图命中），不会重解
        assertEquals("前面那段多出的只有刚看过的那一页", setOf(10), next.filter { it < 11 }.toSet() - first.filter { it < 10 }.toSet())
        // 离开窗口的两页都不可惜：11 变成了当前页（由可见页那条路取解），8 是回看方向最旧的一页
        assertEquals("离开窗口的是刚变成当前页的 11 与最旧的 8", setOf(8, 11), first.toSet() - next.toSet())
    }

    @Test
    fun `窗口夹在书的首页与末页`() {
        assertEquals(
            "第 0 页：没有前面的页，后面也只到 N+7",
            listOf(1, 2, 3, 4, 5, 6, 7),
            PagePrefetch.pages(currentPage = 0, pageCount = 269, after = 7, before = 2),
        )
        assertTrue(
            "第 0 页的窗口里没有当前页",
            PagePrefetch.pages(currentPage = 0, pageCount = 269, after = 7, before = 2).none { it == 0 },
        )
        assertEquals(
            "末页：后面没有页，只剩前面两页",
            listOf(266, 267),
            PagePrefetch.pages(currentPage = 268, pageCount = 269, after = 7, before = 2),
        )
    }

    @Test
    fun `空书或当前页越界时没有预取窗`() {
        assertEquals("空书", emptyList<Int>(), PagePrefetch.pages(0, pageCount = 0, after = 7, before = 2))
        assertEquals("当前页为负", emptyList<Int>(), PagePrefetch.pages(-1, pageCount = 20, after = 7, before = 2))
        assertEquals("当前页越过末页", emptyList<Int>(), PagePrefetch.pages(20, pageCount = 20, after = 7, before = 2))
    }

    @Test
    fun `记帐本跳过已有位图与在屏页 同一页只领一次`() {
        val ledger = PagePrefetchLedger()
        val owner = Any()

        val first = ledger.begin(
            owner = owner,
            pages = listOf(1, 2, 3),
            excluded = setOf(2),
            cached = { it == 3 },
        )
        assertEquals("在屏页与已有位图的页不领", listOf(1), first)
        assertEquals(
            "已经在飞的第 1 页不重复领；当时被跳过的那两页这次不在屏、也没位图，可以领",
            listOf(2, 3),
            ledger.begin(owner, listOf(1, 2, 3), excluded = emptySet(), cached = { false }),
        )
    }

    @Test
    fun `没开始的任务可以丢 开始过的丢不掉`() {
        val ledger = PagePrefetchLedger()
        val owner = Any()
        ledger.begin(owner, pages = listOf(1, 2, 3), excluded = emptySet(), cached = { false })

        assertTrue("第 1 页开始解码", ledger.start(1, owner))

        val dropped = ledger.dropPending(keep = setOf(3))
        assertEquals("没开始、又不在新窗口里的第 2 页直接丢", listOf(2), dropped)
        assertFalse("已经被丢的那一页再也开始不了（任务不必等它跑）", ledger.start(2, owner))
        assertTrue("已经在解的第 1 页照样跑完", ledger.start(1, owner))
    }

    @Test
    fun `结束一条后可以重新领取`() {
        val ledger = PagePrefetchLedger()
        val owner = Any()
        ledger.begin(owner, pages = listOf(4), excluded = emptySet(), cached = { false })
        ledger.start(4, owner)
        ledger.settle(4, owner)

        assertEquals(
            "结束（成功 / 失败 / 取消）后这一页可再次领取",
            listOf(4),
            ledger.begin(Any(), listOf(4), emptySet()) { false },
        )
    }

    /**
     * 旧批次迟到的收尾不能动新任务：被丢掉的旧任务在 IO 侧结束时（或取消回调晚到），
     * 同一页可能已经被下一批重新领走——它的 [PagePrefetchLedger.settle] 必须按令牌识别、直接无效。
     */
    @Test
    fun `旧批次收尾不摘同页的新任务`() {
        val ledger = PagePrefetchLedger()
        val oldOwner = Any()
        val newOwner = Any()

        ledger.begin(oldOwner, pages = listOf(5), excluded = emptySet(), cached = { false })
        assertEquals("旧任务没开始就被窗口丢掉", listOf(5), ledger.dropPending(keep = emptySet()))
        assertFalse("被丢掉的旧任务开不了工", ledger.start(5, oldOwner))

        assertEquals("下一批把同一页重新领走", listOf(5), ledger.begin(newOwner, listOf(5), emptySet()) { false })
        assertTrue("新任务可以开工", ledger.start(5, newOwner))

        ledger.settle(5, oldOwner)

        assertTrue("旧任务收尾（或取消回调晚到）不影响新任务：领取还在", ledger.start(5, newOwner))
        assertEquals("新任务的领取仍在记帐本里", emptyList<Int>(), ledger.dropPending(keep = setOf(5)))

        ledger.settle(5, newOwner)
        assertEquals("新任务真的结束后才能重新领取", listOf(5), ledger.begin(Any(), listOf(5), emptySet()) { false })
    }
}
