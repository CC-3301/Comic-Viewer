package com.cc3301.comicviewer.core.view

/**
 * 封面预取窗口（票 #108 E2-B，纯函数，由 [CoverPrefetchTest] 锁定）。
 *
 * 口径（维护者：「封面有时立刻就有、有时从无到有慢慢加载出来」）：**可见区 ±1 屏**内的封面要提前弄好——
 * 滚动到之前**字节与位图都已在手**（票 #108 r6 起预取连解码一起做，见 `ui/CoverPrefetchLoad.kt`），
 * 滚到那一行时可见行直接命中封面分区、不再取字节也不再解码。一屏的规模取**当前可见条目数**（不按像素估算条目高度：
 * 列表/网格两档的条目高度不同，按可见条数换算天然贴合当前档位，也不引入 dp→条目的猜测常量）。
 *
 * 窗口是**闭区间**且夹在列表两端（首尾屏不会越界）。返回 null 表示不预取：
 * 空列表、非法区间（`lastVisible < firstVisible`，布局尚未就绪）、或可见区已越过列表末尾。
 *
 * 并发上限 [MAX_CONCURRENT_LOADS] 属同一口径的另一半：预取是「提前把封面弄到可用」，快速滑动会一屏一屏地
 * 撞出新窗口（每屏最多 3 屏的条目量），不设上限就会同时向来源发一长串请求、把内存与带宽拉爆。
 * 接线见 `BrowserScreen` 的预取 effect（按窗口分批、每批 [MAX_CONCURRENT_LOADS] 张）。
 */
internal object CoverPrefetch {

    /**
     * 这一条此刻是不是**已经有可直接用的封面**（⇒ 不占预取名额，票 #135）：位图已在封面分区里，
     * 或字节已在来源缓存里。两个查询都只读内存、不做 IO；**问的顺序有意义**——[hasBitmap] 先问：
     * 位图在就是「可见行直接能用」（r6 起预取连解码一起做），字节在只是省一次来源往返，
     * 前者命中就不必再问后者。
     *
     * 实现说明（票 #135）：这里收成一处是为了让「位图先于字节」这条顺序可被用例钉住
     * （`CoverPrefetchTest`）——之前它写在 `BrowserScreen` 的预取 effect 里，靠 `||` 短路，改起来静默。
     */
    fun alreadyAvailable(hasBitmap: () -> Boolean, hasCachedBytes: () -> Boolean): Boolean =
        hasBitmap() || hasCachedBytes()

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
 * 预取的记帐本（票 #108 r2 ~ r4，纯内存状态，由 [CoverPrefetchTest] 锁定）：
 * 决定「这一屏还要不要把这几条发给来源」。它有**两个**职责，别的都不是它的：
 *
 * 1. **在飞去重**（r2 评审 P2）：r1 把 id 在**发起请求之前**就记进「已预取」且不区分取消，而外层是
 *    `snapshotFlow{…}.collectLatest`——窗口一变（快速滑动时每帧都在变）就取消上一批在飞的请求，
 *    被取消那批的 id 已记上 ⇒ 这些条目在本会话内永远不会再被预取。现在取消/失败经 [release] 或
 *    [settle] 出队，下次窗口变化仍可重试。
 * 2. **有界退避**（r4）：来源对「真的没有封面」与「这次失败」都给 null，因此不能把 null 当终态（会让瞬断
 *    条目本会话再不被预取），也不能不设上限（r2 之前那样每次窗口变化把整窗重发）。每个 id 每会话最多
 *    [MAX_ATTEMPTS_PER_SESSION] 次，达到就别再发了；下拉更新（重建记帐本）会重置。
 *
 * **「已经有字节了」不进这里**（r4）：那是**来源字节缓存**的状态（[Source.hasCachedCoverBytes]），
 * 由调用方作为 [begin] 的判定逐条问。原因：字节缓存有上界、越界按插入序淘汰最旧，界面侧另记一个
 * 「已预取」集合就与淘汰无联动——长列表滚远再滚回时界面以为已有、缓存里其实已经空了（r2/r3 的 P2）。
 */
internal class CoverPrefetchLedger(
    private val maxAttempts: Int = MAX_ATTEMPTS_PER_SESSION,
) {

    private val inFlight = mutableSetOf<String>()

    /** 每个 id 已试过几次（拿不到就 +1；拿到了就清） */
    private val attempts = mutableMapOf<String, Int>()

    /**
     * 本次窗口要预取的 id：落在 [window] 内、**走字节通路**、来源缓存里**还没有**、不在飞、且尝试次数没到上限。
     * 返回的 id 同时被登记为**在飞**，调用方必须在每条结束（或取消）时 [settle] / [release]。
     *
     * [cached] 由调用方给（只读内存、不做 IO）：r4 起问的是来源字节缓存（[Source.hasCachedCoverBytes]），
     * r6 起预取还解码进封面分区，因此调用方又叠上「位图已解好」那一问（见 `BrowserScreen` 的预取 effect）——
     * 预取的唯一真相始终在缓存里，不在本记帐本里。
     *
     * 实现说明（评审 r3 standards P2-3）：这里**显式遍历**、逐条判定后登记，故意不用
     * `window.map{…}.filter{…}`——登记「在飞」是**动作**，写在谓词里就靠 `&&` 短路与 `filter` 逐元素
     * 按序求值，读的人要心算求值顺序；将来换成 `distinct` / `take` / 并行收集会**静默漏登记**（同一批重发）。
     */
    fun begin(
        window: IntRange,
        candidates: List<CoverPrefetch.Candidate>,
        cached: (CoverPrefetch.Candidate) -> Boolean,
    ): List<String> {
        val targets = mutableListOf<String>()
        for (index in window) {
            val candidate = candidates.getOrNull(index) ?: continue
            if (!candidate.viaSourceBytes) continue
            if (cached(candidate)) continue
            if (candidate.id in inFlight) continue
            if ((attempts[candidate.id] ?: 0) >= maxAttempts) continue
            inFlight.add(candidate.id)
            targets.add(candidate.id)
        }
        return targets
    }

    /**
     * 结算一条：[loaded] = 这次真拿到了字节（计数归零）；没拿到（null / 抛异常）则计一次尝试，仍可重试
     * 直到本会话的 [maxAttempts] 用完。
     *
     * 只有「拿到/没拿到」两分（r4）：**两个 `coverBytes` 实现都把失败吞成 null**（`DocumentTreeSource` /
     * `KomgaSource` 的 KDoc 都写「取不到返回 null」），来源给不出「这个条目永远没有封面」这个信号；
     * r3 把 null 当「永久无封面」的分支因此与来源契约相反（SMB 瞬断会被错记成永久）。
     */
    fun settle(id: String, loaded: Boolean) {
        inFlight.remove(id)
        if (loaded) attempts.remove(id) else attempts[id] = (attempts[id] ?: 0) + 1
    }

    /** 取消/异常路径：把这一批在飞的全部放回（**不计**尝试次数：取消不是来源的答复） */
    fun release(ids: List<String>) {
        inFlight.removeAll(ids)
    }

    companion object {
        /** 每个 id 每会话最多试几次（下拉更新重建记帐本即重置）；3 次足以跨过瞬断，又不至于把无封面条目反复拉 */
        const val MAX_ATTEMPTS_PER_SESSION: Int = 3
    }
}
