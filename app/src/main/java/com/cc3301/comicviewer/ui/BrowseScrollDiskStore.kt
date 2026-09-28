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
     * 记下「这一层 + 这一刻的项索引」（写点见 `BrowserScreen`：进屏与离场各写一次）。
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
     * 为什么「一次」是进程级的：重启恢复只属于**本次落地的那一层**——落定之后不该再有别的层吃到它
     *（第 3 条）。本方法**第一次被调用即视为「落地已定」**：命中就给出位置，不命中（落点不是这一层 /
     * 落点压根不是浏览层）也照样消耗掉 ⇒ 之后跳到任何文件夹都回顶部。
     */
    fun consumeOnceForStartup(connId: Long, containerId: String?): Int? {
        if (consumed) return null
        consumed = true
        val p = prefs
        if (!p.contains(KEY_CONN)) return null
        if (p.getLong(KEY_CONN, 0L) != connId) return null
        if ((p.getString(KEY_CONTAINER, ROOT_CONTAINER) ?: ROOT_CONTAINER) != (containerId ?: ROOT_CONTAINER)) return null
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

    /** 根层（容器 id 为 null）的落盘写法：SharedPreferences 存不了 null，空串即根层 */
    private const val ROOT_CONTAINER = ""
    private const val PREFS_NAME = "browse_scroll"
    private const val KEY_CONN = "scroll_conn"
    private const val KEY_CONTAINER = "scroll_container"
    private const val KEY_INDEX = "scroll_index"
}
