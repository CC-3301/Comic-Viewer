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

    /** 「本次进程已经用掉过那份启动恢复」的标记：见 [consumeOnceForStartup] */
    private var consumed = false

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
     * **进程内只用一次**的启动恢复（第 2/3 条）：落在这一层时返回上次记下的项索引并**清掉**那份记录
     *（用掉即清）；层不符 / 已经用掉 / 没有记录 ⇒ 返回 null（调用方据此回顶部）。
     *
     * **层命中才消耗**（评审 spec-r2 P2）：记录指向的层就是上一次停留的那一层，也只有它才是本次
     * 「启动落地的那一层」；层不符的调用（不是落地层）**原样留着记录、也不返回位置** ⇒ 那一层回顶部。
     * 早先的「第一次调用即消耗」写反了因果——启动落点是「书柜 / 首页 / 设置」时，链里更下面的浏览层
     * **不当帧组合**（见 `AppNav` 的落地顺序），进程内第一次浏览页组合可能根本不是落地层，记录会被白白丢掉。
     *
     * 「只用一次」由 [consumed] 与「命中即 [clear]」两道一起保证：命中清掉之后同进程内再调也读不到。
     */
    fun consumeOnceForStartup(connId: Long, containerId: String?): Int? {
        if (consumed) return null
        val p = prefs
        if (!p.contains(KEY_CONN)) return null
        val stored = DiskScrollLayer(p.getLong(KEY_CONN, 0L), p.getString(KEY_CONTAINER, ROOT_CONTAINER) ?: ROOT_CONTAINER)
        if (stored != layerOf(connId, containerId)) return null
        consumed = true
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
        consumed = false
    }

    /**
     * 「离屏 / 切后台」两个写点共用的落盘（评审 standards-r2 P1：两条路必须同源）：
     * 先经 [BrowseScrollIndexStore.record] 过**丢态判据**（票面「系统夹索引不写」），再落它过滤后的**生效值**
     *（[BrowseScrollIndexStore.valueFor]）——直接落裸读数会整条绕开那条判据。
     */
    fun recordEffectivePosition(key: BrowseScrollRecordKey, rawIndex: Int, connId: Long, containerId: String?) {
        BrowseScrollIndexStore.record(key, rawIndex)
        record(connId, containerId, BrowseScrollIndexStore.valueFor(key))
    }

    /**
     * 一层（连接 + 容器）：与 `BrowseScrollRestore` 里 `BrowseScrollRecordKey.layer` 的写法同形
     *（那份是文件私有，本文件按同一形状自持一份），层判定因此是**一次值比较**、不再手写逐字段比较。
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
