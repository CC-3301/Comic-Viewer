package com.cc3301.comicviewer.core.view

/**
 * 解码位图的内存缓存（票 #108 r5，由 [DecodedImageCacheTest] 锁定）：**页面位图与封面位图各占一份预算**。
 *
 * 为什么必须分区（真机现象：从阅读器返回书柜，书的封面变成灰块、要等好一会才出来）：
 * 改动前两者共用**一个** `LruCache`，容量按堆内存折算（`maxMemory/8`）。阅读器解的是整窗宽的页面位图
 * （条漫一页 RGB_565 就是几十 MB），一次阅读就足以把缓存写满——LRU 于是把「几分钟前看过的封面」整批淘汰。
 * 返回书柜时封面必须重新取字节、重新解码（SMB/WebDAV 上就是「等好一会」），骨架于是又出现一次。
 *
 * 分区之后，**页面位图怎么写都动不到封面分区**（反之亦然）：阅读会话不再吃掉封面，跨阅读会话保留。
 * 两份额度各自越界时按**最近最少使用**淘汰（同 `LruCache` 的语义：`get` 命中即刷新使用顺序）。
 *
 * 线程安全：解码发生在 `Dispatchers.IO` 的工作线程、查询发生在组合线程，两份额度各自加锁
 * （与改动前的 `android.util.LruCache` 同一口径）。
 */
internal class DecodedImageCache<V>(
    /** 页面位图分区的预算（KB） */
    pageBudgetKb: Int,
    /** 封面位图分区的预算（KB） */
    coverBudgetKb: Int,
    /** 一项占多少 KB（调用方按位图的 `allocationByteCount` 折算，与改动前的 `LruCache.sizeOf` 同一份口径） */
    sizeOfKb: (V) -> Int,
) {

    private val pages = Partition(pageBudgetKb, sizeOfKb)
    private val covers = Partition(coverBudgetKb, sizeOfKb)

    /** 页面位图命中（[com.cc3301.comicviewer.ui.PageDecoder.decodePage] 那把键） */
    fun page(key: String): V? = pages.get(key)

    /** 收下一张页面位图（越界淘汰本分区最旧的，不动封面分区） */
    fun putPage(key: String, value: V) = pages.put(key, value)

    /** 封面位图命中（[CoverDecode.key] 那把键） */
    fun cover(key: String): V? = covers.get(key)

    /** 收下一张封面位图（越界淘汰本分区最旧的，不动页面分区） */
    fun putCover(key: String, value: V) = covers.put(key, value)

    /**
     * 一份按字节预算的 LRU 分区。`LinkedHashMap(accessOrder = true)` 的迭代顺序即「最旧 → 最新」，
     * 因此淘汰就是从迭代器头部删。
     */
    private class Partition<V>(private val budgetKb: Int, private val sizeOfKb: (V) -> Int) {

        private val entries = LinkedHashMap<String, V>(16, 0.75f, true)
        private var usedKb = 0

        @Synchronized
        fun get(key: String): V? = entries[key]

        @Synchronized
        fun put(key: String, value: V) {
            entries.remove(key)?.let { usedKb -= sizeOfKb(it) }
            entries[key] = value
            usedKb += sizeOfKb(value)
            // 单张就超预算时把它**留下**（否则它永远进不了缓存、每次都要重解）；其余按最旧淘汰到预算内。
            // 这一点与 `android.util.LruCache` 不同（它连最后一张也淘汰），是本票有意选的：
            // 留下的那张在下一次写入时自然成为最旧的而被淘汰，不会把预算撑大。
            while (usedKb > budgetKb && entries.size > 1) {
                val iterator = entries.entries.iterator()
                if (!iterator.hasNext()) break
                val eldest = iterator.next()
                iterator.remove()
                usedKb -= sizeOfKb(eldest.value)
            }
            usedKb = usedKb.coerceAtLeast(0)
        }
    }
}
