package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.test.core.app.ApplicationProvider

/**
 * `AppNav` 路由图的测试复刻脚手架（票 #115）：NavObservationTest / BrowserBackStackSyncTest / ReaderSwapNavTest
 * 三处同构的建图代码合成一份。
 *
 * 图 = 生产的**子集**：只建被测路径需要的 destination，route 串取自同一份 [Routes] 常量（因此串不会漂），
 * 起手 = 冷启动落在首页（栈底 `[HOME]`，与 `AppNav` 落地后的回退栈同形）。
 * **改生产的接线（destination 集合、`navigate()` 的选项、路由参数声明）必须同步调用点传给 [navHostWith]
 * 的 route 列表。**
 */
internal fun navHostWith(routes: List<String>): NavHostController {
    val context: Context = ApplicationProvider.getApplicationContext()
    val controller = NavHostController(context)
    controller.navigatorProvider.addNavigator(ComposeNavigator())
    val graphNavigator = controller.navigatorProvider.getNavigator(NavGraphNavigator::class.java)
    val graph: NavGraph = graphNavigator.createDestination().apply {
        route = "root"
        setStartDestination(Routes.HOME)
    }
    routes.forEach { graph.addDestination(destination(controller, it)) }
    controller.setGraph(graph, null)
    return controller
}

/**
 * 路由表里的一条 destination：**不声明参数**（与生产的 `composable(route)` 一致，`bookId` / `connId` 这类
 * 参数由路由模板解析）。
 */
internal fun destination(controller: NavHostController, route: String): ComposeNavigator.Destination =
    ComposeNavigator.Destination(
        controller.navigatorProvider.getNavigator(ComposeNavigator::class.java),
    ) { }.apply { this.route = route }
