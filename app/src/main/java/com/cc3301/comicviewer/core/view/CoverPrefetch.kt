package com.cc3301.comicviewer.core.view

/**
 * 封面预取窗口（票 #108 E2-B，纯函数，由 [CoverPrefetchTest] 锁定）。
 *
 * 口径（维护者：「封面有时立刻就有、有时从无到有慢慢加载出来」）：**可见区 ±1 屏**内的封面要提前开始加载——
 * 滚动到之前字节已经在手，滚到那一行时只剩解码。一屏的规模取**当前可见条目数**（不按像素估算条目高度：
 * 列表/网格两档的条目高度不同，按可见条数换算天然贴合当前档位，也不引入 dp→条目的猜测常量）。
 *
 * 窗口是**闭区间**且夹在列表两端（首尾屏不会越界）。返回 null 表示不预取：
 * 空列表、非法区间（`lastVisible < firstVisible`，布局尚未就绪）、或可见区已越过列表末尾。
 *
 * 并发上限 [MAX_CONCURRENT_LOADS] 属同一口径的另一半：预取是「提前要字节」，快速滑动会一屏一屏地
 * 撞出新窗口（每屏最多 3 屏的条目量），不设上限就会同时向来源发一长串请求、把内存与带宽拉爆。
 * 接线见 `BrowserScreen` 的预取 effect（按窗口分批、每批 [MAX_CONCURRENT_LOADS] 张）。
 */
internal object CoverPrefetch {

    /** 一次最多同时向来源要几张封面（预取的并发上界） */
    const val MAX_CONCURRENT_LOADS: Int = 4

    /**
     * 预取区间：可见条目 `[firstVisible]..[lastVisible]` 上下各扩一屏（一屏 = 当前可见条目数）。
     * 空列表 / 非法区间返回 null。
     */
    fun window(firstVisible: Int, lastVisible: Int, total: Int): IntRange? {
        if (total <= 0) return null
        if (firstVisible < 0 || lastVisible < firstVisible || firstVisible >= total) return null
        val screenItems = lastVisible - firstVisible + 1
        val from = (firstVisible - screenItems).coerceAtLeast(0)
        val to = (lastVisible + screenItems).coerceAtMost(total - 1)
        return from..to
    }
}
