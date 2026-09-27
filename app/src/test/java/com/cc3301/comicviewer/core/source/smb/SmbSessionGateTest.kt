package com.cc3301.comicviewer.core.source.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话就绪闸门 + 分批放行（票 #113 修法第 2 条）。
 *
 * 为什么测这里而不是 `SmbjTransport`：smbj 的 `SMBClient`/`Connection`/`Session` 在单测里不可注入
 * （`SmbjTransport` 直接 new），真实建连跑不出来。按仓库既有先例（`SmbSessionReporter`、
 * `remote/RemoteRetry.retryOnce`）把「重建期间等一个会话就绪信号、就绪后分批放行」摘进纯内存类
 * [SmbSessionGate]，在这里锁死三条语义：
 *
 * ① **重建期间不放行**：有人正在重建会话时进入的读**等在闸上**（等的是内存信号，不是各自的 socket）；
 * ② **就绪后分批放行**：一次只放 [SmbSessionGate.MAX_CONCURRENT_SESSION_READS] 个，其余按归还顺序补位；
 * ③ **名额按会话代次计**：会话失效前的那些读归还名额时**不再计数**——否则每重建一次名额就多一批，
 *    闸门名存实亡。
 *
 * 判别的两条：①改了前会失败（没有闸门时，重建期间进入的读会直接进到那条死会话上）；
 * ③用单一计数器实现时会失败（老代的归还把名额放大成 2，第三条读会被误放行）。
 *
 * 用真线程 + 闩锁而不是 `runTest`：闸门接口本身是阻塞的（`SmbTransport` 是阻塞接口，
 * 调用方在 IO 线程上），等的是 `Condition`；与 `CoverByteGateTest` 的协程口径不同，别照抄那边。
 */
class SmbSessionGateTest {

    @Test
    fun `默认闸位是 4`() {
        // 默认值也是口径（与 #145 的取字节上界「2~4」取同一个 4 对齐）：只注入小闸位的用例钉不住常量
        assertEquals("票 #113 的默认分批放行闸位", 4, SmbSessionGate.MAX_CONCURRENT_SESSION_READS)
    }

    @Test
    fun `就绪后同时最多 N 个在飞`() {
        val gate = SmbSessionGate(maxConcurrent = 3)
        val running = AtomicInteger()
        val maxSeen = AtomicInteger()
        val finished = CountDownLatch(12)

        val workers = List(12) {
            Thread {
                gate.withPermit {
                    maxSeen.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                    Thread.sleep(20) // 模拟一次取字节的往返
                    running.decrementAndGet()
                }
                finished.countDown()
            }
        }
        workers.forEach { it.start() }

        assertTrue("12 条读都要跑完", finished.await(10, TimeUnit.SECONDS))
        assertEquals("同时最多 3 条在飞（越界的排队，不是一起冲会话）", 3, maxSeen.get())
        assertEquals("跑完全部归还干净", 0, running.get())
        workers.forEach { it.join(5_000) }
    }

    @Test
    fun `重建期间不放行 就绪后放行`() {
        val gate = SmbSessionGate(maxConcurrent = 4)
        val firstEpoch = AtomicLong(-1)
        val holding = CountDownLatch(1)
        val canLeave = CountDownLatch(1)

        // 一条已经在飞的读：占着名额，并在里面走「读失败 → 声明会话失效（重建开始）」
        val inFlight = Thread {
            gate.withPermit { usedEpoch ->
                firstEpoch.set(usedEpoch)
                assertTrue("先失败的那条读负责拆会话", gate.invalidate(usedEpoch))
                holding.countDown()
                canLeave.await()
            }
        }
        inFlight.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        // 重建在飞时进入的读：必须等在闸上（而不是各自卡到那条死会话的 socket 上）
        val reusedEpoch = AtomicLong(-2)
        val entered = CountDownLatch(1)
        val waiting = Thread {
            gate.withPermit { usedEpoch ->
                reusedEpoch.set(usedEpoch)
                entered.countDown()
            }
        }
        waiting.start()
        assertFalse("重建期间不放行", entered.await(200, TimeUnit.MILLISECONDS))

        // 会话就绪：放行
        gate.settled()
        assertTrue("就绪后要放行", entered.await(5, TimeUnit.SECONDS))
        assertEquals("放行时拿到的必须是新代次", firstEpoch.get() + 1, reusedEpoch.get())

        canLeave.countDown()
        inFlight.join(5_000)
        waiting.join(5_000)
        assertFalse("两条读都要结束（线程不该卡在闸上）", inFlight.isAlive || waiting.isAlive)
    }

