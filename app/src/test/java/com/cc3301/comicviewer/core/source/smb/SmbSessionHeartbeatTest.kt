package com.cc3301.comicviewer.core.source.smb

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SMB 会话探活心跳。
 *
 * 为什么测这里而不是 `SmbjTransport`：smbj 的 `SMBClient`/`Connection`/`Session` 在单测里不可注入
 * （`SmbjTransport` 直接 new），真实建连与「空闲断开」在 JVM 上跑不出来。按仓库既有先例
 * （`SmbSessionGate`、`SmbSessionReporter`）把「多久探一次、失败怎么退避、什么算空闲」摘进
 * [SmbSessionHeartbeat]，在这里用**虚拟时间**锁死：
 *
 * ① 空闲时**每 30 秒**探一次（会话可能已被服务端回收，要在用户之前撞上并重建）；
 * ② 间隔内有真实读就**跳过这次**探活（会话刚被用过＝活着，不必再压一条读上去）；
 * ③ 探针失败按 **30 → 60 → 120 → 240 → 封顶 300** 退避（服务器不可达时不会退成每 30 秒一次失败重连），
 *    一次成功即回到 30 秒；
 * ④ `start()` 幂等（未 stop 时反复 start 只起一条循环）、`stop()` 之后不再探，且 **stop 是单向的**：
 *    `stop()` 之后再 `start()` 不得复活循环（`SmbjTransport.close()` 置 `released` 与在飞的那次建会话之间是竞态，
 *    释义见 `SmbSessionHeartbeat.start` 的 KDoc）。
 *
 * 探针自己走的是同一条 `withSession` 链，因此链路里也会记一次「活动」——②的判定要能把探针自己的
 * 活动排除掉（用例 `探针自己走的那次读不算用户活动` 钉的就是这条，改错会让心跳退化成 60 秒一次）。
 *
 * ⑤ 每一拍都要上报 `(probed, ok, ms)`：真探过的才有 `ok`/`ms`，
 * **没探过的那一拍不许报成「探活了」**（2026-09-29 那段里，探针成功不产行就是判读空白的根）。
 * 探针返回 `false` = 这一拍什么都没探（还没会话 / 已释放），见 `SmbjTransport.probeShareRoot`。
 *
 * **每个用例都用 `try/finally` 停掉心跳**：循环是常驻的，若某条断言先失败、又没停循环，
 * `runTest` 收尾时会一直推进虚拟时间等它结束——表现为 `OutOfMemoryError`（探针每次记一条时间），
 * 把真正的失败盖掉。
 */
class SmbSessionHeartbeatTest {

    @Test
    fun `默认间隔 30 秒、退避上限 300 秒`() {
        // 这两个数就是 2026-09-28 定的口径：只注入小间隔的用例钉不住它们
        assertEquals("票 #113 的探活间隔", 30_000L, SmbSessionHeartbeat.PROBE_INTERVAL_MS)
        assertEquals("票 #113 的退避上限", 300_000L, SmbSessionHeartbeat.MAX_INTERVAL_MS)
    }

