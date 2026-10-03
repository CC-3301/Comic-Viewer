package com.cc3301.comicviewer.ui.nav

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护用例：钉住「浏览链与启动落地的导航操作全部住在 `ui/nav/`，`AppNav.kt` 不再直接持有它们」。
 *
 * 口径出处：`BrowseNav.kt` 的文件 KDoc——「把导航操作应用到 NavController 的那一层」只声明在这一处；
 * 本用例用反射核两件事：
 * - 那一层的文件门面（`BrowseNavKt`）真的声明了这些操作；
 * - `AppNavKt` 的门面里一个都没有（搬回去任何一个名字，这里立刻红）。
 */
class BrowseNavHomeGuardTest {

    /** 浏览链与启动落地的那 25 个符号（按 `startupBrowsePath` … `landStartupTopLevel` 清单）里的方法名 */
    private val operations = listOf(
        "startupBrowsePath",
        "resetBrowseHistoryForStartup",
        "pushBrowserPath",
        "recordBrowsePosition",
        "browseLayersOnStack",
        "syncBrowseHistory",
        "browseLocationOf",
        "navigateToBrowseLocation",
        "reopenBrowsingPath",
        "replayTopLevelEntry",
        "popAbove",
        "browseBackInterception",
        "navigateTopLevel",
        "revealBrowsingLayerBelowTopLevelEntries",
        "openReaderFromDrawer",
        "navigateStartupReader",
        "navObservation",
        "readingFlagToRecord",
        "topLevelRecordFor",
        "recordTopLevelForRoute",
        "topLevelRouteOf",
        "usableTopLevelBrowseChain",
        "browseChainBelowTopLevel",
        "pushStartupRootHome",
        "landStartupTopLevel",
    )

    private fun declaredMethodNames(className: String): Set<String> =
        Class.forName(className).declaredMethods.map { it.name }.toSet()

    @Test
    fun `导航操作全部住在BrowseNav`() {
        val browseNav = declaredMethodNames("com.cc3301.comicviewer.ui.nav.BrowseNavKt")
        val missing = operations.filter { it !in browseNav }
        assertTrue("BrowseNavKt 缺少这些操作：$missing", missing.isEmpty())
    }

    @Test
    fun `AppNav不再直接持有这些导航操作`() {
        val appNav = declaredMethodNames("com.cc3301.comicviewer.ui.AppNavKt")
        val leaked = operations.filter { it in appNav }
        assertTrue("AppNavKt 仍持有这些导航操作：$leaked", leaked.isEmpty())
    }

    @Test
    fun `导航观测事件常量也住在nav包`() {
        Class.forName("com.cc3301.comicviewer.ui.nav.NavEvent")
        Class.forName("com.cc3301.comicviewer.ui.nav.TopLevelRecord")
        Class.forName("com.cc3301.comicviewer.ui.nav.StartupReadOutcome")
    }
}
