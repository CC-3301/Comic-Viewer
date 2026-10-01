package com.cc3301.comicviewer.ui

import android.content.Context
import android.net.Uri
import com.cc3301.comicviewer.core.nav.BrowseLocation
import com.cc3301.comicviewer.core.nav.LastBrowsing
import com.cc3301.comicviewer.core.nav.LastRead
import com.cc3301.comicviewer.core.nav.LastTopLevel
import com.cc3301.comicviewer.core.nav.StartupState
import com.cc3301.comicviewer.core.nav.StartupTarget
import com.cc3301.comicviewer.core.nav.resolveStartupTarget

/**
 * 启动页面的「上次状态」持久化（spec 故事 47/48）。
 *
 * 启动页面设置本身在 [AppSettings.startupPage]；这里存的是判定「去哪一页」所需的跨进程状态：
 * 上次阅读的位置、上次停留的位置（目录层级）、上次退出时是否正在看书、顶层落点（首页/书柜/设置）。
 * 排序方式与方向不在这里：它是全局一份的排序设置（[SortSettingStore]），本来就一直保持（故事 48）。
 * 全部落 SharedPreferences——判定必须早于**落地导航**、且是一次可同步完成的读（判定本身在中转页的启动 effect 内完成，见 ADR-0001）。
 */
object StartupStore {

    private val prefs: android.content.SharedPreferences
        get() = ServiceLocator.context.getSharedPreferences("startup", Context.MODE_PRIVATE)

    /** 上次启动判定所需的完整状态（每次现读，不缓存） */
    fun state(): StartupState = StartupState(
        lastRead = lastRead(),
        lastBrowsing = lastBrowsing(),
        wasReading = prefs.getBoolean(KEY_WAS_READING, false),
        lastTopLevel = lastTopLevel(),
    )

    /**
     * 启动落地判定的「快照入口」：**在一次同步调用里**读完判定所需的全部落盘状态
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
     * 上次停留的**浏览路径**（栈底 → 当前层）：重启后按它重建整条层级链，
     * 返回因此逐级回到上一级、只有首页再返回才退出 APP（只记当前位置的话，重启后返回只剩「回首页」一条路）。
     *
     * **术语**：本处的「浏览路径」指**用户停留的层级链**（浏览页逐层下钻留下的那串位置），
     * 与 `CONTEXT.md` 里连接的 `browsePath`（进连接后从哪一层开始，见
     * [com.cc3301.comicviewer.core.source.komga.KomgaConnectionConfig.browsePath]）**同词不同义**——两者只是同名。
     *
     * 落盘时机：**阅读页每层显示时**（[recordBrowsePosition]）与**会话结束**（[ServiceLocator.closeSession]，
     * 即 Activity finish）两处都写，写的是同一个值——设备上更常见的退出是任务被划掉 / 进程被杀，那时没有 finish，
     * 只有逐层写下的这份可用。旋转这类非 finish 的重建不动它（历史随进程存活，回退栈也由系统还原）。
     *
     * **每层还带着条目名**：启动按它重建浏览层时把名字一并写进路由参数，
     * 进程重建（缓存为空）后浏览页标题因此仍是目录名，不依赖会话内存缓存、也不打网络。
     */
    fun browsingPath(): List<BrowseLocation> = decodePath(prefs.getString(KEY_BROWSING_PATH, null))

    /**
     * **顶层落点之下那段浏览链**：用户停在首页/书柜/设置时，**它下面**压着的那段浏览层。
     *
     * 与 [browsingPath] 是两份记录、两个键：[browsingPath] 记的是「上次停留的浏览路径」，
     * 它在用户从浏览层退回首页之后**不会被改写**（那是它既有的写点口径，这里不动它）；本记录只在停在顶层入口
     * 那一帧写（写点见 [recordTopLevelBrowseChain]），因此「这段链真的压在这条顶层路由之下」是结构性事实。
     *
     * 为什么必须是独立的键（而不是拿 [browsingPath] 当它用）：旧版本落下的数据里没有「停在顶层入口时它下面
     * 有没有链」这一位——若拿 [browsingPath] 顶替，升级后第一次启动会把一条**陈旧**路径（用户在浏览根层退回
     * 首页后退出留下的那条）误当成首页之下的链，于是「在首页按返回」会先跑进浏览页而不是退出 APP。
     * 本键在旧数据里不存在 ⇒ 读出来是空 ⇒ 不恢复该链，升级后的行为与改前一致。
     */
    fun topLevelBrowseChain(): List<BrowseLocation> = decodePath(prefs.getString(KEY_TOP_LEVEL_CHAIN, null))

