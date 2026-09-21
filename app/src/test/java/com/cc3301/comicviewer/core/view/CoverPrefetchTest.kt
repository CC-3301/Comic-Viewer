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

    // ---------- 预取记帐本（票 #108 r2 + r3）----------

    /** 全部走来源字节通路的候选（`viaSourceBytes = true`） */
    private fun bytes(vararg ids: String): List<CoverPrefetch.Candidate> =
        ids.map { CoverPrefetch.Candidate(it, viaSourceBytes = true) }

    private val bytesCandidates = bytes("a", "b", "c", "d")

    @Test
    fun `同一个窗口只发一次 在飞的不重复发`() {
        val ledger = CoverPrefetchLedger()

        val first = ledger.begin(0..3, bytesCandidates)
        assertEquals(listOf("a", "b", "c", "d"), first)
        assertEquals("在飞的还没结算，下一帧不能重发", emptyList<String>(), ledger.begin(0..3, bytesCandidates))
    }

    @Test
    fun `拿到字节后不再预取`() {
        val ledger = CoverPrefetchLedger()
        ledger.begin(0..1, bytesCandidates).forEach { ledger.settle(it, PrefetchOutcome.Loaded) }

        assertEquals("已拿到的不再发", emptyList<String>(), ledger.begin(0..1, bytesCandidates))
        assertEquals("窗口里没取过的照发", listOf("c", "d"), ledger.begin(2..3, bytesCandidates))
    }

    @Test
    fun `被取消的那批要放回 下一次窗口变化仍能预取`() {
        // 这是 r1 的回归用例：r1 在**发请求之前**就登记，不区分「取消」，
        // 于是快速滑动（窗口每帧都在变 → collectLatest 取消上一批）过后的条目在本会话内永远不会再被预取
        val ledger = CoverPrefetchLedger()
        val batch = ledger.begin(0..1, bytesCandidates)

        ledger.release(batch)

        assertEquals("取消后放回：下一次还能预取这批", listOf("a", "b"), ledger.begin(0..1, bytesCandidates))
    }

    @Test
    fun `来源明确说没有封面时不反复重试`() {
        // 票 #108 r3 评审 P2：负结果（来源返回 null）跟「这一次调用失败」是两件事——
        // 前者反复重试是纯浪费（Komga 容器行的兜底链一次最多 4 个请求，滚一下就会把整窗重发一遍）
        val ledger = CoverPrefetchLedger()
        ledger.begin(0..0, bytesCandidates).forEach { ledger.settle(it, PrefetchOutcome.Absent) }

        assertEquals("明确无封面的不再发", emptyList<String>(), ledger.begin(0..0, bytesCandidates))
    }

    @Test
    fun `调用失败仍放回可重试`() {
        val ledger = CoverPrefetchLedger()
        ledger.begin(0..0, bytesCandidates).forEach { ledger.settle(it, PrefetchOutcome.Failed) }

        assertEquals("失败不是「无封面」：下次窗口变化仍会试", listOf("a"), ledger.begin(0..0, bytesCandidates))
    }

    @Test
    fun `uri 行不进预取`() {
        // 票 #108 r3 评审 P1：本地/SAF 的封面行由系统解 uri（`CoverThumb` 的 fromUri 分支），
        // **从不调 `coverBytes`**——预取对它只是白读整张图，还会挤占同一份字节缓存
        val mixed = listOf(
            CoverPrefetch.Candidate("local", viaSourceBytes = false),
            CoverPrefetch.Candidate("zip", viaSourceBytes = true),
            CoverPrefetch.Candidate("smb", viaSourceBytes = true),
        )

        assertEquals("只发走字节通路的那两条", listOf("zip", "smb"), CoverPrefetchLedger().begin(0..2, mixed))
    }

    @Test
    fun `窗口越界不会取到列表外的条目`() {
        val ledger = CoverPrefetchLedger()

        assertEquals(
            "总列表只有 4 条，开到 9 的窗口也不会崩、也不多取",
            listOf("d"),
            ledger.begin(3..9, bytesCandidates),
        )
    }
}
