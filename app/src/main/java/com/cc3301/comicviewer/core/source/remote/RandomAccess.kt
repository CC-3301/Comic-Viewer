package com.cc3301.comicviewer.core.source.remote

import com.cc3301.comicviewer.core.source.PerfTiming
import com.cc3301.comicviewer.core.source.zip.RandomAccessBytes

/**
 * 带块缓存的随机访问（票 11/12：SMB 与 WebDAV 共用）。
 *
 * ZIP 解析每个条目要做多次小读（u16/u32/文件名/条目数据），无缓存时每次小读 = 一次网络往返，
 * 一个 200 页 CBZ 会是上千次往返；按块预读后往返次数降一个数量级。
 * 子类只需实现 [fetch]（真正的一段读）与 [size]。
 */
abstract class BlockCachedRandomAccess(
    private val blockBytes: Int = DEFAULT_BLOCK_BYTES,
    private val cacheBlocks: Int = DEFAULT_CACHE_BLOCKS,
) : RandomAccessBytes {

    private var cacheStart = -1L
    private var cache = ByteArray(0)

    /**
     * 句柄长度只解析一次（票 #91）；本基类不缓存任何取消/中断协议。
     *
     * 根因（承重处详述，另两处只有指针）：[size] 在 SMB 上不是内存读，而是网络往返
     * （`SmbRandomAccess.size` → `SmbFile.getLength()` → `DiskEntry.getFileInformation` → `queryInfo`，
     * 每次访问一次 QUERY_INFO）；ZIP 解析却是「每个条目若干次小读」（u32 签名、5×u16、文件名各一次），
     * [read] 每次又要读 2~3 次长度。实测（60 条目的包）：未收口时**一次开包 1090 次长度解析**，
     * 收口后 2 次且与条目数无关；上界由 `RemoteArchiveReadCostTest` 钉住。
     *
     * 语义代价（记录）：长度在首次读时**固化**——会话中途文件被替换/改变大小时按首次读到的长度读
     * （不再是「每次拿最新长度」）。这样反而不会读到新旧混合的内容，最坏是在读到尾部时报读取错误。
     */
    private val handleSize: Long by lazy(LazyThreadSafetyMode.NONE) { size }

    /** 读一段（不超过 [blockBytes] 时走缓存）；EOF 处自动截短 */
    final override fun read(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= handleSize) return ByteArray(0)
        val capped = minOf(len.toLong(), handleSize - offset).toInt()
        // 大读不经过小块缓存（否则会把缓存反复冲掉）
        if (capped > blockBytes) return timedFetch("direct", offset, capped)
        if (offset < cacheStart || offset + capped > cacheStart + cache.size) {
            cacheStart = offset - offset % blockBytes
            val want = minOf(blockBytes.toLong() * cacheBlocks, handleSize - cacheStart).toInt()
            cache = timedFetch("block", cacheStart, want)
        }
        val from = (offset - cacheStart).toInt()
        if (from >= cache.size) return ByteArray(0)
        return cache.copyOfRange(from, minOf(from + capped, cache.size))
    }

    /**
     * 取数 + 真机观测点（票 #91 的验收协议）：开关与 logcat 关键字见 [PerfTiming]。
     * 关闭时零开销（[PerfTiming.log] 惰性求值），打开时打印每一次真实取数的区间、字节数与耗时
     * ——「每步往返」就是排查「卡在哪一步」所需的证据。
     */
    private fun timedFetch(kind: String, offset: Long, len: Int): ByteArray {
        val startedNanos = System.nanoTime()
        val bytes = fetch(offset, len)
        PerfTiming.log {
            "remoteRead kind=$kind offset=$offset len=$len bytes=${bytes.size} ms=" +
                ((System.nanoTime() - startedNanos) / 1_000_000)
        }
        return bytes
    }

    /** 真正读 [offset, offset+len)：允许短读（EOF），实现方需保证不越界读 */
    protected abstract fun fetch(offset: Long, len: Int): ByteArray

    protected companion object {
        const val DEFAULT_BLOCK_BYTES = 64 * 1024
        const val DEFAULT_CACHE_BLOCKS = 4
    }
}

/**
 * 随机访问的失败归类装饰器（票 11/12 review P1）：`read`/`close` 在后台线程调用，
 * 不经过传输层的归类装饰器，若不包一层，断链会以裸 IO 异常冒泡，
 * 被上层（CBZ 元数据解析）当成「损坏的压缩包」吞掉 → 静默 0 页。
 */
class ClassifyingRandomAccess(
    private val delegate: RandomAccessBytes,
    private val toRemoteException: (Throwable) -> Exception,
) : RandomAccessBytes {

    override val size: Long get() = delegate.size

    override fun read(offset: Long, len: Int): ByteArray = try {
        delegate.read(offset, len)
    } catch (t: Throwable) {
        throw toRemoteException(t)
    }

    override fun close() {
        runCatching { delegate.close() }
    }
}
