package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连接入口与浏览页标题口径（票 #49）：首页（本地根列表 / 网络连接列表）与书柜柜列表点连接
 * 必须落到**同一目的地、同一标题口径**，抽屉高亮只在柜列表页为真。
 *
 * 三处纯函数都是票 #49 acceptance 的「可独立验证」落点：
 * - [Routes.browserRoot]：两个入口共用的目的地（书柜不再有自己的单柜路由）。
 * - [browserTitle]：根层用连接显示名、子层用条目名（两处口径一致）。
 * - [bookshelfEntrySelected]：进浏览页后抽屉「书柜」不再高亮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseEntryPointTest {
    // ---------- 目的地 ----------

    @Test
    fun `书柜与首页点连接落到同一路由 = 浏览根层`() {
        // 书柜走的那个函数必须与首页路径写出来的串完全一致（票 #49：同一路由、同一屏）
        assertEquals(Routes.browser(7L, null), Routes.browserRoot(7L))
        assertEquals("browser/7?container=", Routes.browserRoot(7L))
    }

    @Test
    fun `根层路由的 container 参数为空 与子层可区分`() {
        assertTrue(Routes.browserRoot(7L).startsWith("browser/7"))
        assertFalse(Routes.browser(7L, "sub").isEmpty())
        assertEquals("browser/7?container=sub", Routes.browser(7L, "sub"))
    }

    // ---------- 标题口径 ----------

    @Test
    fun `根层标题用连接显示名`() {
        assertEquals(
            "Share @ 10.10.10.200",
            browserTitle(containerId = null, containerName = null, connectionName = "Share @ 10.10.10.200"),
        )
    }

    @Test
    fun `根层取不到连接名时兜底浏览`() {
        // 连接还在查询中（首帧）或连接已被删除时的过渡帧：给个中性标题而不是空白
        assertEquals("浏览", browserTitle(containerId = null, containerName = null, connectionName = null))
        assertEquals("浏览", browserTitle(containerId = null, containerName = null, connectionName = "   "))
    }

    @Test
    fun `子层标题仍是该目录条目名`() {
        assertEquals(
            "第1话",
            browserTitle(containerId = "content://doc/sub", containerName = "第1话", connectionName = "Share"),
        )
    }

    @Test
    fun `子层条目名缺失时退回 id 末段`() {
        assertEquals(
            "sub",
            browserTitle(
                containerId = "content://com.android.externalstorage.documents/tree/x/document/sub",
                containerName = null,
                connectionName = "Share",
            ),
        )
    }

    @Test
    fun `子层标题不受连接名影响`() {
        // 子层即使连接名可用也必须用目录条目名（而不是跟着根层一起变成连接名）
        assertEquals(
            "第2话",
            browserTitle(containerId = "smb://host/share/第2话", containerName = "第2话", connectionName = "Share @ 10.10.10.200"),
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
}
