package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---------- 预取记帐本（票 #108 r2 ~ r4）----------

    /** 全部走来源字节通路的候选（`viaSourceBytes = true`） */
    private fun bytes(vararg ids: String): List<CoverPrefetch.Candidate> =
        ids.map { CoverPrefetch.Candidate(it, viaSourceBytes = true) }

    private val bytesCandidates = bytes("a", "b", "c", "d")

    /** 来源字节缓存里什么都没有（绝大多数用例的起点） */
    private val noneCached: (CoverPrefetch.Candidate) -> Boolean = { false }

    @Test
    fun `同一个窗口只发一次 在飞的不重复发`() {
        val ledger = CoverPrefetchLedger()

        val first = ledger.begin(0..3, bytesCandidates, noneCached)
        assertEquals(listOf("a", "b", "c", "d"), first)
        assertEquals("在飞的还没结算，下一帧不能重发", emptyList<String>(), ledger.begin(0..3, bytesCandidates, noneCached))
    }

    @Test
    fun `字节缓存里已有的不再发`() {
        // 票 #108 r4：预取的唯一真相是**来源的字节缓存**（不再是记帐本里的一个「已预取」集合）
        val ledger = CoverPrefetchLedger()

        assertEquals(
            "缓存里已有 a、b ⇒ 只发 c、d",
            listOf("c", "d"),
            ledger.begin(0..3, bytesCandidates) { it.id == "a" || it.id == "b" },
        )
    }

    @Test
    fun `缓存淘汰后滚回来会重新预取`() {
        // r2/r3 的 P2：旧口径把「已预取」永久记在记帐本里，与字节缓存的淘汰（64 条 ∩ 8MB，淘汰最旧）
        // 无联动 ⇒ 长列表滚远再滚回时，界面以为已有、缓存里其实已经空了，这一层预取就彻底失效。
        // 现在判据问的是缓存本身：淘汰后再进窗口 → 重新发（旧口径下这条用例会失败）。
        val ledger = CoverPrefetchLedger()
        var cached = true

        assertEquals("缓存里还有时：不发", emptyList<String>(), ledger.begin(0..0, bytesCandidates) { cached })

        cached = false // 被上界淘汰（插入序淘汰最旧）
        assertEquals("淘汰后：重新预取", listOf("a"), ledger.begin(0..0, bytesCandidates) { cached })
    }

    @Test
    fun `被取消的那批要放回 下一次窗口变化仍能预取`() {
        // 这是 r1 的回归用例：r1 在**发请求之前**就登记，不区分「取消」，
        // 于是快速滑动（窗口每帧都在变 → collectLatest 取消上一批）过后的条目在本会话内永远不会再被预取
        val ledger = CoverPrefetchLedger()
        val batch = ledger.begin(0..1, bytesCandidates, noneCached)

        ledger.release(batch)

        assertEquals("取消后放回：下一次还能预取这批", listOf("a", "b"), ledger.begin(0..1, bytesCandidates, noneCached))
    }

    @Test
    fun `取不到时仍可重试 不是永久失效`() {
        // 票 #108 r4 评审 P2-1：两个 coverBytes 实现都把失败吞成 null（SMB 瞬断/ Komga 超时都长这样），
        // 所以 null 不能当「这个条目永远没有封面」——r3 的 Absent 就是那么写的，会把瞬断错记成永久
        val ledger = CoverPrefetchLedger()
        ledger.begin(0..0, bytesCandidates, noneCached).forEach { ledger.settle(it, loaded = false) }

        assertEquals("瞬断后：下一次窗口变化仍会重试", listOf("a"), ledger.begin(0..0, bytesCandidates, noneCached))
    }

    @Test
    fun `到尝试上限后不再重试`() {
        // 另一半：可重试不等于无限重试——r2 之前就是「每次窗口变化把整窗重发」
        // （Komga 容器行的兜底链一次最多 4 个请求，滚一下就被重发一遍）
        val ledger = CoverPrefetchLedger()
        repeat(CoverPrefetchLedger.MAX_ATTEMPTS_PER_SESSION) {
            ledger.begin(0..0, bytesCandidates, noneCached).forEach { ledger.settle(it, loaded = false) }
        }

        assertEquals("用满本会话的次数上限后不再发", emptyList<String>(), ledger.begin(0..0, bytesCandidates, noneCached))
    }

    @Test
    fun `拿到字节后计数归零`() {
        val ledger = CoverPrefetchLedger()
        repeat(CoverPrefetchLedger.MAX_ATTEMPTS_PER_SESSION) {
            ledger.begin(0..0, bytesCandidates, noneCached).forEach { ledger.settle(it, loaded = false) }
        }
        ledger.settle("a", loaded = true)

        assertEquals(
            "拿到过就不算失败次数：字节又被淘汰时还能再试",
            listOf("a"),
            ledger.begin(0..0, bytesCandidates, noneCached),
        )
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

        assertEquals(
            "只发走字节通路的那两条",
            listOf("zip", "smb"),
            CoverPrefetchLedger().begin(0..2, mixed, noneCached),
        )
    }

    @Test
    fun `窗口越界不会取到列表外的条目`() {
        val ledger = CoverPrefetchLedger()

        assertEquals(
            "总列表只有 4 条，开到 9 的窗口也不会崩、也不多取",
            listOf("d"),
            ledger.begin(3..9, bytesCandidates, noneCached),
        )
    }

    // ---------- 预取资格（票 #135）----------

    @Test
    fun `预取资格：位图已在就不问字节缓存`() {
        var bytesAsked = 0

        val available = CoverPrefetch.alreadyAvailable(
            hasBitmap = { true },
            hasCachedBytes = { bytesAsked++; false },
        )

        assertTrue("位图在封面分区里 ⇒ 这一条不用再预取", available)
        assertEquals("位图那条判定先命中：字节缓存一次都不问", 0, bytesAsked)
    }

    @Test
    fun `预取资格：位图不在但字节已在也算已有`() {
        var bytesAsked = 0

        val available = CoverPrefetch.alreadyAvailable(
            hasBitmap = { false },
            hasCachedBytes = { bytesAsked++; true },
        )

        assertTrue("字节在来源缓存里 ⇒ 不占预取名额（只省一次来源往返，不需再取）", available)
        assertEquals("位图没命中才轮到字节缓存", 1, bytesAsked)
    }

    @Test
    fun `预取资格：两者都没有才需要预取`() {
        assertFalse(CoverPrefetch.alreadyAvailable(hasBitmap = { false }, hasCachedBytes = { false }))
    }
}
