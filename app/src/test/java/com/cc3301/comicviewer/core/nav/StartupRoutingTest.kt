package com.cc3301.comicviewer.core.nav

import com.cc3301.comicviewer.core.source.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 启动落地判定（票 20，spec 故事 46/47/48）：五选项 + 默认项退化分支逐条锁定。
 * 纯函数，不依赖 Android。
 */
class StartupRoutingTest {

    private val book = LastRead(connId = 1, bookId = "book-a")
    private val browsing = LastBrowsing(connId = 1, containerId = "dir-b", sortMode = SortMode.RELEASE_TIME)

    @Test
    fun `默认项 退出时正在看书 直接进阅读器续读`() {
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
    fun `上次停留的位置 恢复层级与排序 与是否在看书无关`() {
        val state = StartupState(lastRead = book, lastBrowsing = browsing, wasReading = true)
        assertEquals(StartupTarget.OpenBrowser(browsing), resolveStartupTarget(StartupPage.LAST_BROWSING, state))
    }

    @Test
    fun `阅读器选项 不看在看书标志 直接续读最近一本`() {
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
}
