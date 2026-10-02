package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.remote.isRecoverableRemoteFailure
import com.cc3301.comicviewer.core.source.remote.retryOnce
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 「连服务器」这一步的可替换实现（票 #151 第 1 步：开可替换口）。
 *
 * 生产 = `SmbjSessionOpener`（smbj 的连接 → 认证 → 进共享三步）；测试 = 假替身
 * （连得上/连不上、连上后中途断开，见 `FakeSmbSessionOpener`）。生命周期只通过这个接口碰会话，
 * 因此「会话活没活、谁在重建、要不要退避」这套逻辑能在 JVM 上跑用例。
 *
 * [S] = 会话句柄类型（生产 = `SmbjSessionHandle` 那四件套，测试 = 假替身自己的句柄）。
 */
internal interface SmbSessionOpener<S> {

    /**
     * 建一条会话：先把 [previous] 丢开（CLOSE 段）→ 连接（CONNECT）→ 认证（AUTH）→ 进共享（SHARE）。
     * 抛异常 = 这一次建立失败（[SmbRebuildSegment] 指出失败在哪一段）。
     *
     * [previous] 由**实现**负责关掉（它是上一代的句柄，生命周期进入这里之前已经不再指向它）；
     * 没有上一代时是 null。[attempt] = 本轮第几次尝试（连续失败数 + 1），进 `smbRebuild attempt=` 那一行。
     */
    fun open(previous: S?, attempt: Int): S

    /** 句柄还连着吗（断链后重建的判定；smbj 的 `isConnected` 只是本地标志，但这一层没有更准的判据） */
    fun isAlive(handle: S): Boolean

    /** 关掉一个句柄（来源实例释放 / 重建前先丢旧的） */
    fun close(handle: S)
}

/**
 * 一次会话建立（新建或重建）里可以失败的**四段**（`smbRebuild failed=` 的取值）：
 * 关旧会话 / 连接 / 认证 / 进共享。顺序就是 [SmbSessionOpener.open] 里的执行顺序，
 * 因此「失败在哪一段」直接指到那一行代码。
 */
internal enum class SmbRebuildSegment(val token: String) {
    CLOSE("close"),
    CONNECT("connect"),
    AUTH("auth"),
    SHARE("share"),
}

/**
 * 退避期内被就地拒掉的读。
 *
 * 单独一个类型只为让 `smbReadFail kind=` 分得出 [SmbReadFailKind.BACKOFF]：设备上判读「退避生效了没有」
 * 靠的就是这一类与真读失败分开（`ms≈0` + 紧跟在一条 `smbRebuild failed=` 之后）。
 * 它是 [SmbException] 的子类：装饰器与上层的失败归类路径一行都不用改（`asSmbException` 原样透传）。
 *
 * **不是可重连故障**：它的 kind 是 [SmbFailureKind.OTHER]（消息里没有超时/认证/不存在任一标记），
 * 因此 `retryOnce` 不会对它做无意义的重试——重试又会被退避挡回来。
 */
internal class SmbRebuildBackedOffException(
    message: String,
) : SmbException(SmbFailureKind.OTHER, message)

