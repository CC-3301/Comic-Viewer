package com.cc3301.comicviewer.ui

import android.content.Context

/**
 * 「离开 App 时所处的那一层 + 该层的位置」的**一次性落盘**（票 #142 现行口径第 2/3 条）。
 *
 * 为什么单独一份、且只存一条：浏览页的位置记录（[BrowseScrollIndexStore]）活在内存里，进程重启即空，
 * 重启因此回顶部。现行口径第 2 条要求「重启落在上次那一层时停在上次的位置」；第 3 条要求
 * 「重启后在文件夹之间跳转回顶部」——后者的实现方式就是**只存这一条**并「用掉即清」：重启后
 * 只有落地那一层吃得到它，跳去别的文件夹读不到即回顶部。
 *
 * 硬约束（票面）：这里**不记目录层级**（层级仍由 [StartupStore] 的两份记录负责）、**不进**
 * [com.cc3301.comicviewer.core.nav.BrowseLocation]（它保持只记目录层级）、排序也不进
 *（全局一份在 [SortSettingStore]）。
 *
 * 与 [StartupStore] 同款：落 SharedPreferences、每次现读不缓存——重启的读要同步完成。
 */
internal object BrowseScrollDiskStore {

    private val prefs: android.content.SharedPreferences
        get() = ServiceLocator.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 「启动落地已经收口过」的标记：见 [consumeAtStartupLanding]（进程内只收口一次） */
    private var landed = false

    /**
     * 启动链交回的**已定落地层**：浏览层由 [markLanding] 写、非浏览层（顶层路由 / 阅读器）由
     * [markLandingNonBrowserLayer] 写成 null。与 [landingDecided] 合起来是一个**三态**：**还没交回**
     *（`landingDecided == false`）/ **已定：某个浏览层** / **已定：非浏览层**（两者皆为真且本字段为 null）。
     * 少了 `landingDecided` 这一位，「还没交回」就会被读成「**一定不是**」——第一次消费即静默销毁记录（票 #142 b14 堵的窗口）。
     */
    private var landingLayer: DiskScrollLayer? = null

    /** 「落地层**已定**」（两条交回口都置真）：把 `landingLayer == null` 收窄成「本次落地不是浏览层」这一种含义 */
    private var landingDecided = false

    /**
     * 启动链在**导航前**把「本次已定的落地层」交给本 store（票 #142 b13）。
     *
     * 为什么由启动链交、而不是本 store 自己再判一次（原写法自己调 [StartupStore.startupTarget]）：
     * `startupTarget()` 是**落盘的那条判定**，而真正落地的层会被启动链的**退化**改写——默认设置
     * 「上次阅读的位置」下，上次那本书已不是书（`isNotABook`，票 #97 升级路径）时 [resolveStartupRead]
     * 返回 `StartupTarget.OpenBrowser(lastBrowsing)`，连接来源拿不到时同样回落到 `OpenBrowser(lastBrowsing)`。
     * 这些退化支的落地层**正是记录那一层**，而自己重推会判成「非落地层」⇒ 当场 [clear]：位置丢掉、
     * 记录被销毁（本次启动内再也读不回）。改由启动链（`AppNav` 的启动落地导航）交接后，二次推导消失，
     * 两条口径不可能再分叉。
     *
     * 只在这一层是浏览层时调用；非浏览层（顶层路由 / 阅读器）走 [markLandingNonBrowserLayer]——
     * 两者都算「已定」，只是交回的层不同。
     */
    fun markLanding(connId: Long, containerId: String?) {
        landingLayer = layerOf(connId, containerId)
        landingDecided = true
    }

