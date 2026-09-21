package com.cc3301.comicviewer.ui

import android.content.Context
import android.net.Uri
import com.cc3301.comicviewer.core.nav.BrowseLocation
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
     * 上次停留的**浏览路径**（栈底 → 当前层，票 #70 r2 AC11）：重启后按它重建整条层级链，
     * 返回因此逐级回到上一级、只有首页再返回才退出 APP（只记当前位置的话，重启后返回只剩「回首页」一条路）。
     *
     * **术语**（票 #70 r2 评审 P2-3）：本处的「浏览路径」指**用户停留的层级链**（浏览页逐层下钻留下的那串位置），
     * 与 `CONTEXT.md` 里连接的 `browsePath`（进连接后从哪一层开始，见
     * [com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig.browsePath]）**同词不同义**——两者只是同名。
     *
     * 落盘时机：**阅读页每层显示时**（[recordBrowsePosition]）与**会话结束**（[ServiceLocator.closeSession]，
     * 即 Activity finish）两处都写，写的是同一个值——真机上更常见的退出是任务被划掉 / 进程被杀，那时没有 finish，
     * 只有逐层写下的这份可用（票 #70 r2 复审）。旋转这类非 finish 的重建不动它（历史随进程存活，回退栈也由系统还原）。
     */
    fun browsingPath(): List<BrowseLocation> {
        val raw = prefs.getString(KEY_BROWSING_PATH, null) ?: return emptyList()
        val lines = raw.split(ENCODING_SEPARATOR)
        val connId = lines.getOrNull(0)?.toLongOrNull() ?: return emptyList()
        val layers = lines.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        // 段数与落盘时记的层数不一致 = 不是本编码写的（旧格式 / 手改库 / 损坏）：整条作废，
        // 让调用方退回「只恢复当前位置」（评审 P2-2：中间层 id 带分隔符时不能把一条错路径当合法）
        if (lines.size - PATH_HEADER_LINES != layers) return emptyList()
        // 每层落盘前做过百分号编码，解码后即原样（含换行/分隔符的 id 也仍是一段）
        return lines.drop(PATH_HEADER_LINES).map { BrowseLocation(connId, Uri.decode(it).ifEmpty { null }) }
    }

    /**
     * 记录浏览路径；空路径即清除记录（连接被删后不再恢复）。
     *
     * 写点有两个，写的都是同一个值：浏览页每层显示时（[recordBrowsePosition]，写侧主路径）与会话结束时
     * （[ServiceLocator.closeSession]，按返回退出那一类），后者与历史**同源同寿命**。
     */
    fun recordBrowsingPath(path: List<BrowseLocation>) {
        val edit = prefs.edit()
        if (path.isEmpty()) {
            edit.remove(KEY_BROWSING_PATH)
        } else {
            edit.putString(KEY_BROWSING_PATH, encodeBrowsingPath(path))
        }
        edit.apply()
    }

    /**
     * 浏览页显示某层时的落盘（票 #70 r2 复审，真机未过的那条）：把「上次停留的位置」与**整条浏览路径**在
     * **同一次调用**里写下去。
     *
     * 为什么不能只靠会话结束（[ServiceLocator.closeSession] 里那次 [recordBrowsingPath]）那次写：
     * [startupBrowsePath] 采用落盘路径的判据是「路径最后一层 = 本次恢复到的位置」，而「上次停留的位置」是
     * 浏览页每次显示都写（[recordBrowsing]）——任务被划掉、进程被杀这类**没有 Activity finish** 的退出之后，
     * 落盘路径还是上一会话的（或空的），启动因此只能恢复一层，返回于是直接跳回首页（维护者真机反馈的现象 A）。
     * 两个键同写同寿命，读侧的判据才成立，启动也不再用陈旧路径。
     *
     * [path] 为空（历史里没有当前层，理论上不该发生：浏览页显示前位置先入历史）时退化为「只有当前这一层」，
     * 保证路径最后一层恒为 [location]——绝不把一条与当前位置无关的旧路径留给启动读。
     */
    fun recordBrowsePosition(location: LastBrowsing, path: List<BrowseLocation>) {
        val layers = path.ifEmpty { listOf(BrowseLocation(location.connId, location.containerId)) }
        prefs.edit()
            .putLong(KEY_BROWSING_CONN, location.connId)
            .putString(KEY_BROWSING_CONTAINER, location.containerId)
            .putString(KEY_BROWSING_PATH, encodeBrowsingPath(layers))
            .apply()
    }

    /**
     * 路径编码：首行连接 id（一条路径只属于一个连接）、次行层数，其余每行一层的**百分号编码**容器 id
     * （空串 = 根层）。分隔符因此**不可能**出现在段内——写前 [Uri.encode]、读后 [Uri.decode]，
     * 容器 id 带换行/`%`/`?` 也仍是一段（评审 P2-2）。
     */
    private fun encodeBrowsingPath(path: List<BrowseLocation>): String =
        (listOf(path.first().connId.toString(), path.size.toString()) + path.map { Uri.encode(it.containerId ?: "") })
            .joinToString(ENCODING_SEPARATOR)

    /**
     * 清掉上次停留的位置（票 26 第 2 项）：该记录指向的连接已被删除时它再也恢复不了，
     * 留着只会让每次启动都重走一遍「导航到浏览页再弹回」的退化路径。
     * **票 #70 r2**：路径与它同属一份「上次停留的位置」，一并清掉。
     */
    fun clearBrowsing() {
        prefs.edit()
            .remove(KEY_BROWSING_CONN)
            .remove(KEY_BROWSING_CONTAINER)
            .remove(KEY_BROWSING_PATH)
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

    /** 上次停留的浏览路径（票 #70 r2）：首行连接 id、次行层数，其余每行一层容器 id 的百分号编码（空串 = 根层） */
    private const val KEY_BROWSING_PATH = "last_browsing_path"
    private const val ENCODING_SEPARATOR = "\n"

    /** [KEY_BROWSING_PATH] 的头两行：连接 id + 层数 */
    private const val PATH_HEADER_LINES = 2
    private const val KEY_WAS_READING = "was_reading"
}