    /**
     * 记录顶层落点之下的那段浏览链；空链即清除记录（这条顶层路由下面本来就没有浏览层）。
     * 写点唯一：`AppNav` 的 `LaunchedEffect(currentRoute)`（与 [recordTopLevel] **同帧、同一判据**写，见
     * `recordTopLevelForRoute`）——其余路由一律不写它。
     *
     * **与顶层落点记录同帧写，但不同寿命**：
     * [clearTopLevel] 与 [clearBrowsing] 都**不**动本键，只有下一次停在顶层入口那一帧才重写它——
     * 留下来的旧值不会被误用，因为读侧（`AppNav.browseChainBelowTopLevel`）凭 `lastTopLevel` 与落点路由
     * 相等才用它（进阅读器/浏览层清掉顶层落点后，本键根本到不了读侧）。
     */
    fun recordTopLevelBrowseChain(path: List<BrowseLocation>) {
        val edit = prefs.edit()
        if (path.isEmpty()) {
            edit.remove(KEY_TOP_LEVEL_CHAIN)
        } else {
            edit.putString(KEY_TOP_LEVEL_CHAIN, encodeBrowsingPath(path))
        }
        edit.apply()
    }

    /**
     * 路径编码的解码（[KEY_BROWSING_PATH] 与 [KEY_TOP_LEVEL_CHAIN] 共用一处）：首行连接 id（一条路径只属于
     * 一个连接）、次行层数，其余每行一层的**百分号编码**容器 id 与条目名（每层是「容器 id | 条目名」
     * 两段，旧数据只有容器 id 一段——那时名字读成 null、行为与改前一致）。
     * 段数与落盘时记的层数不一致 = 不是本编码写的（旧格式 / 手改库 / 损坏）：整条作废，
     * 让调用方退回「只恢复当前位置」（中间层 id 带分隔符时不能把一条错路径当合法）。
     */
    private fun decodePath(raw: String?): List<BrowseLocation> {
        val lines = raw?.split(ENCODING_SEPARATOR) ?: return emptyList()
        val connId = lines.getOrNull(0)?.toLongOrNull() ?: return emptyList()
        val layers = lines.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        if (lines.size - PATH_HEADER_LINES != layers) return emptyList()
        // 每段落盘前做过百分号编码，解码后即原样（含换行/分隔符的 id、带 `|` 的名字也仍是一段）
        return lines.drop(PATH_HEADER_LINES).map { line ->
            BrowseLocation(
                connId = connId,
                containerId = Uri.decode(line.substringBefore(NAME_SEPARATOR)).ifEmpty { null },
                containerName = Uri.decode(line.substringAfter(NAME_SEPARATOR, missingDelimiterValue = ""))
                    .ifEmpty { null },
            )
        }
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
     * 浏览页显示某层时的落盘：把「上次停留的位置」与**整条浏览路径**在
     * **同一次调用**里写下去。
     *
     * 为什么不能只靠会话结束（[ServiceLocator.closeSession] 里那次 [recordBrowsingPath]）那次写：
     * [startupBrowsePath] 采用落盘路径的判据是「路径最后一层 = 本次恢复到的位置」，而「上次停留的位置」是
     * 浏览页每次显示都写（[recordBrowsing]）——任务被划掉、进程被杀这类**没有 Activity finish** 的退出之后，
     * 落盘路径还是上一会话的（或空的），启动因此只能恢复一层，返回于是直接跳回首页（设备反馈的现象）。
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
     * 路径编码：首行连接 id（一条路径只属于一个连接）、次行层数，其余每行一层的容器 id 与条目名
     * （每层两段，`|` 分隔；空串 = 根层 / 没有名字）。分隔符因此**不可能**出现在段内——
     * 写前 [Uri.encode]（把 `\n` 与 `|` 都变成 `%0A` / `%7C`）、读后 [Uri.decode]，
     * 容器 id 带换行/`%`/`?`、名字带 `|` 也仍是一段。
     */
    private fun encodeBrowsingPath(path: List<BrowseLocation>): String =
        (
            listOf(path.first().connId.toString(), path.size.toString()) +
                path.map { Uri.encode(it.containerId ?: "") + NAME_SEPARATOR + Uri.encode(it.containerName ?: "") }
            ).joinToString(ENCODING_SEPARATOR)

    /**
     * 清掉上次停留的位置：该记录指向的连接已被删除时它再也恢复不了，
     * 留着只会让每次启动都重走一遍「导航到浏览页再弹回」的退化路径。
     * 路径与它同属一份「上次停留的位置」，一并清掉。
     */
    fun clearBrowsing() {
        prefs.edit()
            .remove(KEY_BROWSING_CONN)
            .remove(KEY_BROWSING_CONTAINER)
            .remove(KEY_BROWSING_PATH)
            .apply()
    }

    /** 记录最近阅读的书（阅读页真正切进这本书那一刻，由 `ui.applyReaderEntry` 调用；清空时也走它） */
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

    /**
     * 上次退出时停在哪个顶层路由：首页/书柜/设置显示时写入（写点判定见 `AppNav.topLevelRecordFor`）。
     *
     * 为什么需要它：全仓原先只有「上次停留的**浏览**位置」这一条记录，而它在首页/书柜上从不更新——
     * 在首页退出后启动只能读到很久以前那个目录，于是「上次阅读的位置」（不在阅读器时）与「上次停留的位置」
     * 都回落到那里。本记录与 [lastBrowsing] 分工：停在浏览层时由后者说话。
     */
    fun lastTopLevel(): LastTopLevel? = LastTopLevel.fromKey(prefs.getString(KEY_TOP_LEVEL, null))

    /** 记录顶层落点（首页/书柜/设置显示时调用）；浏览页显示时用 [clearTopLevel] 清掉 */
    fun recordTopLevel(top: LastTopLevel) {
        prefs.edit().putString(KEY_TOP_LEVEL, top.key).apply()
    }

    /**
     * 清掉顶层落点记录（进了浏览层时调用）：位置从此由「上次停留的位置」说话。
     * 只动本键——[KEY_BROWSING_CONN] 那条要留给开书失败的兜底用（见 `resolveStartupRead`），不能一并清。
     */
    fun clearTopLevel() {
        prefs.edit().remove(KEY_TOP_LEVEL).apply()
    }

    private const val KEY_READ_CONN = "last_read_conn"
    private const val KEY_READ_BOOK = "last_read_book"
    private const val KEY_BROWSING_CONN = "last_browsing_conn"
    private const val KEY_BROWSING_CONTAINER = "last_browsing_container"

    /** 上次停留的浏览路径：首行连接 id、次行层数，其余每行一层容器 id 的百分号编码（空串 = 根层） */
    private const val KEY_BROWSING_PATH = "last_browsing_path"

    /** 顶层落点之下那段浏览链：编码与 [KEY_BROWSING_PATH] 同一处（[encodeBrowsingPath] / [decodePath]） */
    private const val KEY_TOP_LEVEL_CHAIN = "last_top_level_browse_chain"
    private const val ENCODING_SEPARATOR = "\n"

    /** [KEY_BROWSING_PATH] / [KEY_TOP_LEVEL_CHAIN] 的头两行：连接 id + 层数 */
    private const val PATH_HEADER_LINES = 2

    /**
     * 一层的「容器 id」与「条目名」之间的分隔：[Uri.encode] 会把 `|` 编码成 `%7C`，
     * 因此它不可能出现在段内；旧数据（只有容器 id 一段、没有本分隔符）解出来的名字是 null。
     */
    private const val NAME_SEPARATOR = "|"
    private const val KEY_WAS_READING = "was_reading"

    /** 顶层落点：首页/书柜/设置之一的路由名，见 [LastTopLevel] */
    private const val KEY_TOP_LEVEL = "last_top_level"
}
