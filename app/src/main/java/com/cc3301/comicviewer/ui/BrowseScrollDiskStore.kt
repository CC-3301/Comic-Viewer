package com.cc3301.comicviewer.ui

import android.content.Context
import com.cc3301.comicviewer.core.nav.StartupTarget

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
     *
     * 为什么必须是「落地已定」而不是「进程内第一次组合浏览页」（评审 spec-r2 P2）：落点是顶层路由
     *（首页 / 书柜 / 设置）时，链里更下面的浏览层**不当帧组合**（见 `AppNav` 的落地顺序），进程内第一次
     * 浏览页组合可能根本不是落地层——那时把记录当成「已用掉」会白白丢掉真正落地那一层的位置。
     * 这里因此按**启动判定本身**（[StartupStore.startupTarget]）判「落地层是不是这一层」。
     *
     * 为什么非落地层要当场丢弃（拍板 B）：票面第 3 条括注就是「重启后只有落地那一层有记录」——
     * 不丢的话，用户随后走进记录那一层还会命中、把重启前的位置恢复回来。
     *
     * 进程内只收口一次（[landed]）：收口之后本方法退化为「按层取回那条记录」（记录已被用掉 / 丢弃时即 null）。
     */
    fun consumeAtStartupLanding(connId: Long, containerId: String?): Int? {
        val p = prefs
        if (!p.contains(KEY_CONN)) return null
        if (!landed) {
            landed = true
            val target = StartupStore.startupTarget()
            val landingIsThisLayer = target is StartupTarget.OpenBrowser &&
                layerOf(target.browsing.connId, target.browsing.containerId) == layerOf(connId, containerId)
            if (!landingIsThisLayer) {
                clear() // 非落地层：当场丢弃（拍板 B），之后走进记录那一层也是顶部
                return null
            }
        }
        val stored = DiskScrollLayer(p.getLong(KEY_CONN, 0L), p.getString(KEY_CONTAINER, ROOT_CONTAINER) ?: ROOT_CONTAINER)
        if (stored != layerOf(connId, containerId)) return null
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
