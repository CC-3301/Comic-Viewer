package com.cc3301.comicviewer.core.source

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 封面字节的会话级缓存（票 #51 F2 的 `DocumentTreeSource` 口径 + 票 #108 r2 的 `KomgaSource` 口径，
 * 两源共用一份实现：**同一件事只有一个拼法**）。
 *
 * 为什么需要它（票 #108 E2-B）：界面按可见区 ±1 屏**预取**封面字节，预取的意义全在「拿到的那份会被
 * 可见行复用」。没有本缓存的来源（Komga 在 r1 就是这样）会把预取结果直接丢掉——用户滚动到那一行时
 * 仍要再拉一次，净效果是封面请求翻倍、而「滚动到之前已开始加载」一次都没发生。
 *
 * 上界 = 条目数 ∩ 总字节数，任一越界就**从最旧（插入序）的条目开始淘汰**，直到回到界内。
 * 不是「越界就整仓清空」：快速滑动会把一屏一屏的封面都拉进来，整仓清空会连**屏上正显示**的那几张一起丢，
 * 它们当场重新拉一次——真机上就是「封面从无到有慢慢加载出来」那一态（票 #108 E2-B 的原始现象）。
 *
 * 淘汰序是插入序而不是访问序：屏上的封面都是刚插进来的那一批，插入序淘汰能保住它们；
 * 访问序要额外维护链表，而命中路径（每次重组读一遍屏上的行）会把它变成高频写热点。
 *
 * 并发：读写各自线程安全（`ConcurrentHashMap` + 原子计数）。上界是**尽力而为**——两个线程同时写入时
 * 可能短暂多出一两条再被下一次写入淘汰掉；缓存是「省一次往返」的优化，不需要严格不变量。
 */
internal class CoverByteCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val entries = ConcurrentHashMap<String, ByteArray>()

    /** 插入序（淘汰时从队首取最旧） */
    private val insertionOrder = ConcurrentLinkedQueue<String>()

    /** 当前占用的字节数（淘汰判据之一） */
    private val totalBytes = AtomicLong(0L)

    /** 命中返回字节；未命中返回 null（缓存里**不存**失败/空结果，失败下一次仍可重试） */
    fun get(key: String): ByteArray? = entries[key]

    /**
     * 写入并按插入序淘汰到界内（同一键重写只记字节差值，不重复进队）。
     * 单条字节数大于 [maxBytes] 时会被下一次淘汰取走（不特殊处理：它本来就装不下）。
     */
    fun put(key: String, bytes: ByteArray) {
        val previous = entries.put(key, bytes)
        if (previous != null) {
            totalBytes.addAndGet((bytes.size - previous.size).toLong())
        } else {
            insertionOrder.add(key)
            totalBytes.addAndGet(bytes.size.toLong())
        }
        while (entries.size > maxEntries || totalBytes.get() > maxBytes) {
            val oldest = insertionOrder.poll() ?: return
            entries.remove(oldest)?.let { totalBytes.addAndGet(-it.size.toLong()) }
        }
    }

    /** 整体清空：手动刷新（下拉更新）与会话释放都走这里 */
    fun clear() {
        entries.clear()
        insertionOrder.clear()
        totalBytes.set(0L)
    }

    companion object {
        /** 条目数上界（与 `DocumentTreeSource` 的既有值一致：一次会话浏览到的封面数量级） */
        const val DEFAULT_MAX_ENTRIES: Int = 64

        /** 总字节上界：封面是一整张原始图片字节，条目数上界单独用会吃掉过多内存 */
        const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024
    }
}
