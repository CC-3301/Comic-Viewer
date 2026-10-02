package com.cc3301.comicviewer.core.source.smb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SMB 会话探活心跳（票 #151 后它长在 [SmbSessionLifecycle] 里，这一段用**虚拟时间**锁住）。
 *
 * 为什么测这里而不是 `SmbjTransport`：smbj 的 `SMBClient`/`Connection`/`Session` 在单测里不可注入，
 * 「会话空闲被服务端作废」在 JVM 上跑不出来。心跳与退避都收在生命周期模块里，用假替身（会话建得起来）
 * + 虚拟时间把节奏钉死：
 *
 * ① 空闲时**每 30 秒**探一次（会话可能已被服务端回收，要在用户之前撞上并重建）；
 * ② 间隔内有真实读就**跳过这次**探活（会话刚被用过＝活着，不必再压一条读上去）；
 * ③ 探针失败按 **30 → 60 → 120 → 240 → 封顶 300** 退避，一次成功即回到 30 秒；
 * ④ **规则 5**：会话建立成功才起心跳（没被用过的来源不该被心跳拉起来联网）、
 *    `close()` 之后不再探，且 **close 是终态**——在飞的那次建会话落地后不得把循环复活
 *    （`close()` 先置已关闭再停循环，而建会话是慢 I/O，两者之间是竞态，释义见
 *    `SmbSessionLifecycle.startHeartbeat` 的 KDoc）；
 * ⑤ 每一拍都要上报 `(probed, ok, ms)`：真探过的才有 `ok`/`ms`，
 *    **没探过的那一拍不许报成「探活了」**（2026-09-29 那段里，探针成功不产行就是判读空白的根）。
 *    探针返回 `false` = 这一拍什么都没探（还没会话 / 已释放），见 `SmbSessionLifecycle.probeOnce`；
 * ⑥ **规则 6**：探针自己走的那次读不算用户活动（不排除掉的话心跳会退化成 60 秒一次）。
 *
 * **每个用例都用 `try/finally` 停掉心跳**：循环是常驻的，若某条断言先失败、又没停循环，
 * `runTest` 收尾时会一直推进虚拟时间等它结束——表现为 `OutOfMemoryError`（探针每次记一条时间），
 * 把真正的失败盖掉。
 *
 * 一处与旧用例不同的地方（**生产行为不变**）：建立会话靠「跑一次真读」，而每一次读都会记一次
 * 「会话被用过」⇒ 心跳起来后的**第一拍被那条读顶掉**，之后的节奏才是每 30 秒一次。因此下面的用例
 * 都先 `establishSession`（含那一次被顶掉的拍），断言的是**之后连续三拍**的间隔。
 */
class SmbSessionLifecycleHeartbeatTest {

    @Test
    fun `默认间隔 30 秒、退避上限 300 秒`() {
        // 这两个数就是 2026-09-28 定的口径：只注入小间隔的用例钉不住它们
        assertEquals("票 #113 的探活间隔", 30_000L, SmbSessionLifecycle.PROBE_INTERVAL_MS)
        assertEquals("票 #113 的退避上限", 300_000L, SmbSessionLifecycle.MAX_INTERVAL_MS)
    }

