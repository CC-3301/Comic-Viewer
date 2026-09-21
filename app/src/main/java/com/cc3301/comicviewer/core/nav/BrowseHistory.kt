package com.cc3301.comicviewer.core.nav

/**
 * 浏览层级中的一个位置（spec 故事 37/38）：位置身份只有连接 + 容器。
 * 排序不属于位置——排序方式与方向是全 app 一份的全局设置（票 #29）。
 * 阅读器不进历史 —— 前进永远回到浏览位置，不会回到阅读器。
 */
data class BrowseLocation(
    val connId: Long,
    val containerId: String?,
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

    /**
     * 当前路径（栈底 → 当前层，票 #70 r2）：会话结束（Activity finish）时落盘，重启后按它逐级重建返回路径。
     * 前进栈里的位置不在路径里——它不在回退栈上，也不是用户现在所处的位置。
     * 本栈是**回退栈里浏览层的镜像**，由 `AppNav.syncBrowseHistory` 重建（票 #70 r3）。
     */
    fun path(): List<BrowseLocation> = backStack.toList()

    /**
     * 把回退部分换成 [path]（栈底 → 栈顶，票 #70 r3）：浏览历史不再是「谁导航谁记一笔」的独立结构，
     * 而是**回退栈里实际浏览层**的镜像——唯一的事实来源是回退栈（`AppNav.browseLayersOnStack`）。
     *
     * 前进栈（鼠标前进侧键）**保留**：它记的是回退栈上已经被弹掉的位置，与回退部分无关；
     * 会话切换那类要作废前进栈的场合由 [clear] 负责（`resetBrowseHistoryForStartup` 先清再写）。
     */
    fun syncPath(path: List<BrowseLocation>) {
        if (path == backStack.toList()) return
        backStack.clear()
        backStack.addAll(path.takeLast(limit))
    }

    val canGoBack: Boolean get() = backStack.size >= 2

    /**
     * 与 [canGoBack] 成对的历史可用性查询：还有可前进的位置。
     * 生产代码只用 [goForward] 判空（前进侧键返回 false 就交回系统），本 getter 供可用性查询与测试使用。
     */
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

    /**
     * 作废前进栈（票 #70 r3）：[syncPath] 特意保留前进栈，所以「导航到新位置要清掉前进历史」这条
     * 标准浏览器语义（与 [record] 一致）由导航侧显式声明——只有真实的浏览层导航才清，
     * 返回一层后浏览页显示时那次镜像同步不清（否则鼠标前进侧键会在返回后立刻失效）。
     */
    fun clearForward() {
        forwardStack.clear()
    }
}
