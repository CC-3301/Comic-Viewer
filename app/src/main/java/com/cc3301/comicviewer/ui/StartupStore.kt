package com.cc3301.comicviewer.ui

import android.content.Context
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.StartupState
import com.cc3301.comicviewer.core.source.SortMode

/**
 * 启动页面的「上次状态」持久化（票 20，spec 故事 47/48）。
 *
 * 启动页面设置本身在 [AppSettings.startupPage]；这里存的是判定「去哪一页」所需的跨进程状态：
 * 上次阅读的位置、上次停留的位置（目录层级 + 排序方式）、上次退出时是否正在看书。
 * 全部落 SharedPreferences——判定发生在导航建立之前，必须是可同步读到的进程外状态。
 */
object StartupStore {

    private val prefs: android.content.SharedPreferences
        get() = ServiceLocator.context.getSharedPreferences("startup", Context.MODE_PRIVATE)

    /** 上次启动判定所需的完整状态（每次现读，不缓存） */
    fun state(): StartupState = StartupState(
        lastRead = lastRead(),
        lastBrowsing = lastBrowsing(),
        wasReading = prefs.getBoolean(KEY_WAS_READING, false),
    )

    fun lastRead(): LastRead? {
        val p = prefs
        if (!p.contains(KEY_READ_CONN)) return null
        return LastRead(p.getLong(KEY_READ_CONN, 0L), p.getString(KEY_READ_BOOK, null) ?: return null)
    }

    fun lastBrowsing(): LastBrowsing? {
        val p = prefs
        if (!p.contains(KEY_BROWSING_CONN)) return null
        return LastBrowsing(
            connId = p.getLong(KEY_BROWSING_CONN, 0L),
            containerId = p.getString(KEY_BROWSING_CONTAINER, null),
            sortMode = SortMode.entries.firstOrNull { it.name == p.getString(KEY_BROWSING_SORT, null) }
                ?: SortMode.NAME,
        )
    }

    /** 记录上次停留的位置（浏览界面显示或切换排序时调用） */
    fun recordBrowsing(location: LastBrowsing) {
        prefs.edit()
            .putLong(KEY_BROWSING_CONN, location.connId)
            .putString(KEY_BROWSING_CONTAINER, location.containerId)
            .putString(KEY_BROWSING_SORT, location.sortMode.name)
            .apply()
    }

    /** 记录最近阅读的书（打开书 / 阅读器内换书 / 清空时调用） */
    fun recordLastRead(lastRead: LastRead?) {
        val edit = prefs.edit()
        if (lastRead == null) {
            edit.remove(KEY_READ_CONN).remove(KEY_READ_BOOK)
        } else {
            edit.putLong(KEY_READ_CONN, lastRead.connId).putString(KEY_READ_BOOK, lastRead.bookId)
        }
        edit.apply()
    }

    /** 记录当前是否停在阅读器（spec 故事 47：下次启动的退化条件） */
    fun recordReading(reading: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_READING, reading).apply()
    }

    private const val KEY_READ_CONN = "last_read_conn"
    private const val KEY_READ_BOOK = "last_read_book"
    private const val KEY_BROWSING_CONN = "last_browsing_conn"
    private const val KEY_BROWSING_CONTAINER = "last_browsing_container"
    private const val KEY_BROWSING_SORT = "last_browsing_sort"
    private const val KEY_WAS_READING = "was_reading"
}