    @Test
    fun `空闲时每 30 秒探一次活`() = runTest {
        val at = mutableListOf<Long>()
        val lifecycle = newLifecycle(probe = { at += testScheduler.currentTime; true })

        try {
            establishSession(lifecycle)

            advanceTimeBy(90_000)
            runCurrent()

            assertEquals("空闲 90 秒探 3 次（每 30 秒一拍）", listOf(60_000L, 90_000L, 120_000L), at)
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `间隔内有真实读时跳过该次探活`() = runTest {
        val at = mutableListOf<Long>()
        val lifecycle = newLifecycle(probe = { at += testScheduler.currentTime; true })

        try {
            establishSession(lifecycle)

            lifecycle.noteActivity() // 一次用户读：会话已被证明活着
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("被真实读顶掉的那个 tick 不探", emptyList<Long>(), at)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("下一个 tick 照常探", listOf(90_000L), at)
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `规则6 探针自己走的那次读不算用户活动`() = runTest {
        val at = mutableListOf<Long>()
        lateinit var lifecycle: SmbSessionLifecycle<FakeSmbSessionHandle>
        lifecycle = newLifecycle(
            // 探针在真实链路里就是走一次真读（`stat(共享根)`），链路里会记一次活动——那不算「用户读过」
            probe = {
                at += testScheduler.currentTime
                lifecycle.withSession(SmbReadOp.STAT) { }
                true
            },
        )

        try {
            establishSession(lifecycle)

            advanceTimeBy(90_000)
            runCurrent()

            assertEquals("探针自己的活动不该把节奏拖成 60 秒一次", listOf(60_000L, 90_000L, 120_000L), at)
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `探针失败按 30 到 300 秒退避`() = runTest {
        val at = mutableListOf<Long>()
        val lifecycle = newLifecycle(
            probe = {
                at += testScheduler.currentTime
                throw SmbException(SmbFailureKind.TIMEOUT, "会话不可达")
            },
        )

        try {
            establishSession(lifecycle)

            advanceTimeBy(800_000)
            runCurrent()
            assertEquals(
                "退避序列：60 → 120 → 240 → 480 → 780（间隔 30 / 60 / 120 / 240 / 300）",
                listOf(60_000L, 120_000L, 240_000L, 480_000L, 780_000L),
                at,
            )

            advanceTimeBy(620_000)
            runCurrent()
            assertEquals(
                "封顶后每 300 秒一次，不再翻倍",
                listOf(60_000L, 120_000L, 240_000L, 480_000L, 780_000L, 1_080_000L, 1_380_000L),
                at,
            )
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `一次探活成功后退避回到 30 秒`() = runTest {
        val at = mutableListOf<Long>()
        var attempts = 0
        val lifecycle = newLifecycle(
            probe = {
                at += testScheduler.currentTime
                attempts++
                if (attempts < 3) throw SmbException(SmbFailureKind.TIMEOUT, "会话不可达")
                true
            },
        )

        try {
            establishSession(lifecycle)
            advanceTimeBy(210_000)
            runCurrent()
            assertEquals("前两次失败：间隔 30 / 60，第三次（240 秒）成功", listOf(60_000L, 120_000L, 240_000L), at)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("成功后退避清零：下一次仍在 30 秒后", listOf(60_000L, 120_000L, 240_000L, 270_000L), at)
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `每一拍都上报 真探带结果与耗时 跳过也上报`() = runTest {
        val ticks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        var fakeNanos = 0L
        val lifecycle = newLifecycle(
            // 探针在假时钟上花 12 毫秒：`ms=` 必须是量出来的，不是写死的
            probe = { fakeNanos += 12_000_000; true },
            nanoTime = { fakeNanos },
            onProbe = { probed, ok, ms -> ticks += Triple(probed, ok, ms) },
        )

        try {
            establishSession(lifecycle)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(
                "第一拍是建立那次读顶掉的（probed=false），第二拍才是真探：probed=true + 结果 + 耗时",
                listOf(Triple(false, true, 0L), Triple(true, true, 12L)),
                ticks,
            )

            lifecycle.noteActivity() // 一次真实读：下一个 tick 被顶掉
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(
                "跳过的那一拍也要产一条（否则真机上又看不出心跳跑没跑）",
                listOf(Triple(false, true, 0L), Triple(true, true, 12L), Triple(false, true, 0L)),
                ticks,
            )
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `探针失败上报 ok=false`() = runTest {
        val ticks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val lifecycle = newLifecycle(
            probe = { throw SmbException(SmbFailureKind.TIMEOUT, "会话不可达") },
            onProbe = { probed, ok, ms -> ticks += Triple(probed, ok, ms) },
        )

        try {
            establishSession(lifecycle)

            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(
                "抛异常的那一次是真探过且失败了（头一条是建立那次读顶掉的那拍）",
                listOf(Triple(false, true, 0L), Triple(true, false, 0L)),
                ticks,
            )
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `没有那一次会话的探活报成跳过 不是探活成功`() = runTest {
        val ticks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val lifecycle = newLifecycle(
            // 探针的返回 false = 这一拍什么都没探（还没建过会话 / 已释放 / 正被拆掉重建）
            probe = { false },
            onProbe = { probed, ok, ms -> ticks += Triple(probed, ok, ms) },
        )

        try {
            establishSession(lifecycle)

            advanceTimeBy(30_000)
            runCurrent()

            // 两条都是「什么都没探」：一条是建立那次读顶掉的，一条是探针自己返回 false 的
            assertEquals("空转要报成跳过，不能报成探活成功（否则判读会被带反）", listOf(false, false), ticks.map { it.first })
            assertEquals("两条都不是「探活了」", listOf(true, true), ticks.map { it.second })
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `规则5 会话建立成功才起心跳 close 之后不再探活`() = runTest {
        val at = mutableListOf<Long>()
        val lifecycle = newLifecycle(probe = { at += testScheduler.currentTime; true })

        try {
            // 还没建过会话：不该把来源拉起来联网
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals("未建立会话时零探活", 0, at.size)

            lifecycle.read()
            establishSession(lifecycle)
            val nextProbeAt = testScheduler.currentTime + SmbSessionLifecycle.PROBE_INTERVAL_MS
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("建立之后正常探活", listOf(nextProbeAt), at)

            lifecycle.close()
            lifecycle.close() // 重复 close 是空操作
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals("close 之后不再探活", 1, at.size)
        } finally {
            lifecycle.close()
        }
    }

    @Test
    fun `close 与在飞的建会话交错后不留下空转的循环`() = runTest {
        val ticks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, probe = { true }, onProbe = { p, o, m -> ticks += Triple(p, o, m) })

        // close() 与建会话交错：建会话是慢 I/O，它落地那一刻会起一次心跳，而 close 已经在拆了
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        opener.openEntered = entered
        opener.openGate = gate
        val inFlight = Thread { runCatching { lifecycle.withSession(SmbReadOp.STAT) { } } }
        inFlight.start()
        assertTrue("建会话在飞", entered.await(5, TimeUnit.SECONDS))

        val closer = Thread { lifecycle.close() }
        closer.start()
        gate.countDown()
        inFlight.join(5_000)
        closer.join(5_000)
        advanceTimeBy(600_000)
        runCurrent()

        // 留下循环的话：探针撞上「已释放」直接返回 ⇒ 判成功 ⇒ 每 30 秒空转一次、永不退避（所以一拍都不许有）
        assertEquals("close 之后不得留下空转的探活循环", emptyList<Triple<Boolean, Boolean, Long>>(), ticks)
    }

    @Test
    fun `重复建立不叠加成两条循环`() = runTest {
        val at = mutableListOf<Long>()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, probe = { at += testScheduler.currentTime; true })

        lifecycle.read()
        try {
            // 会话建立每次成功都会起一次心跳（重建也走同一条路），因此它必须是幂等的
            val first = mutableListOf<FakeSmbSessionHandle>()
            lifecycle.withSession(SmbReadOp.STAT) { first += it }
            first[0].alive = false // 连上之后中途断开 → 下一条读重建
            lifecycle.withSession(SmbReadOp.STAT) { }
            assertEquals("确实重建过一条会话", 2, opener.opened.get())

            establishSession(lifecycle)
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals("一条循环只探一次", 1, at.size)
        } finally {
            lifecycle.close()
        }
    }

    // ---------- 用例脚手架 ----------

    /** 生命周期 + 假替身 + 虚拟时间调度器：心跳节奏就是本文件要钉的东西 */
    private fun TestScope.newLifecycle(
        opener: FakeSmbSessionOpener = FakeSmbSessionOpener(),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        probe: () -> Boolean = { false },
        nanoTime: () -> Long = { 0L },
        onProbe: (Boolean, Boolean, Long) -> Unit = { _, _, _ -> },
    ): SmbSessionLifecycle<FakeSmbSessionHandle> = SmbSessionLifecycle(
        opener = opener,
        host = "smb-test",
        probe = probe,
        onProbe = onProbe,
        dispatcher = dispatcher,
        nanoTime = nanoTime,
    )

    /**
     * 建立会话 + 把「刚读过」的那一拍喂掉：之后心跳按 30 秒节奏跑。
     * 那一拍本身是生产行为（建立会话就是一次真读，读一开始就记「用过」），由 `间隔内有真实读时跳过该次探活` 钉住。
     */
    private fun TestScope.establishSession(lifecycle: SmbSessionLifecycle<FakeSmbSessionHandle>) {
        lifecycle.withSession(SmbReadOp.STAT) { }
        advanceTimeBy(SmbSessionLifecycle.PROBE_INTERVAL_MS)
        runCurrent()
    }

    /** 跑一次读（拿句柄什么都不做）：`lifecycle.read()` 就是「一次真实读」 */
    private fun SmbSessionLifecycle<FakeSmbSessionHandle>.read() {
        withSession(SmbReadOp.STAT) { }
    }
}