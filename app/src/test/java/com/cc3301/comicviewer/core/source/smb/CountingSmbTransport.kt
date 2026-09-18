package com.cc3301.comicviewer.core.source.smb

import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 计数型 SMB 传输装饰器（票 #30）：把「列目录往返次数」变成可断言的指标——
 * 本票的验收指标就是每层目录一次 `list`、子目录探测真并发且有上限、枚举期不读字节。
 *
 * [blockProbes] 让子目录探测堵在闸门上，用来观测并发峰值（[maxInFlight]）、
 * 也用来验证「取消后不再向未起飞的子目录发请求」；用例无论如何都要 [openProbeGate]。
 */
class CountingSmbTransport(
    private val delegate: SmbTransport,
    /** 根容器的 list 路径（SmbBackend 的起始路径）：它不算「子目录探测」，不受闸门影响 */
    private val rootPath: String = SmbPaths.ROOT,
) : SmbTransport {

    private val listCount = AtomicInteger(0)
    private val readCount = AtomicInteger(0)
    private val statCount = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    private val peakInFlight = AtomicInteger(0)
    private val probeGate = CountDownLatch(1)

    @Volatile
    private var gateClosed = false

    /** 同时处于 `list` 中的最大条数（过程峰值） */
    val maxInFlight: Int get() = peakInFlight.get()

    /** 目录列举往返次数（SMB 侧就是一次请求） */
    val listCalls: Int get() = listCount.get()

    /** 读字节次数（`readBytes` + `openRandomAccess`，即封面/包内数据真实传输的次数） */
    val readCalls: Int get() = readCount.get()

    /**
     * 「按 id 取节点」次数（票 #51）：SMB 的 `stat` 在真实实现里是 folderExists + 取文件信息两次往返，
     * 时间类排序若在比较器里按 id 取节点，一次排序就是 O(条目数 × log 条目数) 次 stat —— 本票的主凶。
     */
    val statCalls: Int get() = statCount.get()

    /** 让后续所有子目录探测堵在闸门上 */
    fun blockProbes() {
        gateClosed = true
    }

    /** 放行闸门：已堵住的探测继续，之后的探测不再等 */
    fun openProbeGate() {
        gateClosed = false
        probeGate.countDown()
    }

    fun resetCounters() {
        listCount.set(0)
        readCount.set(0)
        statCount.set(0)
        peakInFlight.set(0)
    }

    /** 轮询等在飞探测达到 [target]（返回是否等到；不抛异常，由用例断言） */
    fun awaitInFlight(target: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (peakInFlight.get() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        return peakInFlight.get() >= target
    }

    override fun list(path: String): List<SmbEntry> {
        listCount.incrementAndGet()
        // 先自增再更新峰值：updateAndGet 的 lambda 可能因 CAS 重试跑多次，不能在里面做副作用
        val now = inFlight.incrementAndGet()
        peakInFlight.updateAndGet { peak -> maxOf(peak, now) }
        try {
            if (gateClosed && SmbPaths.normalize(path) != rootPath) {
                probeGate.await(GATE_WAIT_SECONDS, TimeUnit.SECONDS)
            }
            return delegate.list(path)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun stat(path: String): SmbEntry? {
        statCount.incrementAndGet()
        return delegate.stat(path)
    }

    override fun readBytes(path: String): ByteArray {
        readCount.incrementAndGet()
        return delegate.readBytes(path)
    }

    override fun openRandomAccess(path: String): RandomAccessBytes {
        readCount.incrementAndGet()
        return delegate.openRandomAccess(path)
    }

    override fun close() = delegate.close()

    private companion object {
        const val POLL_MS = 10L
        const val GATE_WAIT_SECONDS = 30L
    }
}