    @Test
    fun `空闲时每 30 秒探一次活`() = runTest {
        val at = mutableListOf<Long>()
        val heartbeat = SmbSessionHeartbeat(
            probe = { at += testScheduler.currentTime; true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(90_000)
            runCurrent()

            assertEquals("空闲 90 秒探 3 次", listOf(30_000L, 60_000L, 90_000L), at)
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `间隔内有真实读时跳过该次探活`() = runTest {
        val at = mutableListOf<Long>()
        val heartbeat = SmbSessionHeartbeat(
            probe = { at += testScheduler.currentTime; true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        heartbeat.start()
        try {
            runCurrent()
            heartbeat.noteActivity() // 一次用户读：会话已被证明活着
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("被真实读顶掉的那个 tick 不探", emptyList<Long>(), at)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("下一个 tick 照常探", listOf(60_000L), at)
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `探针自己走的那次读不算用户活动`() = runTest {
        val at = mutableListOf<Long>()
        lateinit var heartbeat: SmbSessionHeartbeat
        heartbeat = SmbSessionHeartbeat(
            // 探针在真实链路里走 withSession，链路里会记一次活动——那不算「用户读过」
            probe = {
                at += testScheduler.currentTime
                heartbeat.noteActivity()
                true
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(90_000)
            runCurrent()

            assertEquals("探针自己的活动不该把节奏拖成 60 秒一次", listOf(30_000L, 60_000L, 90_000L), at)
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `探针失败按 30 到 300 秒退避`() = runTest {
        val at = mutableListOf<Long>()
        val heartbeat = SmbSessionHeartbeat(
            probe = {
                at += testScheduler.currentTime
                throw SmbException(SmbFailureKind.TIMEOUT, "会话不可达")
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        heartbeat.start()
        try {
            runCurrent()

            advanceTimeBy(800_000)
            runCurrent()
            assertEquals(
                "退避序列：30 → 90 → 210 → 450（间隔 30 / 60 / 120 / 240）→ 750（间隔 300）",
                listOf(30_000L, 90_000L, 210_000L, 450_000L, 750_000L),
                at,
            )

            advanceTimeBy(620_000)
            runCurrent()
            assertEquals(
                "封顶后每 300 秒一次，不再翻倍",
                listOf(30_000L, 90_000L, 210_000L, 450_000L, 750_000L, 1_050_000L, 1_350_000L),
                at,
            )
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `一次探活成功后退避回到 30 秒`() = runTest {
        val at = mutableListOf<Long>()
        var attempts = 0
        val heartbeat = SmbSessionHeartbeat(
            probe = {
                at += testScheduler.currentTime
                attempts++
                if (attempts < 3) throw SmbException(SmbFailureKind.TIMEOUT, "会话不可达")
                true
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(210_000)
            runCurrent()
            assertEquals("前两次失败：间隔 30 / 60，第三次（210 秒）成功", listOf(30_000L, 90_000L, 210_000L), at)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("成功后退避清零：下一次仍在 30 秒后", listOf(30_000L, 90_000L, 210_000L, 240_000L), at)
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `每一拍都上报 真探带结果与耗时 跳过也上报`() = runTest {
        val ticks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        var fakeNanos = 0L
        val heartbeat = SmbSessionHeartbeat(
            // 探针在假时钟上花 12 毫秒：`ms=` 必须是量出来的，不是写死的
            probe = { fakeNanos += 12_000_000; true },
            dispatcher = StandardTestDispatcher(testScheduler),
            nanoTime = { fakeNanos },
            report = { probed, ok, ms -> ticks += Triple(probed, ok, ms) },
        )

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("真探的那一拍：probed=true + 结果 + 耗时", listOf(Triple(true, true, 12L)), ticks)

            heartbeat.noteActivity() // 一次真实读：下一个 tick 被顶掉
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(
                "跳过的那一拍也要产一条（否则真机上又看不出心跳跑没跑）",
                listOf(Triple(true, true, 12L), Triple(false, true, 0L)),
                ticks,
            )
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `探针失败上报 ok=false`() = runTest {
        val ticks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val heartbeat = SmbSessionHeartbeat(
            probe = { throw SmbException(SmbFailureKind.TIMEOUT, "会话不可达") },
            dispatcher = StandardTestDispatcher(testScheduler),
            nanoTime = { 0L },
            report = { probed, ok, ms -> ticks += Triple(probed, ok, ms) },
        )

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals("抛异常的那一次是真探过且失败了", listOf(Triple(true, false, 0L)), ticks)
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `没有会话的那一拍报成跳过 不是探活成功`() = runTest {
        val ticks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        val heartbeat = SmbSessionHeartbeat(
            // 探针的返回 false = 这一拍什么都没探（还没建过会话 / 已释放 / 在退避窗口里）
            probe = { false },
            dispatcher = StandardTestDispatcher(testScheduler),
            nanoTime = { 0L },
            report = { probed, ok, ms -> ticks += Triple(probed, ok, ms) },
        )

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals("空转要报成跳过，不能报成探活成功（否则判读会被带反）", listOf(false, false), ticks.map { it.first })
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `未 start 不探活、stop 之后不再探活`() = runTest {
        val at = mutableListOf<Long>()
        val heartbeat = SmbSessionHeartbeat(
            probe = { at += testScheduler.currentTime; true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        // 没 start：还没建过会话的来源不该被心跳拉起来联网
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals("未 start 时零探活", 0, at.size)

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("start 之后正常探活", 1, at.size)

            heartbeat.stop()
            heartbeat.stop() // 重复 stop 是空操作
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals("stop 之后不再探活", 1, at.size)
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `stop 之后再 start 不复活循环`() = runTest {
        val at = mutableListOf<Long>()
        val heartbeat = SmbSessionHeartbeat(
            probe = { at += testScheduler.currentTime; true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        heartbeat.start()
        try {
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("start 之后正常探活", listOf(30_000L), at)

            // close() 先置 released 再 stop，而建会话是慢 I/O：在飞的那次建会话可以在这里之后才喊 start
            heartbeat.stop()
            heartbeat.start()
            advanceTimeBy(600_000)
            runCurrent()

            assertEquals("stop 之后的 start 是空操作，循环不得复活", listOf(30_000L), at)
        } finally {
            heartbeat.stop()
        }
    }

    @Test
    fun `重复 start 不叠加成两条循环`() = runTest {
        val at = mutableListOf<Long>()
        val heartbeat = SmbSessionHeartbeat(
            probe = { at += testScheduler.currentTime; true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        // 会话建立每次成功都会喊 start（重连也喊），因此它必须是幂等的
        heartbeat.start()
        try {
            heartbeat.start()
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals("一条循环只探一次", listOf(30_000L), at)
        } finally {
            heartbeat.stop()
        }
    }
}
