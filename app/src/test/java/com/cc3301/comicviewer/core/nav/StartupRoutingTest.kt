package com.cc3301.comicviewer.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 启动落地判定（票 20，spec 故事 46/47/48）：五选项 + 默认项退化分支逐条锁定，
 * 并锁定票 33 的「连接已不存在」兜底（旧指针指向被删连接时回落首页）。
 * 纯函数，不依赖 Android。
 */
class StartupRoutingTest {

    private val book = LastRead(connId = 1, bookId = "book-a")
    private val browsing = LastBrowsing(connId = 1, containerId = "dir-b")

    @Test
    fun `默认项 退出时正在看书 直接打开该书`() {
        val target = resolveStartupTarget(
            StartupPage.LAST_READ,
            StartupState(lastRead = book, lastBrowsing = browsing, wasReading = true),
        )
        assertEquals(StartupTarget.OpenReader(book), target)
    }

    @Test
    fun `默认项 退出时在浏览 退化为上次停留的位置`() {
        val target = resolveStartupTarget(
            StartupPage.LAST_READ,
            StartupState(lastRead = book, lastBrowsing = browsing, wasReading = false),
        )
        assertEquals(StartupTarget.OpenBrowser(browsing), target)
    }

    @Test
    fun `默认项 退出时正在看书但没有浏览记录 仍进阅读器`() {
        val target = resolveStartupTarget(
            StartupPage.LAST_READ,
            StartupState(lastRead = book, lastBrowsing = null, wasReading = true),
        )
        assertEquals(StartupTarget.OpenReader(book), target)
    }

    @Test
    fun `默认项 无读书记录但有浏览记录 退化为上次停留的位置`() {
        val target = resolveStartupTarget(
            StartupPage.LAST_READ,
            StartupState(lastRead = null, lastBrowsing = browsing, wasReading = true),
        )
        assertEquals(StartupTarget.OpenBrowser(browsing), target)
    }

    @Test
    fun `无任何上次记录 一律落到首页`() {
        val empty = StartupState()
        assertEquals(StartupTarget.OpenHome, resolveStartupTarget(StartupPage.LAST_READ, empty))
        assertEquals(StartupTarget.OpenHome, resolveStartupTarget(StartupPage.LAST_BROWSING, empty))
        assertEquals(StartupTarget.OpenHome, resolveStartupTarget(StartupPage.READER, empty))
    }

    @Test
    fun `上次停留的位置 恢复目录层级 与是否在看书无关`() {
        val state = StartupState(lastRead = book, lastBrowsing = browsing, wasReading = true)
        assertEquals(StartupTarget.OpenBrowser(browsing), resolveStartupTarget(StartupPage.LAST_BROWSING, state))
    }

    @Test
    fun `阅读器选项 不看在看书标志 直接打开最近一本`() {
        val state = StartupState(lastRead = book, lastBrowsing = browsing, wasReading = false)
        assertEquals(StartupTarget.OpenReader(book), resolveStartupTarget(StartupPage.READER, state))
    }

    @Test
    fun `书柜与首页是固定目的地`() {
        val state = StartupState(lastRead = book, lastBrowsing = browsing, wasReading = true)
        assertEquals(StartupTarget.OpenBookshelf, resolveStartupTarget(StartupPage.BOOKSHELF, state))
        assertEquals(StartupTarget.OpenHome, resolveStartupTarget(StartupPage.HOME, state))
    }

    @Test
    fun `持久化键解析 未知或缺失回退默认项`() {
        assertEquals(StartupPage.LAST_READ, StartupPage.fromKey(null))
        assertEquals(StartupPage.LAST_READ, StartupPage.fromKey("bogus"))
        assertEquals(StartupPage.LAST_BROWSING, StartupPage.fromKey("last_browsing"))
        assertEquals(StartupPage.BOOKSHELF, StartupPage.fromKey("bookshelf"))
        assertEquals(StartupPage.READER, StartupPage.fromKey("reader"))
        assertEquals(StartupPage.HOME, StartupPage.fromKey("home"))
    }

    // ---------- 顶层落点（票 #137）----------

