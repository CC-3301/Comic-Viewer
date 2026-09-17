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
 * 启动判定所需的「上次状态」（票 20）。
 * [wasReading]：上次退出时是否正停在阅读器——「上次阅读的位置」的退化条件（spec 故事 47）。
 */
data class StartupState(
    val lastRead: LastRead? = null,
    val lastBrowsing: LastBrowsing? = null,
    val wasReading: Boolean = false,
)

/** 启动目的地（票 20）：由设置项与上次状态判定得出，随后由 AppNav 落地为导航。 */
sealed interface StartupTarget {
    /** 直接打开上次阅读的书并定位到上次页码（会话来源与 lastRead 需在导航前备好） */
    data class OpenReader(val lastRead: LastRead) : StartupTarget

    /** 恢复浏览界面（目录层级） */
    data class OpenBrowser(val browsing: LastBrowsing) : StartupTarget

    data object OpenBookshelf : StartupTarget
    data object OpenHome : StartupTarget
}

/**
 * 启动落地判定（spec 故事 46/47/48，纯函数）：
 * - 上次阅读的位置（默认）：上次退出时正在看书才打开该书并定位到上次页码，否则退化为上次停留的位置（故事 47）
 * - 上次停留的位置：恢复目录层级（故事 48；排序方式与方向是全局设置，不在恢复之列）
 * - 阅读器：始终打开上次阅读的书；没有读书记录时退化为首页
 * - 书柜 / 首页：固定目的地
 *
 * 边界（无任何上次状态，如首次启动）：一律落到首页。
 */
fun resolveStartupTarget(page: StartupPage, state: StartupState): StartupTarget = when (page) {
    StartupPage.LAST_READ -> when {
        state.wasReading && state.lastRead != null -> StartupTarget.OpenReader(state.lastRead)
        else -> state.lastBrowsing?.let { StartupTarget.OpenBrowser(it) } ?: StartupTarget.OpenHome
    }
    StartupPage.LAST_BROWSING ->
        state.lastBrowsing?.let { StartupTarget.OpenBrowser(it) } ?: StartupTarget.OpenHome
    StartupPage.READER ->
        state.lastRead?.let { StartupTarget.OpenReader(it) } ?: StartupTarget.OpenHome
    StartupPage.BOOKSHELF -> StartupTarget.OpenBookshelf
    StartupPage.HOME -> StartupTarget.OpenHome
}

/**
 * 连接缺失时的启动兜底（票 33）：启动目标指向的连接已经不存在时（OPDS-only 用户在 v2→v3 迁移后被清库、
 * 或用户手工删了该连接），浏览页与阅读器都没有可加载的内容——浏览页只会停在「加载中…」。
 * 此时一律回落首页；不依赖连接的启动目标（书柜/首页）原样返回。
 */
fun fallbackWhenConnectionMissing(target: StartupTarget): StartupTarget = when (target) {
    is StartupTarget.OpenBrowser, is StartupTarget.OpenReader -> StartupTarget.OpenHome
    StartupTarget.OpenBookshelf, StartupTarget.OpenHome -> target
}
