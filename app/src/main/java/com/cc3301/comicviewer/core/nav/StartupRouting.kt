package com.cc3301.comicviewer.core.nav

import com.cc3301.comicviewer.core.source.SortMode

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
 * 只含目录层级与排序方式——滚轮/滚动位置不恢复（SPEC Out of Scope）。
 */
data class LastBrowsing(
    val connId: Long,
    val containerId: String?,
    val sortMode: SortMode = SortMode.NAME,
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

    /** 恢复浏览界面（目录层级 + 排序方式） */
    data class OpenBrowser(val browsing: LastBrowsing) : StartupTarget

    data object OpenBookshelf : StartupTarget
    data object OpenHome : StartupTarget
}

/**
 * 启动落地判定（spec 故事 46/47/48，纯函数）：
 * - 上次阅读的位置（默认）：上次退出时正在看书才打开该书并定位到上次页码，否则退化为上次停留的位置（故事 47）
 * - 上次停留的位置：恢复目录层级与排序方式（故事 48）
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
