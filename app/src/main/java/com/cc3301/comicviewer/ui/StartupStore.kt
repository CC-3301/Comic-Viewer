package com.cc3301.comicviewer.ui

import android.content.Context
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.StartupState
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.nav.resolveStartupTarget

/**
 * 启动页面的「上次状态」持久化（票 20，spec 故事 47/48）。
 *
 * 启动页面设置本身在 [AppSettings.startupPage]；这里存的是判定「去哪一页」所需的跨进程状态：
 * 上次阅读的位置、上次停留的位置（目录层级）、上次退出时是否正在看书。
 * 排序方式与方向不在这里：它是全局一份的排序设置（[SortSettingStore]），本来就一直保持（票 #29，故事 48）。
 * 全部落 SharedPreferences——判定必须早于**落地导航**、且是一次可同步完成的读（票 #26：判定本身在中转页的启动 effect 内完成，见 ADR-0001）。
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

    /**
     * 启动落地判定的「快照入口」（票 26 r2 修正 1）：**在一次同步调用里**读完判定所需的全部落盘状态
     * （启动页面设置 + [state]）并当场判定，给调用点一份不会随后变动的结果。
     *
     * 调用点必须把这一步放在**任何挂起点之前**（AppNav 的启动 effect 第一句）：本会话随后会写
     * `was_reading`（路由一变即落盘），跨线程的「IO 线程读 / 主线程写」没有任何先后保证，
     * 只有「本会话的读先于本会话的一切写」才稳定——否则在阅读器里退出后的冷启动可能读成 false，
     * 故事 47「直接打开上次那本书」的语义就丢了。
     *
     * 放在这里而不是 `core/nav`：[StartupTarget] 的判定输入同时来自 ui 层的设置（[AppSettings.startupPage]）
     * 与状态（[state]），而 core 不依赖 ui。
     */
    fun startupTarget(): StartupTarget = resolveStartupTarget(AppSettings.startupPage, state())

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
        )
    }

    /** 记录上次停留的位置（浏览界面显示时调用；柜页不是浏览位置，不写这里） */
    fun recordBrowsing(location: LastBrowsing) {
        prefs.edit()
            .putLong(KEY_BROWSING_CONN, location.connId)
            .putString(KEY_BROWSING_CONTAINER, location.containerId)
            .apply()
    }

    /**
     * 清掉上次停留的位置（票 26 第 2 项）：该记录指向的连接已被删除时它再也恢复不了，
     * 留着只会让每次启动都重走一遍「导航到浏览页再弹回」的退化路径。
     */
    fun clearBrowsing() {
        prefs.edit().remove(KEY_BROWSING_CONN).remove(KEY_BROWSING_CONTAINER).apply()
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
    private const val KEY_WAS_READING = "was_reading"
}
