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

/**
 * 预取的记帐本（票 #108 r2，纯内存状态，由 [CoverPrefetchTest] 锁定）：决定「这一屏还要不要把这几条发给来源」。
 *
 * 为什么单独一个东西（评审 P2-2）：r1 把 id 在**发起请求之前**就记进「已预取」，而外层是
 * `snapshotFlow{…}.collectLatest`——窗口一变（快速滑动时每帧都在变）就取消上一批在飞的请求。被取消那批的 id
 * 已经记上了 ⇒ 这些条目在本会话内**永远不会再被预取**（而快滑正是本票要修的场景）。
 *
 * 三态区分：
 * - **已结算成功**（[settle] 真拿到了字节）—— 不再重取；
 * - **在飞**（[begin] 登记，还没结算）—— 不重复发；
 * - **被取消/失败**（[release] / `settle(loaded = false)`）—— **放回**，下一次窗口变化时还能被预取。
 */
internal class CoverPrefetchLedger {

    private val settled = mutableSetOf<String>()

    private val inFlight = mutableSetOf<String>()

    /**
     * 本次窗口要预取的 id：落在 [window] 内、且既不在飞也结算成功过的（其余一律过滤掉）。
     * 返回的 id 同时被登记为**在飞**，调用方必须在每条结束（或取消）时 [settle] / [release]。
     */
    fun begin(window: IntRange, ids: List<String>): List<String> {
        val targets = window.mapNotNull { ids.getOrNull(it) }.filter { it !in settled && inFlight.add(it) }
        return targets
    }

    /** 结算一条：真拿到字节则不再重取；没拿到（来源返回 null / 请求失败）放回，下次还能试 */
    fun settle(id: String, loaded: Boolean) {
        inFlight.remove(id)
        if (loaded) settled.add(id)
    }

    /** 取消/异常路径：把这一批在飞的全部放回（下一次窗口变化时仍可预取） */
    fun release(ids: List<String>) {
        inFlight.removeAll(ids)
    }
}
