package com.cc3301.comicviewer.ui

import android.content.Context
import androidx.navigation.NavArgument
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 读内换书的**导航路径**（票 #68 r3）：菜单「上一本/下一本」、跨书确认条与抽屉「阅读器」入口都走
 * [readerSwapNavOptions]，而阅读页的 `bookId` 参数来自
 * `composable(Routes.READER) { entry -> entry.arguments?.getString("bookId") }`。
 *
 * 为什么要在这一层钉：真机验收「开态下两个方向都不回第 1 页」失败，而 `OpenForReadingTest`（纯函数层）
 * 全绿 —— 落点判定没错，可疑的是**阅读页到底拿到哪本书、以及拿到新书后宿主态有没有重建**。
 * 这里不组合 UI，只跑真实的 `NavController` + 与生产同名的路由图，把这条链上能在单测里钉住的两件事钉死：
 * ① 换书后阅读器 destination 读到的是新书 id（`bookId` 确实是分槽键的输入）；
 * ② 换书换的是**一条新的 back stack entry**（宿主态与保存态因此整体重建，不依赖 Compose 分槽键兜底）。
 * ②在 `launchSingleTop` 实现下会红（entry id 不变）—— 本票真机失败就发生在这一层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderSwapNavTest {

    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        nav = NavHostController(context)
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        val graphNavigator = nav.navigatorProvider.getNavigator(NavGraphNavigator::class.java)
        val graph: NavGraph = graphNavigator.createDestination().apply {
            route = "root"
            setStartDestination(Routes.HOME)
        }
        graph.addDestination(destination(Routes.HOME))
        graph.addDestination(destination(Routes.BROWSER))
        graph.addDestination(
            destination(Routes.READER).apply {
                addArgument("bookId", NavArgument.Builder().setType(NavType.StringType).build())
            },
        )
        nav.setGraph(graph, null)
    }

    private fun destination(route: String): ComposeNavigator.Destination =
        ComposeNavigator.Destination(
            nav.navigatorProvider.getNavigator(ComposeNavigator::class.java),
        ) { }.apply { this.route = route }

    /** 阅读页读到的书 id（与 `AppNav` 的 `entry.arguments?.getString("bookId")` 同源） */
    private fun readerBookId(): String? = nav.currentBackStackEntry?.arguments?.getString("bookId")

    private fun openReader(bookId: String) {
        nav.navigate(Routes.reader(bookId), readerSwapNavOptions())
    }

    @Test
    fun `换书后阅读器读到的是新书 id`() {
        openReader("book-a")
        assertEquals("book-a", readerBookId())

        openReader("book-b")

        assertEquals("换书后 bookId 必须是新书：否则阅读页整屏都还是上一本（含页位）", "book-b", readerBookId())
    }

    @Test
    fun `换书换一条 entry：宿主态与保存态随之整体重建`() {
        nav.navigate(Routes.HOME) { launchSingleTop = true }
        nav.navigate(Routes.browser(1L, null)) { launchSingleTop = true }
        openReader("book-a")
        val firstId = nav.currentBackStackEntry?.id

        openReader("book-b")

        assertNotEquals(
            "换书必须换一条 entry：宿主态（页位/页边界表/按页缩放表/菜单/确认条）与保存态挂在 entry 上，" +
                "复用同一条 entry 时页位正确性只能靠 Compose 分槽键兜底（真机验收时正是这层没兜住）",
            firstId,
            nav.currentBackStackEntry?.id,
        )
        assertEquals("book-b", readerBookId())
    }

    @Test
    fun `换书后回退栈里仍只有一条阅读器 entry`() {
        nav.navigate(Routes.HOME) { launchSingleTop = true }
        nav.navigate(Routes.browser(1L, null)) { launchSingleTop = true }
        openReader("book-a")

        openReader("book-b")

        assertTrue("回退必须成功", nav.popBackStack())
        assertEquals(
            "换书不得把上一条阅读器 entry 留在栈里（回退仍落到浏览层）",
            Routes.BROWSER,
            nav.currentBackStackEntry?.destination?.route,
        )
    }

    @Test
    fun `阅读器是栈底时换书也不报错`() {
        // 启动页面选「阅读器」/「上次阅读的位置」时，阅读器可以就是唯一一条 entry：
        // 「换 entry」的实现不能在建栈这件事上翻车
        openReader("book-a")

        openReader("book-b")

        assertEquals("book-b", readerBookId())
        assertEquals(Routes.READER, nav.currentBackStackEntry?.destination?.route)
    }
}
