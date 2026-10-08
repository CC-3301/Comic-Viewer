package com.cc3301.comicviewer.ui.nav

import com.cc3301.comicviewer.ui.Routes
import com.cc3301.comicviewer.ui.ServiceLocator
import com.cc3301.comicviewer.ui.contentBackEnabled
import com.cc3301.comicviewer.ui.navHostWith

import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import com.cc3301.comicviewer.core.nav.BrowseLocation
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 抽屉开着时内容层返回让位（spec 分册 `docs/spec/shell.md` §「返回逐级」）。
 *
 * 设备现象：抽屉开着按返回走的是**浏览历史后退**（`nav browseBack route=browser/...`），不是关抽屉。
 * 成因是返回回调「后注册先派发」——抽屉自己的返回接管注册得早，内容层各屏的 `BackHandler` 注册得更晚，
 * 于是先拿到返回。口径因此不能靠注册顺序，靠 [LocalDrawerIsClosed]：内容层一律
 * `enabled = 自己那条判据 && 抽屉关着`（判据 [contentBackEnabled]，本文件钉住它的取值）。
 *
 * 三条取值对应三件不能改坏的事：抽屉关着 → 浏览页照旧「返回上一级」；抽屉开着 → 浏览页让位；
 * 阅读器菜单那一处抽屉关着仍是「先关菜单」（语义与浏览页不同，不能与浏览页一起改）。
 *
 * 浏览页那两条用**真实回退栈 + 真实历史镜像**（[navHostWith]，与 [BrowserBackStackSyncTest] 同手法）：
 * 判据里那一段自己那条（`browseBackInterception`）必须真的是「成立」状态，否则这条用例证不了让位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawerBackGateTest {

    private lateinit var nav: NavHostController

    private val history get() = ServiceLocator.session.browseHistory

    private val root = BrowseLocation(connId = 7, containerId = null)
    private val subdir = BrowseLocation(connId = 7, containerId = "dir-sub")

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
        history.clear()
        nav = navHostWith(listOf(Routes.HOME, Routes.BROWSER))
        // 与 BrowserScreen 显示某层时同形：镜像与回退栈逐层一致（连接根层 → 子目录）
        history.record(root)
        nav.navigate(Routes.browser(root.connId, root.containerId))
        history.record(subdir)
        nav.navigate(Routes.browser(subdir.connId, subdir.containerId))
    }

    @After
    fun tearDown() {
        history.clear()
    }

    @Test
    fun `抽屉关着时浏览页返回处理器照旧接管`() {
        assertTrue(
            "原行为不变：抽屉关着按返回 → 返回上一级",
            contentBackEnabled(browseBackInterception(nav, history), drawerIsClosed = true),
        )
    }

    @Test
    fun `抽屉开着时浏览页的返回处理器不接管`() {
        assertTrue("前提：自己那条判据本身成立，否则这条用例证不了「让位」", browseBackInterception(nav, history))
        assertFalse(
            "抽屉开着 → 让位给抽屉（这次返回只关抽屉，不再出现 `nav browseBack`）",
            contentBackEnabled(browseBackInterception(nav, history), drawerIsClosed = false),
        )
    }

    @Test
    fun `抽屉开着时阅读器菜单的返回也让位 抽屉关着时仍是先关菜单`() {
        assertTrue(
            "阅读器菜单语义不变：抽屉关着 → 先关阅读器菜单",
            contentBackEnabled(ownEnabled = true, drawerIsClosed = true),
        )
        assertFalse(
            "抽屉开着 → 不抢返回（先关抽屉）",
            contentBackEnabled(ownEnabled = true, drawerIsClosed = false),
        )
        assertFalse("自己那条判据不成立（菜单没开）时照旧不接管", contentBackEnabled(ownEnabled = false, drawerIsClosed = true))
    }
}