/**
 * SMB 会话生命周期（票 #151）：**会话句柄 + 代次 + 就绪/重建中/退避中/已关闭 + 心跳 + 打点判定**
 * 一处持有，外面只通过它跟会话打交道。传输层退化成「拿句柄跑一次读」的薄壳。
 *
 * 为什么收拢：这六样东西原来分在 `SmbjTransport` / `SmbSessionGate` / `SmbSessionHeartbeat` /
 * `SmbRebuildBackoff` / `SmbSessionReporter` 五个地方，**跨文件才成立的规则有 8 条**，只写在注释里：
 *
 * 1. 拆会话那条读必须有一个出口喊放行（[withSession] 的 `catch` 就是那个出口）；
 * 2. 重建失败也要放行（[establish] 的失败分支），否则等着的读永远醒不来；
 * 3. 已释放就不拆、不再拉起新会话（[closed]）；
 * 4. 退避窗口内进来的读**就地失败**（[isBackingOff]，不排队、不再建）；
 * 5. 会话建立成功才起心跳（[startHeartbeat] 只在 [establish] 成功之后喊）；
 * 6. 探针自己走的那次读不算用户活动（[sessionUsed] 在探针结束后被清掉）；
 * 7. `rebuilt` 的真相 = 此前成功建立过会话（[establishedBefore]），不是「句柄 != null」；
 * 8. 换代那一瞬最坏 2×并发上限（老一代在飞的读归还名额不计数）——**这不是任何时刻的硬上界**，
 *    见 [MAX_CONCURRENT_SESSION_READS]。
 *
 * 三条承重语义（都来自 2026-09-27~29 的设备日志，逐条见各方法与常量的 KDoc）：
 *
 * - **重建期间不放行**：会话失效到就绪之间进入的读等在内存信号上（不是各自的 socket），
 *   就绪后**分批放行**一次 [MAX_CONCURRENT_SESSION_READS] 条，刚建好的会话不会再被一批请求压住；
 * - **重建失败退避** 1s → 2s → 4s（封顶 8s）：窗口内进来的读就地失败，会话建起来即清零；
 * - **会话建立成功后起一条探活心跳**：空闲时每 30 秒读一次共享根，让 App 在用户之前撞上死会话。
 *
 * 等待用 `Condition` 而不是协程原语：本类的读接口是**阻塞**的（`SmbTransport` 是阻塞接口、
 * 调用方在 IO 线程上），临界区里没有挂起点。心跳那一支是协程（时长可注入，用例用虚拟时间跑）。
 *
 * **两个口**：传输层只走 [withSession]（一次读的完整流程）；[withPermit] / [invalidate] / [settled] /
 * [close] 是闸门本身的口，用例拿它锁语义（例如「重建期间不放行」「同一代只有第一个失败者拆会话」）。
 */
