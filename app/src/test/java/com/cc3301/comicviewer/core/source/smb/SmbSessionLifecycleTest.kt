package com.cc3301.comicviewer.core.source.smb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SMB 会话生命周期（票 #151）：**闸门 + 代次 + 退避 + 打点判定**，以及八条跨件规则。
 * 心跳那一支（调度 + 退避 + 报告）在 `SmbSessionLifecycleHeartbeatTest`。
 *
 * 为什么测这里而不是 `SmbjTransport`：smbj 的 `SMBClient`/`Connection`/`Session` 在单测里不可注入
 * （`SmbjTransport` 直接 new），真实建连跑不出来。票 #151 把「连服务器」开成一个可替换口
 * （[SmbSessionOpener]，生产 = smbj，测试 = [FakeSmbSessionOpener]），于是原来分在
 * `SmbSessionGate` / `SmbRebuildBackoff` / `SmbSessionReporter` 三个纯类里的语义**都落到同一个模块**上，
 * 也第一次能把「跨件才成立的规则」直接跑出来（第二节）。
 *
 * 本文件四段：
 *
 * ① **闸门与代次**（搬自 `SmbSessionGateTest`，断言一字未放宽）：重建期间不放行、就绪后分批放行、
 *    名额按代次计、关闭 = 永久关闸、已就绪时重复放行不补名额；
 * ② **重建退避**（搬自 `SmbRebuildBackoffTest`，改为由「建连失败」这条真路径驱动）：
 *    1s → 2s → 4s 封顶 8s、窗口内一条都不尝试、成功即清零、尝试号按连续失败算；
 * ③ **打点判定**（搬自 `SmbSessionReporterTest`）：`rebuilt` 的真相是「此前成功建立过会话」，
 *    且只在建立成功之后打点；
 * ④ **八条跨件规则**（票 #151 新增）：规则 1~7 各一条用例；规则 8（换代那一瞬最坏 2×上限）
 *    只能测到**机制**（老一代归还不计数 ⇒ 8 条同时在飞），真机上那一刻是否真发生不与本用例等价
 *    —— 那是 #113 的复现验收。
 *
 * 用真线程 + 闩锁而不是 `runTest`：读接口本身是阻塞的（`SmbTransport` 是阻塞接口，调用方在 IO 线程上），
 * 等的是 `Condition`；与 `CoverByteGateTest` 的协程口径不同，两边的写法不能互抄。
 */
class SmbSessionLifecycleTest {

    // ---------- ① 闸门与代次（搬自 SmbSessionGateTest） ----------

    @Test
    fun `默认闸位是 4`() {
        // 默认值也是口径（与取字节上界「2~4」取同一个 4 对齐）：只注入小闸位的用例钉不住常量
        assertEquals("票 #113 的默认分批放行闸位", 4, SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS)
    }