    @Test
    fun `同一代里只有第一个失败者拆会话`() {
        val gate = SmbSessionGate(maxConcurrent = 4)

        gate.withPermit { usedEpoch ->
            assertTrue("第一个失败者拆会话（并负责回来后 settled）", gate.invalidate(usedEpoch))
            // 另一个 worker 手里拿的还是同一代的号：代次已经变了 ⇒ 它不该再拆一次
            // （真机日志里 30+ 个 worker 各拆一次，就是把别人刚建好的会话又推倒）
            assertFalse("代次变了就不再拆会话", gate.invalidate(usedEpoch))
        }
        gate.settled()
    }

    @Test
    fun `老一代的读归还时不再计数`() {
        val gate = SmbSessionGate(maxConcurrent = 1)
        val firstEpoch = AtomicLong(-1)
        val firstIn = CountDownLatch(1)
        val firstOut = CountDownLatch(1)
        val first = Thread {
            gate.withPermit { usedEpoch ->
                firstEpoch.set(usedEpoch)
                firstIn.countDown()
                firstOut.await()
            }
        }
        first.start()
        assertTrue(firstIn.await(5, TimeUnit.SECONDS))

        // 会话失效 → 重建 → 新的一代
        assertTrue(gate.invalidate(firstEpoch.get()))
        gate.settled()

        // 新代的第一条读拿到唯一名额并占住
        val secondIn = CountDownLatch(1)
        val secondOut = CountDownLatch(1)
        val second = Thread {
            gate.withPermit {
                secondIn.countDown()
                secondOut.await()
            }
        }
        second.start()
        assertTrue(secondIn.await(5, TimeUnit.SECONDS))

        val thirdIn = CountDownLatch(1)
        val third = Thread { gate.withPermit { thirdIn.countDown() } }
        third.start()

        // 老一代那条读现在才归还：按代次计数 ⇒ 它不该凭空多出一个名额
        firstOut.countDown()
        first.join(5_000)
        assertFalse("老一代的归还不该放行新代的读", thirdIn.await(200, TimeUnit.MILLISECONDS))

        secondOut.countDown()
        assertTrue("新代归还后才放行", thirdIn.await(5, TimeUnit.SECONDS))
        second.join(5_000)
        third.join(5_000)
    }

    @Test
    fun `就绪状态下的重复放行不补名额`() {
        val gate = SmbSessionGate(maxConcurrent = 1)
        val holding = CountDownLatch(1)
        val canLeave = CountDownLatch(1)
        val holder = Thread {
            gate.withPermit {
                holding.countDown()
                canLeave.await()
            }
        }
        holder.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        // 一次重建可能从好几处喊放行：已经就绪时再喊，不该凭空多出名额（否则「同时最多 N 条」就没约束了）
        gate.settled()
        gate.settled()

        val entered = CountDownLatch(1)
        val next = Thread { gate.withPermit { entered.countDown() } }
        next.start()
        assertFalse("就绪状态下的重复放行不该补名额", entered.await(200, TimeUnit.MILLISECONDS))

        canLeave.countDown()
        assertTrue("真正归还名额后才放行", entered.await(5, TimeUnit.SECONDS))
        holder.join(5_000)
        next.join(5_000)
    }

    @Test
    fun `读抛异常也归还名额`() {
        val gate = SmbSessionGate(maxConcurrent = 1)

        assertThrows(IllegalStateException::class.java) {
            gate.withPermit { throw IllegalStateException("会话断了") }
        }

        // 漏掉一个名额 = 闸位永久少一个：下一次读必须还能进
        val entered = CountDownLatch(1)
        val next = Thread { gate.withPermit { entered.countDown() } }
        next.start()
        assertTrue("上一次读抛异常也把名额还了", entered.await(5, TimeUnit.SECONDS))
        next.join(5_000)
    }
}
