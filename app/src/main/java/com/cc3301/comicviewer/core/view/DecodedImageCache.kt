package com.cc3301.comicviewer.core.view

/**
 * 解码位图的内存缓存（由 [DecodedImageCacheTest] 锁定）：**页面位图与封面位图各占一份预算**。
 *
 * 为什么必须分区（盖不住时的后果：书的封面变成灰块、要等好一会才出来）：
 * 改动前两者共用**一个** `LruCache`，容量按堆内存折算（`maxMemory/8`）。阅读器解的是整窗宽的页面位图
 * （条漫一页 RGB_565 就是几十 MB），一次阅读就足以把缓存写满——LRU 于是把「几分钟前看过的封面」整批淘汰。
 * 返回书柜时封面必须重新取字节、重新解码（SMB/WebDAV 上就是「等好一会」），骨架于是又出现一次。
 *
 * 分区之后，**页面位图怎么写都动不到封面分区**（反之亦然）：阅读会话不再吃掉封面，跨阅读会话保留。
 * 两份额度各自越界时按**最近最少使用**淘汰（同 `LruCache` 的语义：`get` 命中即刷新使用顺序）。
 *
 * 预算内淘汰挡不住「屏上那几张恰好是最旧的」：封面分区装不下一屏时，在屏上退到视口上方的那几行就会被挤掉，
 * 滚回来要重解（设备现象：往回滚时封面闪一下）。因此封面分区另有
 * [pinCover]/[unpinCover]：屏上正在用的那几张**淘汰时跳过**，它们自己就超预算时允许暂时超
 * （上界 = 屏上可见封面的数量 + 1，另一个是刚写入的那张）。只给可见行钉，预取窗口不钉（整窗免淘汰 = 预算失效）。
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

    /**
     * 钉住一张封面位图：淘汰**跳过它**（屏上正在用的那张不许被挤掉）。
     * 每 [pinCover] 一次要对应一次 [unpinCover]（引用计数，同一张被多处钉住时只在最后一次解锁）。
     * 键**还没入缓存也可以钉**（解码中就该钉住，否则刚解好就可能被下一次写入挤掉）。
     */
    fun pinCover(key: String) = covers.pin(key)

    /** 解锁一张封面位图（落回最旧淘汰的候选里）；没钉过的键不报错 */
    fun unpinCover(key: String) = covers.unpin(key)

    /** 收下一张封面位图（越界淘汰本分区最旧的，不动页面分区） */
    fun putCover(key: String, value: V) = covers.put(key, value)

    /**
     * 一份按字节预算的 LRU 分区。`LinkedHashMap(accessOrder = true)` 的迭代顺序即「最旧 → 最新」，
     * 因此淘汰就是从迭代器头部删。
     */
    private class Partition<V>(private val budgetKb: Int, private val sizeOfKb: (V) -> Int) {

        private val entries = LinkedHashMap<String, V>(16, 0.75f, true)
        private var usedKb = 0

        /** 屏上正在用的键 → 引用计数（减到 0 就移除）：淘汰跳过它们 */
        private val pinned = HashMap<String, Int>()

        @Synchronized
        fun get(key: String): V? = entries[key]

        @Synchronized
        fun pin(key: String) {
            pinned[key] = (pinned[key] ?: 0) + 1
        }

        @Synchronized
        fun unpin(key: String) {
            val remaining = (pinned[key] ?: 1) - 1
            if (remaining > 0) pinned[key] = remaining else pinned.remove(key)
        }

        @Synchronized
        fun put(key: String, value: V) {
            entries.remove(key)?.let { usedKb -= sizeOfKb(it) }
            entries[key] = value
            usedKb += sizeOfKb(value)
            evictToBudget(keepKey = key)
        }

        /**
         * 淘汰到预算内：从最旧（迭代器头部）开始，**跳过已 pin 的与这次刚写入的那张**；只剩一张时停手。
         * 调用方 [put] 已持本分区的锁（本方法自身不再取锁）。
         *
         * 刚写入的那张不淘汰：它在访问序里就是最新的，淘汰它等于把这次刚解好的位图当场丢掉（白解一次）。
         * 单张就超预算时同理把它**留下**（否则它永远进不了缓存、每次都要重解）——这一点与
         * `android.util.LruCache` 不同（它连最后一张也淘汰），是有意选的：留下的那张会在下一次写入时
         * 自然成为最旧的而被淘汰。屏上被钉住的那几张一律不动，它们自己就超预算时暂时超
         * （上界 = 屏上可见封面的数量 + 1）。
         */
        private fun evictToBudget(keepKey: String) {
            if (usedKb <= budgetKb) return
            val iterator = entries.entries.iterator()
            while (usedKb > budgetKb && entries.size > 1 && iterator.hasNext()) {
                val eldest = iterator.next()
                if (eldest.key == keepKey || pinned.containsKey(eldest.key)) continue
                iterator.remove()
                usedKb -= sizeOfKb(eldest.value)
            }
            usedKb = usedKb.coerceAtLeast(0)
        }
    }
}