    @Test
    fun `就绪后同时最多 N 个在飞`() {
        val lifecycle = newLifecycle(maxConcurrent = 3)
        val running = AtomicInteger()
        val maxSeen = AtomicInteger()
        val finished = CountDownLatch(12)

        val workers = List(12) {
            Thread {
                lifecycle.withPermit {
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
        val lifecycle = newLifecycle(maxConcurrent = 4)
        val firstEpoch = AtomicLong(-1)
        val holding = CountDownLatch(1)
        val canLeave = CountDownLatch(1)

        // 一条已经在飞的读：占着名额，并在里面走「读失败 → 声明会话失效（重建开始）」
        val inFlight = Thread {
            lifecycle.withPermit { usedEpoch ->
                firstEpoch.set(usedEpoch)
                assertTrue("先失败的那条读负责拆会话", lifecycle.invalidate(usedEpoch))
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
            lifecycle.withPermit { usedEpoch ->
                reusedEpoch.set(usedEpoch)
                entered.countDown()
            }
        }
        waiting.start()
        assertFalse("重建期间不放行", entered.await(200, TimeUnit.MILLISECONDS))

        // 会话就绪：放行
        lifecycle.settled()
        assertTrue("就绪后要放行", entered.await(5, TimeUnit.SECONDS))
        assertEquals("放行时拿到的必须是新代次", firstEpoch.get() + 1, reusedEpoch.get())

        canLeave.countDown()
        inFlight.join(5_000)
        waiting.join(5_000)
        assertFalse("两条读都要结束（线程不该卡在闸上）", inFlight.isAlive || waiting.isAlive)
    }

    @Test
    fun `同一代里只有第一个失败者拆会话`() {
        val lifecycle = newLifecycle(maxConcurrent = 4)

        lifecycle.withPermit { usedEpoch ->
            assertTrue("第一个失败者拆会话（并负责回来后 settled）", lifecycle.invalidate(usedEpoch))
            // 另一个 worker 手里拿的还是同一代的号：代次已经变了 ⇒ 它不该再拆一次
            // （设备日志里 30+ 个 worker 各拆一次，就是把别人刚建好的会话又推倒）
            assertFalse("代次变了就不再拆会话", lifecycle.invalidate(usedEpoch))
        }
        lifecycle.settled()
    }

    @Test
    fun `老一代的读归还时不再计数`() {
        val lifecycle = newLifecycle(maxConcurrent = 1)
        val firstEpoch = AtomicLong(-1)
        val firstIn = CountDownLatch(1)
        val firstOut = CountDownLatch(1)
        val first = Thread {
            lifecycle.withPermit { usedEpoch ->
                firstEpoch.set(usedEpoch)
                firstIn.countDown()
                firstOut.await()
            }
        }
        first.start()
        assertTrue(firstIn.await(5, TimeUnit.SECONDS))

        // 会话失效 → 重建 → 新的一代
        assertTrue(lifecycle.invalidate(firstEpoch.get()))
        lifecycle.settled()

        // 新代的第一条读拿到唯一名额并占住
        val secondIn = CountDownLatch(1)
        val secondOut = CountDownLatch(1)
        val second = Thread {
            lifecycle.withPermit {
                secondIn.countDown()
                secondOut.await()
            }
        }
        second.start()
        assertTrue(secondIn.await(5, TimeUnit.SECONDS))

        val thirdIn = CountDownLatch(1)
        val third = Thread { lifecycle.withPermit { thirdIn.countDown() } }
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
        val lifecycle = newLifecycle(maxConcurrent = 1)
        val holding = CountDownLatch(1)
        val canLeave = CountDownLatch(1)
        val holder = Thread {
            lifecycle.withPermit {
                holding.countDown()
                canLeave.await()
            }
        }
        holder.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        // 一次重建可能从好几处喊放行：已经就绪时再喊，不该凭空多出名额（否则「同时最多 N 条」就没约束了）
        lifecycle.settled()
        lifecycle.settled()

        val entered = CountDownLatch(1)
        val next = Thread { lifecycle.withPermit { entered.countDown() } }
        next.start()
        assertFalse("就绪状态下的重复放行不该补名额", entered.await(200, TimeUnit.MILLISECONDS))

        canLeave.countDown()
        assertTrue("真正归还名额后才放行", entered.await(5, TimeUnit.SECONDS))
        holder.join(5_000)
        next.join(5_000)
    }

    @Test
    fun `传输关闭后等在闸上的读不放行而是失败退出`() {
        val lifecycle = newLifecycle(maxConcurrent = 1)
        val holding = CountDownLatch(1)
        val canLeave = CountDownLatch(1)
        val inFlight = Thread {
            lifecycle.withPermit { usedEpoch ->
                // 会话失效：后面的读全部等在闸上
                lifecycle.invalidate(usedEpoch)
                holding.countDown()
                canLeave.await()
            }
        }
        inFlight.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        val entered = CountDownLatch(1)
        val failed = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val waiting = Thread {
            try {
                lifecycle.withPermit { entered.countDown() }
            } catch (t: Throwable) {
                failed.set(t)
            } finally {
                done.countDown()
            }
        }
        waiting.start()
        assertFalse("重建期间不放行", entered.await(200, TimeUnit.MILLISECONDS))

        // 来源实例被释放：排在闸上的读不再放行（放行它们 = 各自去建一条新会话），改为就地失败
        lifecycle.close()
        assertTrue("关闭后要唤醒排队读（不然它永远挂在闸上）", done.await(5, TimeUnit.SECONDS))
        assertEquals("关闭后一个都不放行", 1L, entered.count)
        assertTrue("以既有失败形态退出（SmbException）", failed.get() is SmbException)

        // 关闭之后新进来的读同样直接失败：不夺名额、不碰会话
        assertThrows(SmbException::class.java) { lifecycle.withPermit { entered.countDown() } }
        assertEquals("关闭后没有任何读被放行", 1L, entered.count)

        canLeave.countDown()
        inFlight.join(5_000)
        waiting.join(5_000)
        assertFalse("不该有线程卡在闸上", inFlight.isAlive || waiting.isAlive)
    }

    @Test
    fun `读抛异常也归还名额`() {
        val lifecycle = newLifecycle(maxConcurrent = 1)

        assertThrows(IllegalStateException::class.java) {
            lifecycle.withPermit { throw IllegalStateException("会话断了") }
        }

        // 漏掉一个名额 = 闸位永久少一个：下一次读必须还能进
        val entered = CountDownLatch(1)
        val next = Thread { lifecycle.withPermit { entered.countDown() } }
        next.start()
        assertTrue("上一次读抛异常也把名额还了", entered.await(5, TimeUnit.SECONDS))
        next.join(5_000)
    }

    // ---------- ② 重建退避（搬自 SmbRebuildBackoffTest，由真路径驱动） ----------

    @Test
    fun `第一次失败退避 1 秒 窗口一过就放行下一次尝试`() {
        val clock = FakeClock()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, clock = clock::read)
        lifecycle.failEstablish(opener)

        assertTrue("刚失败就在窗口里", lifecycle.isBackingOff())
        clock.advance(999)
        assertTrue("还差 1 毫秒也算窗口内", lifecycle.isBackingOff())
        clock.advance(1)
        assertFalse("窗口一过就要放行（不然会话永远建不回来）", lifecycle.isBackingOff())
    }

    @Test
    fun `窗口长度按 1 到 2 到 4 翻倍 封顶 8 秒`() {
        // 用 1 毫秒步进探出真实窗口长度，而不是在用例里复述一遍公式（复述公式等于没测）
        val clock = FakeClock()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, clock = clock::read)

        assertEquals("第 1 次失败", 1_000L, lifecycle.windowMsAfterFailure(opener, clock))
        assertEquals("第 2 次失败", 2_000L, lifecycle.windowMsAfterFailure(opener, clock))
        assertEquals("第 3 次失败", 4_000L, lifecycle.windowMsAfterFailure(opener, clock))
        assertEquals("第 4 次失败已经封顶", 8_000L, lifecycle.windowMsAfterFailure(opener, clock))
        assertEquals("再失败也还是封顶（不会无限翻倍）", 8_000L, lifecycle.windowMsAfterFailure(opener, clock))
    }

    @Test
    fun `重建成功即清零退避`() {
        val clock = FakeClock()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, clock = clock::read)

        lifecycle.failEstablish(opener)
        clock.advance(1_000)
        lifecycle.failEstablish(opener)
        assertTrue("两次失败之后仍在窗口里", lifecycle.isBackingOff())

        // 会话建起来了（下一次读不再要失败）
        clock.advance(2_000)
        lifecycle.read()

        assertFalse("成功即清零：窗口还剩一半也要立刻放行", lifecycle.isBackingOff())
        assertEquals("下一轮重建从第 1 次尝试重新数", 1, lifecycle.nextAttempt())
    }

    @Test
    fun `窗口从这一次失败的时刻起算 而不是沿用上一次的到期时刻`() {
        val clock = FakeClock()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, clock = clock::read)

        lifecycle.failEstablish(opener) // t=0：窗口到 1000ms
        clock.advance(1_000) // 第一个窗口过去
        lifecycle.failEstablish(opener) // t=1000：窗口到 1000+2000=3000ms

        clock.advance(1_500) // t=2500：第一次的窗口早就过了
        assertTrue("第二次失败把到期时刻推后了", lifecycle.isBackingOff())
        clock.advance(499) // t=2999
        assertTrue("还差 1 毫秒", lifecycle.isBackingOff())
        clock.advance(1) // t=3000
        assertFalse("3000ms 到点", lifecycle.isBackingOff())
    }