    @Test
    fun `上次停留的位置 顶层落点是首页或书柜时 落到该顶层路由`() {
        // 现象：在首页退出、重开却落到很久以前那个目录列表——全仓没有记录「用户已离开浏览层、停在顶层」
        val atHome = StartupState(
            lastRead = book,
            lastBrowsing = browsing,
            wasReading = false,
            lastTopLevel = LastTopLevel.HOME,
        )
        assertEquals(StartupTarget.OpenHome, resolveStartupTarget(StartupPage.LAST_BROWSING, atHome))
        assertEquals(
            StartupTarget.OpenBookshelf,
            resolveStartupTarget(StartupPage.LAST_BROWSING, atHome.copy(lastTopLevel = LastTopLevel.BOOKSHELF)),
        )
    }

    @Test
    fun `上次停留的位置 顶层落点是设置页时 落到设置页`() {
        // 设置页只在顶层落点记录里可达：它不是启动页面选项（见 StartupPage 五选项），
        // 但记录必须始终可解析——否则「路由变到设置时改写它」就没有意义
        val state = StartupState(lastBrowsing = browsing, lastTopLevel = LastTopLevel.SETTINGS)
        assertEquals(StartupTarget.OpenSettings, resolveStartupTarget(StartupPage.LAST_BROWSING, state))
    }

    @Test
    fun `默认项 退出时不在阅读器 顶层落点同样优先于上次停留的目录`() {
        val state = StartupState(
            lastRead = book,
            lastBrowsing = browsing,
            wasReading = false,
            lastTopLevel = LastTopLevel.BOOKSHELF,
        )
        assertEquals(StartupTarget.OpenBookshelf, resolveStartupTarget(StartupPage.LAST_READ, state))
    }

    @Test
    fun `默认项 退出时在阅读器 顶层落点不参与判定`() {
        // 故事 47 那条链一字不动：正在看书就打开那本书，哪怕更早曾停在某个顶层路由
        val state = StartupState(
            lastRead = book,
            lastBrowsing = browsing,
            wasReading = true,
            lastTopLevel = LastTopLevel.HOME,
        )
        assertEquals(StartupTarget.OpenReader(book), resolveStartupTarget(StartupPage.LAST_READ, state))
    }

    @Test
    fun `没有顶层落点记录时 仍退化为上次停留的位置`() {
        // 升级安装/旧数据：只有「上次停留的位置」一条记录时行为不变
        val state = StartupState(lastRead = book, lastBrowsing = browsing, wasReading = false)
        assertEquals(StartupTarget.OpenBrowser(browsing), resolveStartupTarget(StartupPage.LAST_READ, state))
        assertEquals(StartupTarget.OpenBrowser(browsing), resolveStartupTarget(StartupPage.LAST_BROWSING, state))
    }

    @Test
    fun `顶层落点键解析 未知或缺失为 null`() {
        assertNull(LastTopLevel.fromKey(null))
        assertNull(LastTopLevel.fromKey("bogus"))
        assertEquals(LastTopLevel.HOME, LastTopLevel.fromKey("home"))
        assertEquals(LastTopLevel.BOOKSHELF, LastTopLevel.fromKey("bookshelf"))
        assertEquals(LastTopLevel.SETTINGS, LastTopLevel.fromKey("settings"))
    }

    // ---------- 连接已不存在的兜底（票 33）----------

    @Test
    fun `启动目标指向的连接已不存在时 浏览与阅读一律回落首页`() {
        // OPDS-only 用户在 v2→v3 迁移后被清库，或用户手工删了连接：
        // 浏览页/阅读器都没有可加载的内容（浏览页只会停在「加载中…」）
        // 注：生产路径目前只对 OpenBrowser 调用本函数（见 KDoc），OpenReader 分支是防御性的 —— 这里一并锁住函数契约
        assertEquals(StartupTarget.OpenHome, fallbackWhenConnectionMissing(StartupTarget.OpenBrowser(browsing)))
        assertEquals(StartupTarget.OpenHome, fallbackWhenConnectionMissing(StartupTarget.OpenReader(book)))
    }

    @Test
    fun `不依赖连接的启动目标不受连接缺失影响`() {
        assertEquals(StartupTarget.OpenHome, fallbackWhenConnectionMissing(StartupTarget.OpenHome))
        assertEquals(StartupTarget.OpenBookshelf, fallbackWhenConnectionMissing(StartupTarget.OpenBookshelf))
        // 票 #137：顶层落点记录解析出的设置页同样不依赖连接，不许被这条兜底退化掉
        assertEquals(StartupTarget.OpenSettings, fallbackWhenConnectionMissing(StartupTarget.OpenSettings))
    }
}