    /**
     * 启动链把「本次落地层是**非浏览层**」交给本 store（顶层路由 首页/书柜/设置、阅读器，以及启动落地的兜底支）。
     *
     * 调用形态有两种（票 #142 b15）：**先交一个默认值**——`AppNav` 的启动落地在 `catchingNonCancellation` 块首
     * 就按非浏览层交回一句（`AppNav.kt:1743`，那时还不知道本次会不会落到浏览层），落到浏览层的那一支随后用
     * [markLanding] 覆盖它；以及**已定**的非浏览落点（早退支 else、`onFailure` 兜底支）。
     * 所以本方法不代表调用者已断言「本次落地层不是浏览层」，它交回的是「**已定**」这一位——[landingDecided]
     * 置真，之后 [markLanding] 仍可改落地层。
     *
     * 为什么要专门交回这一句：`null` 的落地层在三态里有两种含义（「已定：不是浏览层」与「还没交回」），
     * 只有这一句能把它钉成前者——维护者拍板 B 的「**已定的**非落地层 ⇒ 当场丢弃那条记录」因此照旧；
     * 不调用它时那种 `null` 表示「还没交回」：[consumeAtStartupLanding] 既不消费也不销毁记录（票 #142 b14）。
     * 缺了它，顶层落点 / 阅读器落地这两条路会永远停在「还没交回」，拍板 B 在那两条路上静默失效。
     */
    fun markLandingNonBrowserLayer() {
        landingLayer = null
        landingDecided = true
    }

    /**
     * 记下「这一层 + 这一刻的项索引」。写点见 `BrowserScreen`，**三个**：
     * ① **进屏**（首屏 effect 内，写本次要恢复到的位置）；
     * ② **离屏**（`onDispose`）与 ③ **切后台**（生命周期 `ON_STOP`）——这两处走 [recordEffectivePosition]，
     * 先过 [BrowseScrollIndexStore] 的丢态判据再落生效值。
     * **只保留最后一条**——本记录问的是「此刻所处的那一层」，后来的写覆盖先前的。
     */
    fun record(connId: Long, containerId: String?, index: Int) {
        prefs.edit()
            .putLong(KEY_CONN, connId)
            .putString(KEY_CONTAINER, containerId ?: ROOT_CONTAINER)
            .putInt(KEY_INDEX, index)
            .apply()
    }

    /**
     * **启动落地已定**那一刻的启动恢复（维护者 2026-09-28 拍板 **B**）：这一层正是记录指向的层
     *（= 本次落地的那一层）时返回上次记下的项索引并**清掉**记录；否则**当场丢弃**那条记录并返回 null。
     * 落地层**还没交回**时不适用这两条——见下面第三段。
     *
     * 为什么必须是「落地已定」而不是「进程内第一次组合浏览页」（评审 spec-r2 P2）：落点是顶层路由
     *（首页 / 书柜 / 设置）时，链里更下面的浏览层**不当帧组合**（见 `AppNav` 的落地顺序），进程内第一次
     * 浏览页组合可能根本不是落地层——那时把记录当成「已用掉」会白白丢掉真正落地那一层的位置。
     * 落地层因此由**启动链在导航前交回**（[markLanding] / [markLandingNonBrowserLayer]），本 store 不再自己按启动
     * 判定二次推导（这样启动链的退化支——票 #97 不是书 / 连接来源拿不到——落地的那一层也算数，见 [markLanding]）。
     *
     * 为什么「已定的非落地层」要当场丢弃（拍板 B）：票面第 3 条括注就是「重启后只有落地那一层有记录」——
     * 不丢的话，用户随后走进记录那一层还会命中、把重启前的位置恢复回来。
     *
     * **还没交回**（[landingDecided] 假）那一态两者都不做（票 #142 b14）：这时判不出「这一层是不是落地层」，
     * 所以**既不消费也不 [clear]**（否则将来再漏一个调用点就又静默销毁一次记录）；只把「问的这一层正是记录那一层」
     * 那一种情形照旧给值——系统还原回退栈那条路上，还原出的浏览层**当帧就是栈顶**、它的组合早于启动 effect 的交回，
     * 而那条路的落地层就是记录那一层（交回随后就到，见 [markLanding]）。收口（用掉 / 丢弃）留到交回之后。
     *
     * 进程内只收口一次（[landed]）：收口之后本方法退化为「按层取回那条记录」（记录已被用掉 / 丢弃时即 null）。
     */
    fun consumeAtStartupLanding(connId: Long, containerId: String?): Int? {
        val p = prefs
        if (!p.contains(KEY_CONN)) return null
        val layer = layerOf(connId, containerId)
        val stored = DiskScrollLayer(p.getLong(KEY_CONN, 0L), p.getString(KEY_CONTAINER, ROOT_CONTAINER) ?: ROOT_CONTAINER)
        if (!landed && !landingDecided) return if (stored == layer) p.getInt(KEY_INDEX, 0) else null
        if (!landed) {
            landed = true
            // 落地层由启动链交回（[markLanding] / [markLandingNonBrowserLayer]）：不是这一层
            //（含 [landingDecided] 为真、[landingLayer] 为 null = 本次落地不是浏览层）⇒ 当场丢弃（拍板 B），
            // 之后走进记录那一层也是顶部
            if (landingLayer != layer) {
                clear()
                return null
            }
        }
        if (stored != layer) return null
        val index = p.getInt(KEY_INDEX, 0)
        clear()
        return index
    }

