package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.ui.session.OpenBookRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连接入口与浏览页标题口径：首页（本地根列表 / 网络连接列表）与书柜柜列表点连接
 * 必须落到**同一目的地、同一标题口径**，抽屉高亮只在柜列表页为真。
 *
 * 三处纯函数都是 acceptance 的「可独立验证」落点：
 * - [Routes.browserRoot]：两个入口共用的目的地（书柜不再有自己的单柜路由）。
 * - [browserTitle]：根层用连接显示名、子层用条目名（两处口径一致）。
 * - [bookshelfEntrySelected]：进浏览页后抽屉「书柜」不再高亮。
 *
 * 再加一处：[browseOpenRequest] —— 浏览页开书入口那条「这次点击算不算数」的登记
 *（四条入口共用同一套，见 `ui/session/OpenBookRequests.kt`；判定本身由 `ReaderEntryRequestTest` 钉，
 * 三条 AppNav 入口用的 `beginGuard` 由 `OpenBookEntryTest` 的「守卫登记」用例钉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseEntryPointTest {
    // ---------- 目的地 ----------

    @Test
    fun `书柜与首页点连接落到同一路由 = 浏览根层`() {
        // 书柜走的那个函数必须与首页路径写出来的串完全一致（同一路由、同一屏）
        assertEquals(Routes.browser(7L, null), Routes.browserRoot(7L))
        assertEquals("browser/7?container=&name=", Routes.browserRoot(7L))
    }

    @Test
    fun `根层路由的 container 参数为空 与子层可区分`() {
        assertTrue(Routes.browserRoot(7L).startsWith("browser/7"))
        assertFalse(Routes.browser(7L, "sub").isEmpty())
        assertEquals("browser/7?container=sub&name=", Routes.browser(7L, "sub"))
    }

    @Test
    fun `子层路由带上条目名 名字也走百分号编码`() {
        // A 案：名字随路由带（进目录时写进 route 参数），进程重建后直接用它
        assertEquals(
            "browser/7?container=dir-sub&name=%E7%AC%AC3%E8%AF%9D",
            Routes.browser(7L, "dir-sub", "第3话"),
        )
        // 名字里的 `&` / `?` 不能把参数切开（与 container 同一套编码）
        assertEquals("browser/7?container=dir-sub&name=a%26b%3Fc", Routes.browser(7L, "dir-sub", "a&b?c"))
    }

    // ---------- 标题口径 ----------

    @Test
    fun `根层标题用连接显示名`() {
        assertEquals(
            "Share @ 192.0.2.200",
            browserTitle(containerId = null, routeName = null, cachedName = null, connectionName = "Share @ 192.0.2.200"),
        )
    }

    @Test
    fun `根层取不到连接名时兜底浏览`() {
        // 连接还在查询中（首帧）或连接已被删除时的过渡帧：给个中性标题而不是空白
        assertEquals("浏览", browserTitle(containerId = null, routeName = null, cachedName = null, connectionName = null))
        assertEquals("浏览", browserTitle(containerId = null, routeName = null, cachedName = null, connectionName = "   "))
    }

    @Test
    fun `子层标题仍是该目录条目名`() {
        assertEquals(
            "第1话",
            browserTitle(containerId = "content://doc/sub", routeName = null, cachedName = "第1话", connectionName = "Share"),
        )
    }

    @Test
    fun `子层条目名缺失时退回 id 末段`() {
        assertEquals(
            "sub",
            browserTitle(
                containerId = "content://com.android.externalstorage.documents/tree/x/document/sub",
                routeName = null,
                cachedName = null,
                connectionName = "Share",
            ),
        )
    }

    @Test
    fun `子层标题不受连接名影响`() {
        // 子层即使连接名可用也必须用目录条目名（而不是跟着根层一起变成连接名）
        assertEquals(
            "第2话",
            browserTitle(
                containerId = "smb://host/share/第2话",
                routeName = null,
                cachedName = "第2话",
                connectionName = "Share @ 192.0.2.200",
            ),
        )
    }

    @Test
    fun `子层标题优先用路由带回来的名字 缓存空也不吃 id 末段`() {
        // 进程重建（退出 APP 再回来）后条目名缓存是空的，而这次恢复不经过父层枚举，
        // 兜底链于是吃到 id 末段——Komga 的容器 id 末段是服务端随机 id，标题表现成「一串英文」。
        // 名字随路由带回来后（进目录时写进 route 参数），这一层既不看会话内存缓存也不打网络。
        assertEquals(
            "第3话",
            browserTitle(
                containerId = "komga://<host>/series/7f3c1d2e",
                routeName = "第3话",
                cachedName = null,
                connectionName = "Komga",
            ),
        )
    }

    @Test
    fun `路由没带名字时退回会话缓存里的条目名`() {
        // 兜底链的顺序：路由带回来的名字 → 会话内回填的条目名 → id 末段 → 「浏览」
        assertEquals(
            "第3话",
            browserTitle(
                containerId = "komga://<host>/series/7f3c1d2e",
                routeName = null,
                cachedName = "第3话",
                connectionName = "Komga",
            ),
        )
    }

    @Test
    fun `路由带回来的名字为空串时不吃它 仍走后面的来源`() {
        // 根层以外的层没带名字时路由参数是空串（不是 null）：空串与「没名字」同义，不能当成名字渲染
        assertEquals(
            "第3话",
            browserTitle(
                containerId = "komga://<host>/series/7f3c1d2e",
                routeName = "   ",
                cachedName = "第3话",
                connectionName = "Komga",
            ),
        )
    }

    // ---------- 抽屉高亮 ----------

    @Test
    fun `抽屉书柜高亮只在柜列表页`() {
        assertTrue(bookshelfEntrySelected(Routes.BOOKSHELF))
        // 进浏览页后与首页路径一致（不高亮）：界面已经不是柜列表
        assertFalse(bookshelfEntrySelected(Routes.browserRoot(7L)))
        assertFalse(bookshelfEntrySelected(Routes.HOME))
        assertFalse(bookshelfEntrySelected(Routes.SETTINGS))
        assertFalse(bookshelfEntrySelected(Routes.READER))
        assertFalse(bookshelfEntrySelected(null))
    }

    // ---------- 浏览页开书守卫（第四入口的登记也接到可单测的接缝上） ----------

    /** 会话级的开书请求模块（生产那份由组合根持有）：每个用例现造一份，两个计数器互不串 */
    private fun requests() = OpenBookRequests()

    @Test
    fun `浏览页开书守卫：组合存活且仍是当前那次点击才算数`() {
        val nav = navHostWith(listOf(Routes.HOME))

        assertTrue(browseOpenRequest(requests(), nav, { true }, "root/a").guard.isCurrent())
    }

    @Test
    fun `浏览页开书守卫：被后一次点击顶替的那次不算数`() {
        // 连点另一本：后一次自己会导航，前一次不再算数
        val nav = navHostWith(listOf(Routes.HOME))
        val requests = requests()
        val first = browseOpenRequest(requests, nav, { true }, "root/a")
        val second = browseOpenRequest(requests, nav, { true }, "root/b")

        assertFalse("被顶替的那次不导航", first.guard.isCurrent())
        assertTrue("只有最新那次算数", second.guard.isCurrent())
    }

    @Test
    fun `浏览页开书守卫：连点同一本书两次也各领一条请求`() {
        // effect 的键是**请求对象**，不是「当前要开的那一本」的书 id 值：连点同一本书时值不变，
        // 旧口径下 effect 不重跑（后一次点击被当成同一次）；现在每次点击领一条新的 ⇒ 重跑一次，
        // 净结果同样是一次导航（前一条已过期，为假就不导航）。
        val nav = navHostWith(listOf(Routes.HOME))
        val requests = requests()
        val first = browseOpenRequest(requests, nav, { true }, "root/a")
        val second = browseOpenRequest(requests, nav, { true }, "root/a")

        assertNotEquals("两次点击是两个请求对象（键变了 ⇒ effect 重跑一次）", first, second)
        assertFalse("前一条已过期", first.guard.isCurrent())
        assertTrue("只有最新那条算数", second.guard.isCurrent())
    }

    @Test
    fun `浏览页开书守卫：这一屏已经离开不算数`() {
        // 组合存活标志（点了就返回 / 切走）：导航发生在点击那一帧，这一道防的是「这次还算不算数」
        val nav = navHostWith(listOf(Routes.HOME))
        val requests = requests()

        assertFalse(browseOpenRequest(requests, nav, { false }, "root/a").guard.isCurrent())

        browseOpenRequest(requests, nav, { true }, "root/a")
        assertFalse(browseOpenRequest(requests, nav, { false }, "root/b").guard.isCurrent())
    }
}
