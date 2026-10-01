package com.cc3301.comicviewer.core.source.smb

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 会话就绪闸门 + 分批放行（修法第 2 条；纯内存状态，由 [SmbSessionGateTest] 锁定）。
 *
 * 为什么需要它（的 SMB 设备日志）：一次 SMB 会话停摆时，屏幕上的三十几条封面读
 * **各自**卡在自己的 socket 上（快的等了 7.6 秒、慢的 14.9 秒），会话重建成功后它们又**一起**返回
 * ⇒ 一屏封面全灰十几秒。现状里每一个失败的调用方还会各自 `closeQuietly()` + 重连一次
 * （`retryOnce` 的 reconnect），于是「刚建好的会话又被一批请求压住」。
 *
 * 两条承重语义：
 * 1. **重建期间不放行**（[invalidate] → [settled] 之间）：这一窗口里进入的读**等在闸上**——
 *    等的是内存里的信号，不是各自的 socket，因此不会各自把十几秒耗在一条死会话的往返上。
 * 2. **就绪后分批放行**：一次只放 [MAX_CONCURRENT_SESSION_READS] 条进去，其余按归还顺序补位，
 *    刚建好的会话不会再被几十个请求同时压住。
 *
 * **代次（epoch）**：会话每失效一次就开新的一代。
 * - 代次随 [withPermit] 交给调用方（它要拿这个号去 [invalidate]）；
 * - **老一代还在飞的读归还名额时不计数**——它们还的是已经作废的那批名额，照数还回去等于
 *   每重建一次名额就多一批，闸门形同虚设；
 * - [invalidate] 带调用方**用过的**代次：号已经变了说明别人拆过并（正在）重建，
 *   这时**不再拆第二次**（拆了就是把别人刚建好的会话又推倒），直接返回 `false` 让调用方重试即可。
 *
 * 等待用 `Condition` 而不是协程原语：[SmbTransport] 本身是阻塞接口、调用方在 IO 线程上，
 * 临界区里没有挂起点（与既有实现同形，也与 `CoverByteGate` 那种「挂起点只在 await 上」
 * 的情形不同）。**拆会话的那条读必须保证有一个出口喊 [settled]**，否则闸上等着的读永远醒不来。
 * `SmbjTransport` 这条链上的四个出口各接一处：**建连成功 / 建连失败 / 读再失败**三处喊 [settled]，
 * **传输被关闭**（来源实例释放）走 [close]——那是**永久关闭、不再放行**，迟到的读以既有失败形态退出。
 */
internal class SmbSessionGate(
    maxConcurrent: Int = MAX_CONCURRENT_SESSION_READS,
) {

    /** 闸位上限非法时按 1 兜底：宁慢勿冲（与 [com.cc3301.comicviewer.core.view.CoverByteGate] 同一口径） */
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
    private var closed = false

    /** 闸是不是已经永久关闭（`SmbjTransport` 用它拦住「释放后重试又建一条新会话」） */
    val isClosed: Boolean
        get() = lock.withLock { closed }

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
     * 重建结束（**成功、失败、传输被关闭都算**）：把名额补满到上限，放行下一批。
     * 失败也要放行——不然闸上等着的读会永远醒不来；会话建不起来的错误由各自的调用方上抛。
     *
     * **只有「重建中」这一次生效**（就绪状态下的重复喊是空操作）：一次重建可能从好几处喊放行
     * （建连成功一处、异常出口两处，见 `SmbjTransport`），每次都补名额的话在飞的名额会被越补越多，
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
     * 传输被关闭（来源实例释放，见 `SmbjTransport.close`）：闸**永久关闭**。
     *
     * 为什么不是「喊一次 [settled] 把等人放走」：被放走的读接着会各自去建一条新会话
     * ⇒ **已释放的来源实例又被迟到的读拉起一条 SMB 会话**。关闭后闸上等着的读改为
     * 就地失败退出（唤醒它们抛 [closedFailure]，不夺名额、不碰会话）。
     */
    fun close() {
        lock.lock()
        try {
            closed = true
            releasedSignal.signalAll()
        } finally {
            lock.unlock()
        }
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

    /** 归还名额：只还当代的（老一代的归还作废，见类注释） */
    private fun leave(enteredEpoch: Long) {
        lock.lock()
        try {
            if (enteredEpoch == epoch) permits++
            releasedSignal.signalAll()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /**
         * 就绪后一批放行几条（第 1 条给的上界 2~4 取 4，与封面取字节闸位同一个数）：
         * 一次会话上同时最多 4 条读在飞，越界的排队——正是设备日志里「30+ 个 worker 压在同一条会话上」
         * 要消掉的形态。封面那条通路另有可见优先闸，这里只管「同时压几条到会话上」。
         *
         * **这个数不是任何时刻的硬上界**：换代那一瞬最坏到 2×上限（= 8）——[invalidate] 之前已经在飞的
         * 读（至多 4 条）带的是老一代名额、归还不计数，因此 [settled] 为新代补满 4 条时它们可能都还在飞。
         * 要让它成为硬上界得让老一代的读重试前重新过闸（那是行为改动，不做）。
         *
         * 只管 **四个入口操作**（列目录/stat/取整份字节/打开随机访问）：已经打开的那个随机访问句柄上的
         * 后续读不过闸——它们不是那 30+ 条并发读的来源。
         */
        const val MAX_CONCURRENT_SESSION_READS: Int = 4
    }
}