    private fun clear() {
        prefs.edit().remove(KEY_CONN).remove(KEY_CONTAINER).remove(KEY_INDEX).apply()
    }

    /** 单测用：对象是进程级单例、且带一个进程级标记，用例之间要互不串味 */
    internal fun clearForTest() {
        clear()
        landed = false
        landingLayer = null
        landingDecided = false
    }

    /**
     * 「离屏 / 切后台」两个写点共用的落盘（评审 standards-r2 P1：两条路必须同源）：
     * 先经 [BrowseScrollIndexStore.record] 过**丢态判据**（票面「系统夹索引不写」），再落它过滤后的**生效值**
     *（[BrowseScrollIndexStore.valueFor]）——直接落裸读数会整条绕开那条判据。
     * 判据里那份**进屏基准在拒写时不消费**（评审 spec-r3-b3 P2-1，见 [BrowseScrollIndexStore.record]）：因此同屏的
     * 两个写点（`onDispose` / `ON_STOP`）先后读到同一份丢态残留时**两个都会被拒**——基准被前一次消费掉时，
     * 第二次调用没有东西可比、必然把被夹小的读数放行到内存记录与磁盘。
     */
    fun recordEffectivePosition(key: BrowseScrollRecordKey, rawIndex: Int, connId: Long, containerId: String?) {
        BrowseScrollIndexStore.record(key, rawIndex)
        record(connId, containerId, BrowseScrollIndexStore.valueFor(key))
    }

    /**
     * 一层（连接 + 容器）：与 `BrowseScrollRestore` 里 `BrowseScrollRecordKey.layer` 的写法同形
     *（那份是文件私有，本文件按同一形状自持一份），层判定因此是**一次值比较**、不再手写逐字段比较
     *（两个用点——[consumeAtStartupLanding] 的落地层判定与它下面的盘侧判定——都过 [layerOf]，评审 standards-r3-b3 P2-1）。
     */
    private data class DiskScrollLayer(val connId: Long, val containerId: String)

    /** 由连接 + 容器（根层为 null）得到层键 */
    private fun layerOf(connId: Long, containerId: String?): DiskScrollLayer =
        DiskScrollLayer(connId, containerId ?: ROOT_CONTAINER)

    /** 根层（容器 id 为 null）的落盘写法：SharedPreferences 存不了 null，空串即根层 */
    private const val ROOT_CONTAINER = ""
    private const val PREFS_NAME = "browse_scroll"
    private const val KEY_CONN = "scroll_conn"
    private const val KEY_CONTAINER = "scroll_container"
    private const val KEY_INDEX = "scroll_index"
}
