package com.cc3301.comicviewer.core.nav

/**
 * 启动页面（spec 故事 46）：APP 启动时进入的界面，默认「上次阅读的位置」。
 */
enum class StartupPage(val key: String) {
    LAST_READ("last_read"),
    LAST_BROWSING("last_browsing"),
    BOOKSHELF("bookshelf"),
    READER("reader"),
    HOME("home"),
    ;

    companion object {
        /** 持久化键解析；未知/缺失回退默认项「上次阅读的位置」 */
        fun fromKey(key: String?): StartupPage = entries.firstOrNull { it.key == key } ?: LAST_READ
    }
}

/**
 * 上次停留的位置（spec 故事 48）：退出时所停留的浏览界面状态。
 * 只含目录层级——排序方式与方向属于全局设置（票 #29），本就一直保持、不参与恢复；
 * 滚轮/滚动位置也不恢复（SPEC Out of Scope）。
 */
data class LastBrowsing(
    val connId: Long,
    val containerId: String?,
)

/**
 * 顶层落点（票 #137）：用户退出时停在**顶层路由**（首页 / 书柜 / 设置）的那一处。
 *
 * 与 [LastBrowsing] 互补：停在浏览层时位置由 [LastBrowsing] 说话，离开浏览层（首页/书柜/设置）后由本记录说话。
 * 没有它时，在首页退出后启动只能读到很久以前那个浏览目录（票 #137 的真机现象）。
 *
 * 设置页只在**本记录**里可达：它不是启动页面选项（[StartupPage] 仍是五选项，见 spec 故事 46），
 * 但记录必须始终可解析，否则「路由变到顶层时改写它」就没有意义。
 */
enum class LastTopLevel(val key: String) {
    HOME("home"),
    BOOKSHELF("bookshelf"),
    SETTINGS("settings"),
    ;

    companion object {
        /** 持久化键解析；未知/缺失返回 null（= 没有顶层落点记录，退化为 [LastBrowsing]） */
        fun fromKey(key: String?): LastTopLevel? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 启动判定所需的「上次状态」（票 20）。
 * [wasReading]：上次退出时是否正停在阅读器——「上次阅读的位置」的退化条件（spec 故事 47）。
 * [lastTopLevel]：上次退出时停在哪个顶层路由（首页/书柜/设置，票 #137）——有它时「上次停留的位置」落它。
 */
data class StartupState(
    val lastRead: LastRead? = null,
    val lastBrowsing: LastBrowsing? = null,
    val wasReading: Boolean = false,
    val lastTopLevel: LastTopLevel? = null,
)

/** 启动目的地（票 20）：由设置项与上次状态判定得出，随后由 AppNav 落地为导航。 */
sealed interface StartupTarget {
    /** 直接打开上次阅读的书并定位到上次页码（会话来源与 lastRead 需在导航前备好） */
    data class OpenReader(val lastRead: LastRead) : StartupTarget

    /** 恢复浏览界面（目录层级） */
    data class OpenBrowser(val browsing: LastBrowsing) : StartupTarget

    data object OpenBookshelf : StartupTarget
    data object OpenHome : StartupTarget

    /**
     * 顶层落点记录指向设置页（票 #137）：**只有**「上次停留的位置」（或「上次阅读的位置」不在阅读器时的
     * 退化）读到顶层落点 = 设置时产生——启动页面选项本身没有设置页（[StartupPage] 仍是五选项）。
     */
    data object OpenSettings : StartupTarget
}

/**
 * 启动落地判定（spec 故事 46/47/48，纯函数）：
 * - 上次阅读的位置（默认）：上次退出时正在看书才打开该书并定位到上次页码，否则退化为上次停留的位置（故事 47）
 * - 上次停留的位置：顶层落点记录（首页/书柜/设置，票 #137）优先，否则恢复目录层级（故事 48；排序方式与方向是全局设置，不在恢复之列）
 * - 阅读器：始终打开上次阅读的书；没有读书记录时退化为首页
 * - 书柜 / 首页：固定目的地
 *
 * 边界（无任何上次状态，如首次启动）：一律落到首页。
 */
fun resolveStartupTarget(page: StartupPage, state: StartupState): StartupTarget = when (page) {
    StartupPage.LAST_READ -> when {
        state.wasReading && state.lastRead != null -> StartupTarget.OpenReader(state.lastRead)
        else -> lastStopTarget(state)
    }
    StartupPage.LAST_BROWSING -> lastStopTarget(state)
    StartupPage.READER ->
        state.lastRead?.let { StartupTarget.OpenReader(it) } ?: StartupTarget.OpenHome
    StartupPage.BOOKSHELF -> StartupTarget.OpenBookshelf
    StartupPage.HOME -> StartupTarget.OpenHome
}

/**
 * 「上次停留的位置」的落点（票 #137）：**顶层落点记录优先**——它记的正是「用户已离开浏览层、停在顶层」那一刻；
 * 没有顶层记录（首次启动、升级安装里的旧数据、当前就在浏览层）时才退化为上次停留的浏览目录。
 */
private fun lastStopTarget(state: StartupState): StartupTarget = when (state.lastTopLevel) {
    LastTopLevel.HOME -> StartupTarget.OpenHome
    LastTopLevel.BOOKSHELF -> StartupTarget.OpenBookshelf
    LastTopLevel.SETTINGS -> StartupTarget.OpenSettings
    null -> state.lastBrowsing?.let { StartupTarget.OpenBrowser(it) } ?: StartupTarget.OpenHome
}

/**
 * 连接缺失时的启动兜底（票 #33，票 #26 收窄口径）：启动目标指向的连接已经不存在时（OPDS-only 用户在 v2→v3 迁移后被清库、
 * 或用户手工删了该连接），浏览页没有可加载的内容——只会停在「加载中…」。
 *
 * 目前生产路径只对 [StartupTarget.OpenBrowser] 调用它（`AppNav.prepareStartup` 的浏览分支）：
 * 阅读器分支在自己的退化链里先退化为「上次停留的位置」、仍不可解析才回落首页（见 [resolveStartupTarget] 的 KDoc），
 * 因此 [StartupTarget.OpenReader] 分支是防御性的（生产不可达）——保留以保证任何调用方语义一致。
 * 不依赖连接的启动目标（书柜/首页）原样返回。
 */
fun fallbackWhenConnectionMissing(target: StartupTarget): StartupTarget = when (target) {
    is StartupTarget.OpenBrowser, is StartupTarget.OpenReader -> StartupTarget.OpenHome
    StartupTarget.OpenBookshelf, StartupTarget.OpenHome, StartupTarget.OpenSettings -> target
}
