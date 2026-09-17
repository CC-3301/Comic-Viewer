package com.cc3301.comicviewer.core.source.remote

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

    /** 读一段（不超过 [blockBytes] 时走缓存）；EOF 处自动截短 */
    final override fun read(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset < 0 || offset >= size) return ByteArray(0)
        val capped = minOf(len.toLong(), size - offset).toInt()
        // 大读不经过小块缓存（否则会把缓存反复冲掉）
        if (capped > blockBytes) return fetch(offset, capped)
        if (offset < cacheStart || offset + capped > cacheStart + cache.size) {
            cacheStart = offset - offset % blockBytes
            val want = minOf(blockBytes.toLong() * cacheBlocks, size - cacheStart).toInt()
            cache = fetch(cacheStart, want)
        }
        val from = (offset - cacheStart).toInt()
        if (from >= cache.size) return ByteArray(0)
        return cache.copyOfRange(from, minOf(from + capped, cache.size))
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
