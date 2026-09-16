package com.cc3301.comicviewer.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 浏览历史栈纯函数（票 09，spec 故事 37/38：层级后退/前进、前进不进阅读器） */
class BrowseHistoryTest {

    private val a = BrowseLocation(connId = 1, containerId = null)
    private val b = BrowseLocation(connId = 1, containerId = "dir-b")
    private val c = BrowseLocation(connId = 2, containerId = "dir-c")

    @Test
    fun `初始无历史`() {
        val history = BrowseHistory()
        assertNull(history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
        assertNull(history.goBack())
        assertNull(history.goForward())
    }

    @Test
    fun `记录首个位置后无后退`() {
        val history = BrowseHistory()
        history.record(a)
        assertEquals(a, history.current)
        assertFalse(history.canGoBack)
        assertNull(history.goBack())
    }

    @Test
    fun `两层之间可后退再前进`() {
        val history = BrowseHistory()
        history.record(a)
        history.record(b)
        assertEquals(b, history.current)
        assertTrue(history.canGoBack)

        assertEquals(a, history.goBack())
        assertEquals(a, history.current)
        assertTrue(history.canGoForward)

        assertEquals(b, history.goForward())
        assertEquals(b, history.current)
        assertFalse(history.canGoForward)
    }

    @Test
    fun `返回手势到达最早位置即停`() {
        val history = BrowseHistory()
        history.record(a)
        history.record(b)
        history.record(c)
        assertEquals(b, history.goBack())
        assertEquals(a, history.goBack())
        assertNull(history.goBack())          // 已到最早
        assertEquals(a, history.current)
        assertTrue(history.canGoForward)
    }

    @Test
    fun `新导航清空前进栈`() {
        val history = BrowseHistory()
        history.record(a)
        history.record(b)
        history.goBack()
        assertTrue(history.canGoForward)

        history.record(c)
        assertEquals(c, history.current)
        assertFalse(history.canGoForward)     // 标准浏览器语义
        assertNull(history.goForward())
    }

    @Test
    fun `同一位置重复导航不入栈`() {
        val history = BrowseHistory()
        history.record(a)
        history.record(a)
        assertFalse(history.canGoBack)
        assertEquals(a, history.current)
    }

    @Test
    fun `前进后再后退回到原路径`() {
        val history = BrowseHistory()
        history.record(a)
        history.record(b)
        history.record(c)

        assertEquals(b, history.goBack())
        assertEquals(a, history.goBack())
        assertEquals(b, history.goForward())
        assertEquals(c, history.goForward())
        assertFalse(history.canGoForward)
        assertEquals(b, history.goBack())
        assertEquals(b, history.current)
    }

    @Test
    fun `超过容量上限丢弃最旧项`() {
        val history = BrowseHistory(limit = 2)
        history.record(a)
        history.record(b)
        history.record(c)

        assertEquals(c, history.current)
        assertEquals(b, history.goBack())
        assertNull(history.goBack())          // a 已被丢弃
    }

    @Test
    fun `排序方式属于位置的一部分`() {
        val byName = BrowseLocation(connId = 1, containerId = null, sortMode = "name")
        val byTime = BrowseLocation(connId = 1, containerId = null, sortMode = "modified")
        val history = BrowseHistory()
        history.record(byName)
        history.record(byTime)
        assertTrue(history.canGoBack)
        assertEquals(byName, history.goBack())
    }

    @Test
    fun `清空历史`() {
        val history = BrowseHistory()
        history.record(a)
        history.record(b)
        history.clear()
        assertNull(history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
    }
}