internal class SmbSessionLifecycle<S>(
    /** 「连服务器」的可替换实现（生产 = smbj；测试 = 假替身），见 [SmbSessionOpener] */
    private val opener: SmbSessionOpener<S>,
    /** 主机（含端口）：只进「退避期内被拒」那句中文提示，让用户知道卡在哪台服务器上 */
    private val host: String = "",
    /** 「这次失败值不值得重连」的判定（生产 = [isRecoverableRemoteFailure]，与 WebDAV 共用同一份） */
    private val isRecoverableFailure: (Throwable) -> Boolean = ::isRecoverableRemoteFailure,
    /** 一次探活：读一次共享根（走现成的读路径）；返回 `false` = 这一拍什么都没探 */
    private val probe: () -> Boolean = { false },
    /**
     * 会话建立成功之后的打点（参数 = 本次建立是否属于**重建**）：只在建连真正成功之后喊，
     * 失败时既不打点也不改状态——判据与次序就是原来 `SmbSessionReporter` 那两条。
     */
    private val onSessionEstablished: (rebuilt: Boolean) -> Unit = {},
    /**
     * 每一次**读尝试**失败的打点：操作 / 从发起到失败的毫秒数 / 此刻传输是否已释放 / 异常。
     * 含「失败后重试成功」的那一次——那正是会话失效被发现的时刻。
     */
    private val onReadFailed: (op: SmbReadOp, ms: Long, appReleased: Boolean, t: Throwable) -> Unit =
        { _, _, _, _ -> },
    /** 心跳每一拍的上报：`(probed, ok, ms)`；`probed=false` 时另两个字段无意义 */
    private val onProbe: (probed: Boolean, ok: Boolean, ms: Long) -> Unit = { _, _, _ -> },
    /** 心跳跑在哪个调度器上（生产 = [Dispatchers.IO]：探针是可能阻塞的来源 I/O） */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 时间源（退避窗口 / 读耗时 / 探针耗时同一口径）：可注入，用例用假时钟，不靠 sleep 决定成败 */
    private val nanoTime: () -> Long = System::nanoTime,
    /** 闸位上限（见 [MAX_CONCURRENT_SESSION_READS]） */
    maxConcurrent: Int = MAX_CONCURRENT_SESSION_READS,
    /** 探活间隔（见 [PROBE_INTERVAL_MS]） */
    private val probeIntervalMs: Long = PROBE_INTERVAL_MS,
    /** 探活失败退避的上限（见 [MAX_INTERVAL_MS]） */
    private val maxProbeIntervalMs: Long = MAX_INTERVAL_MS,
) {

    // ---------- 会话句柄 ----------

    /**
     * 当前这一代的会话句柄；`null` = 没有会话（没建过 / 刚被拆掉 / 已释放）。
     * 只在 [discardHandle] / [establish] 里改，两处都在本类监视器下。
     */
    @Volatile
    private var handle: S? = null

    // ---------- 就绪 / 重建中 / 代次 / 已关闭 ----------

    /** 闸位上限非法时按 1 兜底：宁慢勿冲（与 `CoverByteGate` 同一口径） */
    private val batch = maxConcurrent.coerceAtLeast(1)

    private val lock = ReentrantLock()

    private val releasedSignal = lock.newCondition()

    /** 没有重建在飞（`false` = 会话正处于「已失效、还没就绪」的窗口里） */
    private var ready = true

    /** 当前这一代还剩几个名额 */
    private var permits = batch

    /** 会话代次：每失效一次 +1 */
    private var epoch = 0L

    /** 闸已永久关闭（来源实例释放）：此后进入的读一律不放行，各自以失败退出 */
    @Volatile
    private var closed = false

    /** 闸是不是已经永久关闭（读路径用它拦住「释放后重试又建一条新会话」） */
    val isClosed: Boolean
        get() = closed

    // ---------- 退避 ----------

    /** 连续失败次数（一次成功清零）：退避时长与 [nextAttempt] 都由它推出来 */
    private var consecutiveFailures = 0

    /** 退避窗口的结束时刻（[nanoTime] 口径）；`0` = 没有窗口 */
    private var blockedUntilNanos = 0L

    // ---------- 打点判定 ----------

    /** 此前是否**成功建立过**会话：`rebuilt` 判定的唯一真相（跨拆会话存活，不能用「句柄 != null」代替） */
    private var establishedBefore = false

    // ---------- 心跳 ----------

    /** 上一次 tick 之后有没有真实读动过会话（探针自己的读会被清掉） */
    private val sessionUsed = AtomicBoolean(false)

    /** 心跳循环的宿主；没建过会话就是 null（不预先建作用域、不起线程） */
    private var heartbeatScope: CoroutineScope? = null

    /** 终态：来源实例释放（[close]）之后置位，此后不再复活循环 */
    private var heartbeatStopped = false

    // ---------- 读路径 ----------

    /**
     * 一次读的完整流程：**退避窗口拦 → 等会话就绪 + 占一个名额 → 拿句柄跑一次读 → 连接层故障重连一次**。
     * 非连接类错误（认证/不存在/权限）直接上抛，不做无意义的重试。
     *
     * **退避窗口内进来的读就地失败**：不排队、不碰 socket、不再建——封面那条由
     * `DocumentTreeSource.coverBytes`（与 `CoverThumb`）吞成 null ⇒ 屏上继续骨架，阅读器取页则拿到中文提示。
     * 因此这个判断放在进闸**之前**：排队等一个明知建不起来的会话没有意义。
     *
     * 规则 1 与规则 2 的出口都在这里：[establish] 的成功/失败两条路各喊一次 [settled]，
     * 而「我拆的会话我负责放行」的那条兜底在下面那个 `catch`。
     */
    fun <T> withSession(op: SmbReadOp, block: (S) -> T): T {
        val startedNanos = nanoTime()
        if (isBackingOff()) throw loggedReadFail(op, startedNanos, backoffFailure())
        return withPermit { usedEpoch ->
            // 会话刚被真实读用过 → 心跳的下一拍不必再探（探针自己也走这里，探针结束后会把它自己的记数清掉）
            sessionUsed.set(true)
            // 我拆的会话，就必须由一个出口保证放行（挂在「重建中」 = 后面所有读一起挂住，比多放一批糟得多）
            var rebuiltByMe = false
            try {
                retryOnce(
                    isRecoverable = isRecoverableFailure,
                    reconnect = {
                        // 只有「我用过的还是当前这一代」时才由我拆会话：别人已经拆过就直接重试，
                        // 否则每次失败都把别人刚建好的会话推倒（设备日志里 30+ 个 worker 各重连一次）
                        rebuiltByMe = invalidate(usedEpoch)
                        // 已释放就不拆了（规则 3）：重试会直接失败退出（见 [establish]），不再拉起一条新会话
                        if (rebuiltByMe && !closed) discardHandle()
                    },
                    block = { attempt(op, startedNanos) { block(session()) } },
                )
            } catch (t: Throwable) {
                // 拆会话那条读再失败也要喊放行（[settled] 只在「重建中」生效，因此这里不会重复补名额）
                if (rebuiltByMe) settled()
                throw t
            }
        }
    }

    /** 一次读的尝试：失败时就地记一条 `smbReadFail` 再上抛（每次尝试各一条，不重复记） */
    private fun <T> attempt(op: SmbReadOp, startedNanos: Long, body: () -> T): T = try {
        body()
    } catch (t: Throwable) {
        throw loggedReadFail(op, startedNanos, t)
    }

    /** 记一条 `smbReadFail`（失败那一刻：操作 / 从发起到失败等了多久 / 类型 / 异常类名），异常原样返回便于 `throw` */
    private fun <T : Throwable> loggedReadFail(op: SmbReadOp, startedNanos: Long, failure: T): T {
        onReadFailed(op, elapsedMs(startedNanos), closed, failure)
        return failure
    }

    /** 从 [startedNanos] 到现在有多少毫秒（打点里的 `ms=` 统一这个口径） */
    private fun elapsedMs(startedNanos: Long): Long = (nanoTime() - startedNanos) / NANOS_PER_MS

    // ---------- 会话建立 / 重建 ----------

    /**
     * 现成的会话句柄：还没有 / 已经断了 / 刚被拆掉 ⇒ 由**本条读**负责建立（或重建）一条。
     * 建立本身在监视器下串行化：同一时刻只有一条读在建会话（其余等在闸上或等这把锁）。
     */
    @Synchronized
    private fun session(): S {
        handle?.takeIf { opener.isAlive(it) }?.let { return it }
        return establish()
    }

    /**
     * 一次会话建立（新建或重建），四种情形都在这里收口：
     * 已释放（规则 3）/ 退避窗口内（规则 4）就直接失败；成功则清退避、放行、起心跳、打点；
     * 失败则记退避、**照旧放行**（规则 2，不然闸上等着的读永远醒不来），异常由各自的调用方上抛。
     *
     * [SmbRebuildSegment] 的分段耗时与 `smbRebuild` 行由 [opener] 自己产出（那是 smbj 的三步）；
     * 本方法给它的只有「第几次尝试」。
     */
    @Synchronized
    private fun establish(): S {
        // 实例已释放：连「已在飞那条读的重试」也不该把会话建回来（释放后仍会新建会话是已知缺陷）
        if (closed) throw closedFailure()
        // 退避窗口里不再尝试重建：进闸之后到建连之前可能刚好失败，因此这里再拦一次
        if (isBackingOff()) throw backoffFailure()
        val attempt = nextAttempt()
        // 第 7 条规则的判据：**此前是否成功建立过**。重连路径上句柄已被丢掉，
        // 用「句柄 != null」会把「刚被丢掉的死会话重新建起来」报成首次建连（旧实现的实际缺陷）。
        val rebuilt = establishedBefore
        val previous = handle
        handle = null // 上一代句柄交给 opener 关（它是 CLOSE 那一段），本类先不再指向它
        val newHandle = try {
            opener.open(previous, attempt)
        } catch (t: Throwable) {
            recordFailure()
            // 建不起来也要放行，否则闸上等着的读永远醒不来（错误照旧由各自的调用方上抛）
            settled()
            throw t
        }
        handle = newHandle
        recordSuccess()
        settled()
        // 会话就绪之后才起心跳（规则 5）：它盯的是「这条已经建好的会话会不会被服务端作废」
        startHeartbeat()
        // 打点在成功之后（原来 `SmbSessionReporter.establish` 的两条语义）：失败时不喊、不改状态
        onSessionEstablished(rebuilt)
        establishedBefore = true
        return newHandle
    }

    /** 丢掉当前句柄（重建前 / 释放时）：旧的是死会话，留着只会让下一条读再撞一次 */
    @Synchronized
    private fun discardHandle() {
        val old = handle ?: return
        handle = null
        opener.close(old)
    }

    // ---------- 闸门 ----------

    /**
     * 等会话就绪 + 占一个名额，执行 [block]（参数 = 进入时的**会话代次**，读失败时拿它去 [invalidate]），
     * 结束或抛异常都归还名额并唤醒等牌的下一位。
     */
    fun <T> withPermit(block: (epoch: Long) -> T): T {
        val enteredEpoch = enter()
        try {
            return block(enteredEpoch)
        } finally {
            leave(enteredEpoch)
        }
    }

    /**
     * 声明「我用过的那一代会话已失效」：由本次调用负责拆会话并重建。
     *
     * 返回 `true` = 号还是当前这一代，由调用方去拆（拆完建好时喊 [settled]）；
     * `false` = 别人已经拆过（号已经变了），**这次不要拆**，直接重试就能落到那条（正在）重建的会话上。
     */
    fun invalidate(usedEpoch: Long): Boolean {
        lock.lock()
        try {
            if (usedEpoch != epoch) return false
            epoch++
            ready = false
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * 重建结束（**成功、失败、传输被关闭都算**）：把名额补满到本轮上限，放行下一批。
     * 失败也要放行（规则 2）——不然闸上等着的读会永远醒不来；会话建不起来的错误由各自的调用方上抛。
     *
     * **只有「重建中」这一次生效**（就绪状态下的重复喊是空操作）：一次重建可能从好几处喊放行
     * （建连成功一处、读再失败一处），每次都补名额的话在飞的名额会被越补越多，
     * 「同时最多 N 条」这条口径就没了。
     */
    fun settled() {
        lock.lock()
        try {
            if (closed || ready) return
            ready = true
            permits = batch
            releasedSignal.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 传输被关闭（来源实例释放，见 `SmbjTransport.close`）：闸**永久关闭**、心跳一并停掉、当前句柄关掉。
     *
     * 为什么不是「喊一次 [settled] 把等人放走」：被放走的读接着会各自去建一条新会话
     * ⇒ **已释放的来源实例又被迟到的读拉起一条 SMB 会话**。关闭后闸上等着的读改为
     * 就地失败退出（唤醒它们抛 [closedFailure]，不夺名额、不碰会话）。
     */
    fun close() {
        closed = true
        lock.lock()
        try {
            releasedSignal.signalAll()
        } finally {
            lock.unlock()
        }
        stopHeartbeat()
        discardHandle()
    }

    /** 闸已关闭时迟到的读的失败形态：与传输层一致的中文可读提示，而不是让它们继续等 */
    private fun closedFailure(): SmbException =
        SmbException(SmbFailureKind.OTHER, "来源已释放：SMB 会话已关闭")

    /** 等就绪且有空名额，返回进入时的代次（闸已关闭则不放行，直接失败） */
    private fun enter(): Long {
        lock.lock()
        try {
            if (closed) throw closedFailure()
            while (!ready || permits <= 0) {
                releasedSignal.await()
                if (closed) throw closedFailure()
            }
            permits--
            return epoch
        } finally {
            lock.unlock()
        }
    }

    /** 归还名额：只还当代的（老一代的归还作废，见 [MAX_CONCURRENT_SESSION_READS]） */
    private fun leave(enteredEpoch: Long) {
        lock.lock()
        try {
            if (enteredEpoch == epoch) permits++
            releasedSignal.signalAll()
        } finally {
            lock.unlock()
        }
    }

    // ---------- 重建退避 ----------

    /** 下一次重建是第几次尝试（连续失败次数 + 1）：进 `smbRebuild attempt=` 行 */
    internal fun nextAttempt(): Int = synchronized(this) { consecutiveFailures + 1 }

    /** 是不是还在退避窗口里（窗口内不许开始任何重建，进来的读就地失败） */
    internal fun isBackingOff(): Boolean = synchronized(this) { nanoTime() < blockedUntilNanos }

    /** 一次重建失败：连续失败 +1，并按 [delayMsFor] 开一个退避窗口（从**这一次失败的时刻**起算） */
    private fun recordFailure() = synchronized(this) {
        consecutiveFailures++
        blockedUntilNanos = nanoTime() + delayMsFor(consecutiveFailures) * NANOS_PER_MS
    }

    /** 一次重建成功：退避立刻清零（连续失败归零、窗口马上结束） */
    private fun recordSuccess() = synchronized(this) {
        consecutiveFailures = 0
        blockedUntilNanos = 0L
    }

    /** 第 `failures` 次连续失败对应的退避时长：1s → 2s → 4s，封顶 [MAX_DELAY_MS] */
    private fun delayMsFor(failures: Int): Long =
        (BASE_DELAY_MS shl (failures - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_DELAY_MS)

    /** 退避窗口内的失败形态：一条中文提示、`kind=backoff` 一类，不排队也不再建 */
    private fun backoffFailure(): SmbRebuildBackedOffException =
        SmbRebuildBackedOffException("SMB 会话正在重连，稍后重试（" + host + "）")

    // ---------- 探活心跳 ----------

    /**
     * 记一次「会话被真实读用过」：由 [withSession] 在每次读一开始喊。
     * 语义是「这次读一开始会话就（被认为）是活的」，因此下一个 tick 不必再探（规则 6 的另一半）。
     */
    fun noteActivity() {
        sessionUsed.set(true)
    }

    /**
     * 会话建立成功后启动（见 [establish]；重复调用是空操作——一次会话可能建成功后又被重建，那条路径也会走到这）。
     *
     * **[close] 之后是空操作，且只单向**：`close()` 先置 [closed] 再停循环，而建会话是**慢 I/O**，
     * 一条在置位前就进了 [session] 的读可以在 [close] 之后才建好并走到这里——那时循环已被停掉，
     * 只判「有没有作用域」会新建一个把心跳复活，而之后没人再关它：探针撞上 [closed] 会直接返回 false
     * （[probeOnce]）⇒ 判成功 ⇒ **每 30 秒空转一次、永不退避**，循环永不结束。
     * 这与 `docs/spec/sources.md` 的「来源实例释放后心跳一并停掉」相抵。
     */
    @Synchronized
    private fun startHeartbeat() {
        if (heartbeatStopped || closed) return
        if (heartbeatScope != null) return
        val newScope = CoroutineScope(SupervisorJob() + dispatcher)
        heartbeatScope = newScope
        newScope.launch {
            // wait 是「下一次探活的间隔」：失败翻倍到封顶，成功回到基础间隔
            var wait = probeIntervalMs
            while (isActive) {
                delay(wait)
                if (sessionUsed.getAndSet(false)) {
                    onProbe(false, true, 0L) // 被真实读顶掉：这一拍没探
                    continue // 上一条真实读已经证明会话活着
                }
                val startedNanos = nanoTime()
                val outcome = runCatching { probeOnce() }
                val ms = (nanoTime() - startedNanos) / NANOS_PER_MS
                sessionUsed.set(false) // 探针刚走的那次读记的活动是它自己的，不算用户活动（规则 6）
                // 探针抛异常时 `getOrNull()` 是 null：那一次是**真探了**（只是失败了）
                onProbe(outcome.getOrNull() ?: true, outcome.isSuccess, ms)
                wait = if (outcome.isSuccess) probeIntervalMs else minOf(wait * 2, maxProbeIntervalMs)
            }
        }
    }

    /**
     * 传输被释放：停掉循环（重复调用是空操作），并置终态——此后 [startHeartbeat] 不再复活循环。
     * 已经在飞的那次探针不打断，读超时后自然结束。
     */
    @Synchronized
    private fun stopHeartbeat() {
        heartbeatStopped = true
        heartbeatScope?.cancel()
        heartbeatScope = null
    }

    /**
     * 一次探活：读一次共享根（[probe] 走的是同一条读路径，因此算四个入口里的一条、也过同一道闸）。
     *
     * 返回 `false` = **这一拍什么都没探**：没有会话（没建过 / 正被拆掉重建 / 已释放）时探针什么都不做——
     * 它的职责是保活，不是把一个没被用过的来源拉起来联网（下一次真实读自己会走建立路径）。
     *
     * 返回 `false` 而不是把空转报成一次成功还有一个判读上的原因：退避窗口里探针本来就什么都不做，
     * 若报成成功（`ms=0`），设备上会把「空转」误读成「心跳每 30 秒探活成功」。
     */
    private fun probeOnce(): Boolean {
        if (closed || handle == null) return false
        return probe()
    }

    internal companion object {
        /**
         * 就绪后一批放行几条（上界 2~4 取 4，与封面取字节闸位同一个数）：
         * 一次会话上同时最多 4 条读在飞，越界的排队——正是设备日志里「30+ 个 worker 压在同一条会话上」
         * 要消掉的形态。封面那条通路另有可见优先闸，这里只管「同时压几条到会话上」。
         *
         * **这个数不是任何时刻的硬上界**（第 8 条规则）：换代那一瞬最坏到 2×上限（= 8）——[invalidate]
         * 之前已经在飞的读（至多 4 条）带的是老一代名额、归还不计数，因此 [settled] 为新代补满 4 条时
         * 它们可能都还在飞。要让它成为硬上界得让老一代的读重试前重新过闸（那是行为改动，本票不做）。
         *
         * 只管 **四个入口操作**（列目录/stat/取整份字节/打开随机访问）：已经打开的那个随机访问句柄上的
         * 后续读不过闸——它们不是那 30+ 条并发读的来源。
         */
        const val MAX_CONCURRENT_SESSION_READS: Int = 4

        /** 探活间隔（2026-09-28 定的 30 秒：设备日志里最短重建间隔约 1 分钟，30s 在其之下） */
        const val PROBE_INTERVAL_MS: Long = 30_000L

        /** 失败退避的上限（服务器不可达时最多每 5 分钟试一次） */
        const val MAX_INTERVAL_MS: Long = 300_000L

        /** 第一次重建失败的退避（2026-09-29 定：1 → 2 → 4，封顶 8 秒） */
        const val BASE_DELAY_MS: Long = 1_000L

        /** 退避上限 8 秒：再久就不是「压住失败次数」而是「会话一直不可用」了（也用例钉住） */
        const val MAX_DELAY_MS: Long = 8_000L

        /** 移位上限：封顶前不让 `BASE_DELAY_MS shl shift` 溢出（1s shl 13 已远超 8 秒上限） */
        private const val MAX_SHIFT: Int = 13

        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