    @Test
    fun `尝试号按连续失败计数`() {
        val clock = FakeClock()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, clock = clock::read)

        assertEquals("第一次建立（新建或重建）都是第 1 次尝试", 1, lifecycle.nextAttempt())
        lifecycle.failEstablish(opener)
        assertEquals(2, lifecycle.nextAttempt())
        clock.advance(2_000)
        lifecycle.failEstablish(opener)
        assertEquals("`smbRebuild attempt=` 报的就是它（真机上「一共试了几次」）", 3, lifecycle.nextAttempt())
        assertEquals("假替身拿到的尝试号就是 1、2（下一次会是 3）", listOf(1, 2), opener.attempts)
    }

    @Test
    fun `退避窗口内并发进来的读一条都不开始尝试`() {
        // 时钟停在 0：窗口不会自己过去，模拟「一批读同时撞上来」
        val clock = FakeClock()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, clock = clock::read)
        lifecycle.failEstablish(opener)
        assertEquals("只有那一条失败在建会话", 1, opener.openCalls.get())

        val thrown = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val done = CountDownLatch(8)
        repeat(8) {
            Thread {
                try {
                    lifecycle.read()
                } catch (t: Throwable) {
                    thrown += t
                } finally {
                    done.countDown()
                }
            }.start()
        }

        assertTrue("8 条读都要跑完", done.await(5, TimeUnit.SECONDS))
        assertEquals("窗口内 0 次尝试（这就是「不排队、不再建」）", 1, opener.openCalls.get())
        assertEquals("8 条都就地失败", 8, thrown.size)
        assertTrue(
            "失败形态是「退避期内被拒」这一类（设备上判退避生效没生效就靠它）",
            thrown.all { it is SmbRebuildBackedOffException },
        )
    }

    // ---------- ③ 打点判定（搬自 SmbSessionReporterTest） ----------

    @Test
    fun `首次建立报重建为假`() {
        val reported = mutableListOf<Boolean>()
        val lifecycle = newLifecycle(onEstablished = { reported += it })

        val seen = AtomicReference<FakeSmbSessionHandle>()
        lifecycle.withSession(SmbReadOp.STAT) { seen.set(it) }

        assertNotNull("一次读要拿到句柄", seen.get())
        assertEquals(1, seen.get().generation)
        assertEquals("首次建立不是重建", listOf(false), reported)
    }

    @Test
    fun `断链重连报重建为真`() {
        val reported = mutableListOf<Boolean>()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, onEstablished = { reported += it })

        val first = AtomicReference<FakeSmbSessionHandle>()
        lifecycle.withSession(SmbReadOp.STAT) { first.set(it) }

        // 连上之后中途断开（服务端把会话作废）：下一条读必须建新的一条
        first.get().alive = false
        val second = AtomicReference<FakeSmbSessionHandle>()
        lifecycle.withSession(SmbReadOp.STAT) { second.set(it) }

        assertEquals("第二条读落到新会话上", 2, second.get().generation)
        assertEquals("重建路径同样是「先丢旧句柄再建」：判定必须跨这次丢弃存活", listOf(false, true), reported)
    }

    @Test
    fun `建立失败不打点且异常照常上抛`() {
        val reported = mutableListOf<Boolean>()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, onEstablished = { reported += it })

        // 失败的这一次：既不上报、也不把状态推成「建立过」（failEstablish 内部断言它抛）
        lifecycle.failEstablish(opener)
        assertEquals("失败的建立不该留下「会话已建立」这一行", emptyList<Boolean>(), reported)

        // 之后再成功一次：仍算首次（上一次压根没建立起来）
        lifecycle.read()
        assertEquals(listOf(false), reported)
    }

    // ---------- ④ 八条跨件规则（票 #151 新增） ----------

    @Test
    fun `规则1 拆会话那条读必须有一个出口喊放行`() {
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, maxConcurrent = 4)
        lifecycle.read() // 先有一条会话

        val rebuildIn = CountDownLatch(1)
        val rebuildGate = CountDownLatch(1)
        opener.openEntered = rebuildIn
        opener.openGate = rebuildGate

        // 拆会话的那条读：第一次读失败（连接层故障）→ 它负责拆 → 重建（这里被闩住）
        val attempts = AtomicInteger()
        val tearDown = Thread {
            lifecycle.withSession(SmbReadOp.STAT) { _ ->
                if (attempts.incrementAndGet() == 1) throw SocketException("对端断开")
            }
        }
        tearDown.start()
        assertTrue("重建在飞", rebuildIn.await(5, TimeUnit.SECONDS))

        // 重建期间进来的读：等在闸上
        val entered = CountDownLatch(1)
        val handle = AtomicReference<FakeSmbSessionHandle>()
        val waits = Thread {
            lifecycle.withSession(SmbReadOp.STAT) { handle.set(it); entered.countDown() }
        }
        waits.start()
        assertFalse("重建期间不放行", entered.await(200, TimeUnit.MILLISECONDS))

        // 重建完成 → 那条读的出口喊放行 → 等着的读落到新会话上
        rebuildGate.countDown()
        assertTrue("放行要在建连成功那一刻发生", entered.await(5, TimeUnit.SECONDS))
        assertEquals("等着的读必须落到新那条会话上", 2, handle.get().generation)

        tearDown.join(5_000)
        waits.join(5_000)
        assertFalse("不该有线程卡住", tearDown.isAlive || waits.isAlive)
    }

    @Test
    fun `规则2 重建失败也要放行`() {
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, maxConcurrent = 4)
        lifecycle.read() // 先有一条会话

        // 重建那一次：建不起来（先用闩把它捏成「重建在飞」）
        opener.failures += authFailure()
        val rebuildIn = CountDownLatch(1)
        val rebuildGate = CountDownLatch(1)
        opener.openEntered = rebuildIn
        opener.openGate = rebuildGate

        val attempts = AtomicInteger()
        val tearDown = Thread {
            runCatching {
                lifecycle.withSession(SmbReadOp.STAT) { _ ->
                    if (attempts.incrementAndGet() == 1) throw SocketException("对端断开")
                }
            }
        }
        tearDown.start()
        assertTrue("重建在飞", rebuildIn.await(5, TimeUnit.SECONDS))

        // 等着的读：建不起来的会话不该把它永远挂住（否则这批读全醒不来）
        val failed = AtomicReference<Throwable>()
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val waits = Thread {
            try {
                lifecycle.withSession(SmbReadOp.STAT) { entered.countDown() }
            } catch (t: Throwable) {
                failed.set(t)
            } finally {
                done.countDown()
            }
        }
        waits.start()
        assertFalse("重建期间它确实在闸上等", entered.await(200, TimeUnit.MILLISECONDS))

        // 重建失败：失败也要放行
        rebuildGate.countDown()
        assertTrue("重建失败也要放行（不然闸上等着的读永远醒不来）", done.await(5, TimeUnit.SECONDS))
        assertTrue("放行后撞上的是那条失败读留下的退避窗口", failed.get() is SmbRebuildBackedOffException)

        tearDown.join(5_000)
        waits.join(5_000)
    }

    @Test
    fun `规则3 已释放就不拆 不再拉起新会话`() {
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener)
        lifecycle.read()
        assertEquals("先有一条会话", 1, opener.opened.get())

        lifecycle.close()

        // 迟到的读：就地失败，且**不再建**一条新会话
        assertThrows(SmbException::class.java) { lifecycle.read() }
        assertEquals("释放后不再建会话", 1, opener.openCalls.get())

        // 「已在飞那条读的重试」同样不许把会话建回来：连接层故障 → 拆 → 重试也走同一条失败出口
        assertThrows(SmbException::class.java) {
            lifecycle.withSession(SmbReadOp.STAT) { throw SocketException("对端断开") }
        }
        assertEquals("重试也不该再建会话", 1, opener.openCalls.get())
    }

    @Test
    fun `规则4 退避窗口内进来的读就地失败 不排队不再建`() {
        val clock = FakeClock()
        val opener = FakeSmbSessionOpener()
        val readFailures = mutableListOf<Triple<SmbReadOp, Long, Throwable>>()
        val lifecycle = newLifecycle(
            opener = opener,
            clock = clock::read,
            onReadFailed = { op, ms, _, t -> readFailures += Triple(op, ms, t) },
        )
        lifecycle.failEstablish(opener)
        readFailures.clear()

        val thrown = assertThrows(SmbRebuildBackedOffException::class.java) { lifecycle.read() }

        assertEquals("一条都没再建", 1, opener.openCalls.get())
        assertEquals("就地失败的形态", SmbFailureKind.OTHER, thrown.kind)
        assertEquals("打点也只有那一条 backoff", 1, readFailures.size)
        assertEquals("退避期内被拒的 ms 应该≈0（不排队、不碰 socket）", 0L, readFailures[0].second)
    }

    // 规则 5（会话建立成功才起心跳）与规则 6（探针自己走的那次读不算用户活动）钉在
    // `SmbSessionLifecycleHeartbeatTest`：那两条的判据是**虚拟时间上的探活节奏**，
    // 与本文件「真线程 + 闩锁」的写法不是一套。

    @Test
    fun `规则7 rebuilt 的真相是此前成功建立过会话 不是句柄非空`() {
        val reported = mutableListOf<Boolean>()
        val opener = FakeSmbSessionOpener()
        val lifecycle = newLifecycle(opener = opener, onEstablished = { reported += it })
        lifecycle.read() // 第一次成功建立：报 false

        // 拆会话（丢句柄）+ 重建：进入 establish 之前句柄已经是空的
        // ⇒ 用「句柄非空」判定会把这次重连报成首次建连（旧实现的实际缺陷）
        val attempts = AtomicInteger()
        lifecycle.withSession(SmbReadOp.STAT) { _ ->
            if (attempts.incrementAndGet() == 1) throw SocketException("对端断开")
        }

        assertEquals("重建那一次仍要报 true", listOf(false, true), reported)
        assertEquals("重建确实是新的一条会话", 2, opener.opened.get())
        assertEquals("重建时上一代句柄已经被丢掉（这正是旧判据会判错的那一瞬）", listOf(null), opener.previousHandles.drop(1))
    }

    @Test
    fun `规则8 换代那一瞬最坏 2×上限（已知的非硬上界）`() {
        // 机制可测：老一代在飞的读归还名额不计数 ⇒ 换代瞬间新代又能放满一批，两批可以叠加。
        // 真机上那一刻是否真发生**不与本用例等价**——那是 #113 的复现验收（本票只登记，不假装测到）。
        val lifecycle = newLifecycle(maxConcurrent = SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS)
        val running = AtomicInteger()
        val maxSeen = AtomicInteger()
        val release = CountDownLatch(1)
        val done = CountDownLatch(2 * SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS)

        fun worker(onEntered: CountDownLatch?) = Thread {
            lifecycle.withPermit {
                maxSeen.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                onEntered?.countDown()
                release.await() // 全部 8 条都占住，直到用例放行：这样「同时在飞几条」才是量出来的
                running.decrementAndGet()
            }
            done.countDown()
        }

        val oldEntered = CountDownLatch(SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS)
        val oldWorkers = List(SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS) { worker(oldEntered) }
        oldWorkers.forEach { it.start() }
        assertTrue("老一代 4 条都在飞", oldEntered.await(5, TimeUnit.SECONDS))

        // 换代
        assertTrue(lifecycle.invalidate(0L))
        lifecycle.settled()

        val newEntered = CountDownLatch(SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS)
        val newWorkers = List(SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS) { worker(newEntered) }
        newWorkers.forEach { it.start() }
        assertTrue("新一代又能放满一批", newEntered.await(5, TimeUnit.SECONDS))

        assertEquals(
            "换代那一瞬最坏 2×上限（spec 明说本次不做成硬上界：要让它收敛得让老一代的读重试前重新过闸）",
            2 * SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS,
            maxSeen.get(),
        )

        release.countDown()
        assertTrue("两批都要跑完", done.await(5, TimeUnit.SECONDS))
        (oldWorkers + newWorkers).forEach { it.join(5_000) }
    }

    // ---------- 用例脚手架 ----------

    /** 建连失败的一种形态：认证失败（非「可重连」故障，因此不会引出重试） */
    private fun authFailure(): SmbException = SmbException(SmbFailureKind.AUTH, "认证失败")

    /** 假时钟（纳秒）：用例自己推它，不睡 */
    private class FakeClock {
        private var nanos = 0L

        fun advance(ms: Long) {
            nanos += ms * NANOS_PER_MS
        }

        fun read(): Long = nanos

        private companion object {
            private const val NANOS_PER_MS: Long = 1_000_000L
        }
    }

    /** 会话音早周期的公共构造：假替身 + 假时钟 + 打点口（心跳默认在 IO 上、探针默认什么都不探） */
    private fun newLifecycle(
        opener: FakeSmbSessionOpener = FakeSmbSessionOpener(),
        clock: () -> Long = { 0L },
        maxConcurrent: Int = SmbSessionLifecycle.MAX_CONCURRENT_SESSION_READS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        probe: () -> Boolean = { false },
        onEstablished: (Boolean) -> Unit = {},
        onReadFailed: (SmbReadOp, Long, Boolean, Throwable) -> Unit = { _, _, _, _ -> },
    ): SmbSessionLifecycle<FakeSmbSessionHandle> = SmbSessionLifecycle(
        opener = opener,
        host = "smb-test",
        probe = probe,
        onSessionEstablished = onEstablished,
        onReadFailed = onReadFailed,
        dispatcher = dispatcher,
        nanoTime = clock,
        maxConcurrent = maxConcurrent,
    )

    /** 跑一次读（拿句柄跑 [body]，默认什么都不做） */
    private fun SmbSessionLifecycle<FakeSmbSessionHandle>.read(body: (FakeSmbSessionHandle) -> Unit = {}) {
        withSession(SmbReadOp.STAT) { body(it) }
    }

    /** 让「建连」必失败的那条读跑一次（退避计数 +1、窗口从这一刻起算） */
    private fun SmbSessionLifecycle<FakeSmbSessionHandle>.failEstablish(opener: FakeSmbSessionOpener) {
        opener.failures += authFailure()
        assertThrows(SmbException::class.java) { read() }
    }

    /** 记一次失败并返回这个窗口的长度（毫秒）：按 1 毫秒步进探到窗口结束为止 */
    private fun SmbSessionLifecycle<FakeSmbSessionHandle>.windowMsAfterFailure(
        opener: FakeSmbSessionOpener,
        clock: FakeClock,
    ): Long {
        failEstablish(opener)
        var ms = 0L
        while (isBackingOff()) {
            clock.advance(1)
            ms++
        }
        return ms
    }
}
