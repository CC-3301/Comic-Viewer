package com.cc3301.comicviewer.ui

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.cc3301.comicviewer.ui.nav.browseLocationOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 常驻浏览层的**宿主范围**（[hostedBrowseEntry]，宿主是 `ui/BrowseLayerHost.kt`）：浏览页住在 `NavHost`
 * 之外，进阅读器时不被销毁、退出时已经就位——这一条全靠「栈顶是阅读器时仍渲染它下面那一条浏览层
 * （且往下第一条非阅读器层确实是浏览层），而且渲染的是**同一个 entry**」（换一个实例就是重建，退出时
 * 又要从零取数、重排、放回位置）。
 *
 * 这里不组合 UI，只跑真实的 `NavController`（与 [ReaderSwapNavTest] 同一手法）：断言选中的栈项
 * （路由 + 连接 + 容器 + 同一条 entry 的身份 + 它还在栈上）。组合本地提供者与遮挡那一半不在本用例范围
 * （需要 Compose UI 测试基建，仓库没有；那两处的口径写在 `ui/BrowseLayerHost.kt` 的 KDoc 里）。
 *
 * **本图是 `AppNav` 路由表的复刻**（建图走共用的 [navHostWith]）：route 串取自同一份 `Routes` 常量，
 * 改生产的目的地集合时同步 [setUp] 的清单。
 *
 * 用例只做**纯导航**：`popBackStack`、以及「栈里同时有两条阅读器层」那种栈形（导航到阅读器两次）都会让
 * 同一 JVM 里后续用例的组合推进停摆；后者正是 `hostedBrowseEntry` 跳过连续阅读器层那一条要护的瞬态，
 * 生产换书用 `popUpTo(READER){inclusive}` 收尾、组合看不到它，因此这里不补用例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseLayerHostTest {

    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        nav = navHostWith(listOf(Routes.HOME, Routes.SETTINGS, Routes.BROWSER, Routes.READER))
    }

    /** 生产那一句的同一形状：栈顶路由 + 整条回退栈 */
    private fun hosted(): NavBackStackEntry? =
        hostedBrowseEntry(nav.currentDestination?.route, nav.currentBackStack.value)

    private fun openBrowser(containerId: String?) {
        nav.navigate(Routes.browser(7L, containerId, containerId))
    }

    private fun openReader(bookId: String) {
        nav.navigate(Routes.reader(bookId))
    }

    private fun containerOf(entry: NavBackStackEntry?): String? = browseLocationOf(entry)?.containerId

    @Test
    fun `栈顶是浏览层时渲染它`() {
        openBrowser(null)

        assertEquals("根层", null, containerOf(hosted()))
        assertEquals(7L, browseLocationOf(hosted())?.connId)
    }

    @Test
    fun `阅读器压在浏览层上时仍渲染下面那一层：同一条 entry`() {
        openBrowser("dir-sub")
        val below = hosted()

        openReader("book-a")

        assertSame(
            "阅读器在栈顶时被盖住的浏览层必须还是同一条 entry：换一个实例就是重建（退出时又要从零取数、重排、放回位置）",
            below,
            hosted(),
        )
        assertTrue(
            "浏览层这一条仍在回退栈上：退出弹掉的是阅读器那一条，落回来的正是它",
            nav.currentBackStack.value.any { it === below },
        )
    }

    @Test
    fun `多级浏览层时渲染最上面那一层`() {
        openBrowser(null)
        openBrowser("dir-sub")

        assertEquals("dir-sub", containerOf(hosted()))

        openReader("book-a")

        assertEquals("被阅读器盖住的仍是最上面那一层（退出正是回到它）", "dir-sub", containerOf(hosted()))
    }

    @Test
    fun `栈顶不是浏览层也不是阅读器时不挂`() {
        openBrowser("dir-sub")

        nav.navigate(Routes.SETTINGS)

        assertNull("设置页在栈顶时不挂常驻层（与改前一致：那一屏不在组合树里）", hosted())
    }

    @Test
    fun `阅读器之下是别的层时不挂`() {
        openBrowser("dir-sub")
        nav.navigate(Routes.SETTINGS)

        openReader("book-a")

        assertNull(
            "阅读器之下第一条非阅读器层是设置页 ⇒ 退出落到设置页而不是浏览层，被盖住的浏览层挂上只会白建白画",
            hosted(),
        )
    }


    @Test
    fun `回退栈里没有浏览层时不挂`() {
        // 从首页直接进阅读器（抽屉入口 / 冷启动直进）：阅读器之下不是浏览层，没有可保住的浏览页
        openReader("book-a")

        assertNull(hosted())
    }
}
