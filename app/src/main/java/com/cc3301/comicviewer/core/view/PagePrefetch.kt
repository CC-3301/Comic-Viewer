package com.cc3301.comicviewer.core.view

/**
 * 条漫滚动预取（纯函数与纯内存记帐，由 [PagePrefetchTest] 锁定）。
 *
 * 预取窗随当前页滑动：当前页之后 [PagePrefetch.pages] 的 after 页、之前 before 页。
 * 滚到下一页时窗口只多出末尾一页，其余页已经在手。当前页自己不在窗里——它由可见页那条路取解，
 * 优先级更高（见 [CoverBytePriority]）。
 */
internal object PagePrefetch {

    /**
     * 这一屏要预取的页：先后面 after 页（滚动方向），再前面 before 页；夹在 `0..pageCount-1`。
     * 空书或当前页越界时返回空表。
     */
    fun pages(currentPage: Int, pageCount: Int, after: Int, before: Int): List<Int> {
        if (pageCount <= 0 || currentPage < 0 || currentPage >= pageCount) return emptyList()
        val forward = ((currentPage + 1)..minOf(currentPage + after, pageCount - 1)).toList()
        val backward = ((currentPage - before).coerceAtLeast(0) until currentPage).toList()
        return forward + backward
    }
}

/**
 * 预取的记帐本（纯内存状态）：决定「这一次窗口还要不要把这几页发出去」。
 *
 * 口径与封面预取同一套（窗口一变 → 还没开始的任务直接丢，已经在取的跑完），但**不复用封面那套类**：
 * 页的任务是「取字节 + 解码」，取消单位是一页而不是一个候选方案，且这里没有尝试次数与退避
 * （预取失败不重试，滚到那一页时阅读页自己会取并按页给出失败提示）。
 *
 * 两个职责：
 * 1. **在飞去重**：页在发起任务之前就记进「已领」；结束（成功 / 失败 / 取消）后 [settle] 才释放。
 * 2. **丢弃判据**：窗口一变，[dropPending] 交出「已领但还没开始、又不在新窗口里」的页；
 *    调用方取消这些任务并释放。任务体在动手前调 [start]——被丢掉的页 start 为假，直接返回，
 *    因此不会出现「取消了一个已经开始解码的任务」。
 *
 * 三个线程都会碰它（组合线程领/丢、IO 线程 start/settle），所有入口都加锁。
 */
internal class PagePrefetchLedger {

    /** 已经领了任务、还没结束的页 → 领走它的那一批（[begin] 的 [owner] 令牌） */
    private val claimed = mutableMapOf<Int, Any>()

    /** 其中真正开跑了的页（已经从闸外走进取字节 / 解码） */
    private val started = mutableSetOf<Int>()

    /**
     * 本次窗口要预取的页：不在 [excluded]（在屏页 / 当前页）、没有位图、还没被领过。
     * 返回的页同时被登记为「已领」；[owner] 是这一批任务的令牌——同一页被后来的窗口重新领取时令牌不同，
     * 旧批次迟到的收尾因此不会动新任务的账（见 [settle]）。
     */
    @Synchronized
    fun begin(owner: Any, pages: List<Int>, excluded: Set<Int>, cached: (Int) -> Boolean): List<Int> {
        val targets = mutableListOf<Int>()
        for (page in pages) {
            if (page in excluded) continue
            if (cached(page)) continue
            if (page in claimed) continue
            claimed[page] = owner
            targets.add(page)
        }
        return targets
    }

    /**
     * 这一页的任务可以开跑了吗：返回 false = 它已经被 [dropPending] 丢掉、或已被后来的批次重新领取，
     * 任务体应当直接返回。返回 true 之后这一页就是「已经在跑」，[dropPending] 不再交它出去。
     */
    @Synchronized
    fun start(page: Int, owner: Any): Boolean {
        if (claimed[page] !== owner) return false
        started.add(page)
        return true
    }

    /** 窗口变了：交出「已领、还没开跑、又不在新窗口 [keep] 里」的页（调用方取消它们并 [settle]） */
    @Synchronized
    fun dropPending(keep: Set<Int>): List<Int> {
        val dropped = claimed.keys.filter { it !in started && it !in keep }
        dropped.forEach(claimed::remove)
        return dropped
    }

    /**
     * 一条任务结束（成功 / 失败 / 取消都算）：让它之后还能被重新领取。
     * **只结算仍是这一批领的那一页**：被丢掉的旧任务在 IO 侧收尾时不能把同页新任务的领取提前放掉。
     */
    @Synchronized
    fun settle(page: Int, owner: Any) {
        if (claimed[page] !== owner) return
        claimed.remove(page)
        started.remove(page)
    }
}
