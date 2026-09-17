package com.cc3301.comicviewer.core.nav

import com.cc3301.comicviewer.core.source.SortMode

/**
 * 浏览层级中的一个位置（spec 故事 37/38）。
 * 阅读器不进历史 —— 前进永远回到浏览位置，不会回到阅读器。
 */
data class BrowseLocation(
    val connId: Long,
    val containerId: String?,
    val sortMode: SortMode = SortMode.NAME,
)

/**
 * 上次阅读位置（票 09）：带来源连接 id。
 * 书 id 只在某个连接内有效（SAF 文档 URI），跨连接直接打开会失败（review P1-1）。
 */
data class LastRead(val connId: Long, val bookId: String)

/**
 * 浏览历史栈：支持浏览层级之间的后退/前进
 * （全面屏返回手势 = 后退；鼠标侧键在票 16 绑定同一套 API）。
 *
 * - [record] 只由「用户向新位置导航」调用；后退/前进不重复记录，因此前进栈不会被自己清空
 * - 记录新位置会清空前进栈（标准浏览器语义）
 * - 超过容量上限时丢弃最旧项
 */
class BrowseHistory(private val limit: Int = 50) {

    private val backStack = ArrayDeque<BrowseLocation>()
    private val forwardStack = ArrayDeque<BrowseLocation>()

    /** 当前位置（后退栈顶） */
    val current: BrowseLocation? get() = backStack.lastOrNull()

    val canGoBack: Boolean get() = backStack.size >= 2
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /** 记录一个新浏览位置（同一位置重复导航不入栈） */
    fun record(location: BrowseLocation) {
        if (backStack.lastOrNull() == location) return
        backStack.addLast(location)
        forwardStack.clear()
        while (backStack.size > limit) backStack.removeFirst()
    }

    /** 后退一步；返回新的当前位置（已到最早位置时返回 null） */
    fun goBack(): BrowseLocation? {
        if (backStack.size < 2) return null
        forwardStack.addLast(backStack.removeLast())
        return backStack.last()
    }

    /** 前进一步；返回新的当前位置（无前进历史时返回 null） */
    fun goForward(): BrowseLocation? {
        val next = forwardStack.removeLastOrNull() ?: return null
        backStack.addLast(next)
        return next
    }

    fun clear() {
        backStack.clear()
        forwardStack.clear()
    }
}
