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
     * 一条预取候选（票 #108 r3）：[id] 加上「这条的**可见行会不会调 `Source.coverBytes`**」。
     *
     * [viaSourceBytes] 为 false（本地/SAF 的 `content://`、`file://` 封面）时**不进预取**：可见行走
     * `PageDecoder.decodeCoverUri` 直接解 uri，从不碰 `coverBytes`；而预取那一次对本地图片是**真的读整张图**，
     * 读出来没人复用，还占同一份会话字节缓存的字节帐（上界一满就淘汰最旧的），反过来把真正要用字节的条目
     * 挤出缓存（票 #108 r3 评审 P1）。判据来自 [CoverUriSource]，与渲染侧同一处。
     */
    data class Candidate(val id: String, val viaSourceBytes: Boolean)

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
 * 一条预取的结算结果（票 #108 r3）：把「来源明确说没有封面」与「这次调用失败」分开——
 * 前者反复重试是纯浪费（Komga 容器行的兜底链一次最多 4 个请求，滚一下就会重发整窗），
 * 后者只是这一次没成功，要放回重试。
 */
internal enum class PrefetchOutcome {
    /** 拿到字节 */
    Loaded,

    /** 来源明确返回了 null：这个条目就是没有封面，记下，不再反复重试 */
    Absent,

    /** 调用失败/抛异常（含取消在 `catchingNonCancellation` 里冒泡前的那些）：放回，下次窗口变化仍可重试 */
    Failed,
}

/**
 * 预取的记帐本（票 #108 r2 + r3，纯内存状态，由 [CoverPrefetchTest] 锁定）：决定「这一屏还要不要把这几条发给来源」。
 *
 * 为什么单独一个东西（r2 评审 P2）：r1 把 id 在**发起请求之前**就记进「已预取」，而外层是
 * `snapshotFlow{…}.collectLatest`——窗口一变（快速滑动时每帧都在变）就取消上一批在飞的请求。被取消那批的 id
 * 已经记上了 ⇒ 这些条目在本会话内**永远不会再被预取**（而快滑正是本票要修的场景）。
 *
 * 三态区分：
 * - **已结算**（拿到字节 [PrefetchOutcome.Loaded]，或来源明确说没有 [PrefetchOutcome.Absent]）——不再重取；
 * - **在飞**（[begin] 登记，还没结算）——不重复发；
 * - **失败/被取消**（[PrefetchOutcome.Failed] / [release]）——**放回**，下一次窗口变化时还能被预取。
 *
 * r3 追加：只有「可见行会走字节通路」的候选才进得来（[CoverPrefetch.Candidate.viaSourceBytes]）。
 */
internal class CoverPrefetchLedger {

    private val settled = mutableSetOf<String>()

    private val inFlight = mutableSetOf<String>()

    /**
     * 本次窗口要预取的 id：落在 [window] 内、**走字节通路**、且既不在飞也没结算过的（其余一律过滤掉）。
     * 返回的 id 同时被登记为**在飞**，调用方必须在每条结束（或取消）时 [settle] / [release]。
     */
    fun begin(window: IntRange, candidates: List<CoverPrefetch.Candidate>): List<String> {
        val targets = window.mapNotNull { candidates.getOrNull(it) }
            .filter { it.viaSourceBytes && it.id !in settled && inFlight.add(it.id) }
        return targets.map { it.id }
    }

    /** 结算一条：拿到字节或来源明确说没有 ⇒ 不再重取；调用失败 ⇒ 放回重试 */
    fun settle(id: String, outcome: PrefetchOutcome) {
        inFlight.remove(id)
        if (outcome != PrefetchOutcome.Failed) settled.add(id)
    }

    /** 取消/异常路径：把这一批在飞的全部放回（下一次窗口变化时仍可预取） */
    fun release(ids: List<String>) {
        inFlight.removeAll(ids)
    }
}
