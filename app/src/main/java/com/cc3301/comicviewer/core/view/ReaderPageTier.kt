package com.cc3301.comicviewer.core.view

/**
 * 阅读器页面位图预算与设备档位（纯函数，由 [ReaderPageTierTest] 锁定）。
 *
 * 档位只决定三列：预取窗（后 + 前）、回看上限、页数上限；字节预算不按档位写死，
 * 一律取 [readerPageBitmapBudgetKb]（8G / 6G / 4G 标称容量下分别是 256 / 192 / 128MB；
 * 真实机器报告的内存略小，因此实际预算略低于这几个值）。
 *
 * 页数上限是**工作集**的上界：回看上限 + 预取窗 + 解码在飞的 [PAGE_DECODE_MAX_CONCURRENT] 张。
 * 「最多几项」与字节预算取先到者（见 [DecodedImageCache] 的页面分区）；回看不另立钉页机制，
 * 由页数上限与 LRU 自然给出。
 */
internal data class ReaderPageTier(
    /** 当前页之后预取几页（条漫滚动方向） */
    val prefetchAfter: Int,
    /** 当前页之前预取几页（回看方向的缓冲） */
    val prefetchBefore: Int,
    /** 回看上限：页数上限里留给「已经看过的页」的份额 */
    val lookbackPages: Int,
) {

    /**
     * 页面分区最多几项（与字节预算取先到者）：工作集上界 =
     * 回看上限 + 预取窗 + 解码在飞的 [PAGE_DECODE_MAX_CONCURRENT] 张。
     */
    val pageLimit: Int get() = lookbackPages + prefetchAfter + prefetchBefore + PAGE_DECODE_MAX_CONCURRENT
}

/**
 * 同时最多几张**全宽页**在**解码**（页面解码闸的闸位）：**3**（预览缩略图不过这道闸）。
 *
 * 解码是纯 CPU 活：条漫预取一次备好后面 7 页、前面 2 页，不设闸就会同时解 9 张、把帧管线要的核占满。
 * 可见页插队、预取让位；已经在解的不抢占。取字节那道闸是另一个实例（闸位见 `ui/PageBytes.kt`）。
 */
internal const val PAGE_DECODE_MAX_CONCURRENT: Int = 3

/**
 * 按**真实整机内存**取档位（`ActivityManager.MemoryInfo.totalMem` 那一个读数）：
 * ≥7GiB = 8G 档、≥5GiB = 6G 档、其余 = 4G 档。
 * 标称容量与真实报告值有差（8G 的机器报告 7.6GiB 上下），阈值因此定在标称值以下一档。
 */
internal fun readerPageTierOf(totalMemoryBytes: Long): ReaderPageTier = when {
    totalMemoryBytes >= 7L * GIB -> ReaderPageTier(prefetchAfter = 7, prefetchBefore = 2, lookbackPages = 12)
    totalMemoryBytes >= 5L * GIB -> ReaderPageTier(prefetchAfter = 7, prefetchBefore = 2, lookbackPages = 10)
    else -> ReaderPageTier(prefetchAfter = 4, prefetchBefore = 1, lookbackPages = 8)
}

/**
 * 页面位图分区的字节预算（KB）：`min(整机内存 / 32, 384MB)`。
 *
 * 位图在 API 26+ 属 native 内存，不吃 Java 堆，因此不按 `Runtime.maxMemory()` 算；
 * 真正的风险是整机内存压力（进程被系统回收），所以仍要封顶。
 */
internal fun readerPageBitmapBudgetKb(totalMemoryBytes: Long): Int =
    (totalMemoryBytes / 32 / 1024).toInt().coerceAtMost(384 * 1024)

private const val GIB: Long = 1024 * 1024 * 1024
