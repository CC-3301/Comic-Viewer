package com.cc3301.comicviewer.ui

import com.cc3301.comicviewer.core.nav.LastTopLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「顶层落点记录」的写点守卫（票 #137，纯函数）。
 *
 * 现象（维护者真机验收 #111 r7/r8 时顺带发现）：在首页退出 APP、重开却落到「带封面的目录列表」——
 * 全仓没有任何写点记录「用户已经离开浏览层、停在首页/书柜了」，于是「上次阅读的位置」（不在阅读器时）
 * 与「上次停留的位置」都回落到很久以前那个浏览目录。
 *
 * 写点只认三类路由：首页/书柜/设置（记下该顶层路由）、浏览层与阅读器（清掉——位置由「上次停留的位置」说话），
 * 其余路由一律不动记录。阅读器的「清」尤其关键：从顶层路由经抽屉进阅读器时，不清就会让
 * 「上次停留的位置」在阅读器里退出后落到那个顶层路由（改前口径是落回上次停留的浏览目录）。
 */
class TopLevelStopRecordTest {

    @Test
    fun `首页书柜设置 记下该顶层路由`() {
        assertEquals(TopLevelRecord.At(LastTopLevel.HOME), topLevelRecordFor(Routes.HOME))
        assertEquals(TopLevelRecord.At(LastTopLevel.BOOKSHELF), topLevelRecordFor(Routes.BOOKSHELF))
        assertEquals(TopLevelRecord.At(LastTopLevel.SETTINGS), topLevelRecordFor(Routes.SETTINGS))
    }

    @Test
    fun `浏览层与阅读器都清掉顶层落点记录`() {
        // 进了浏览层，位置就该由「上次停留的位置」说话；留着旧顶层记录会让重启落到早就不在的首页/书柜
        assertEquals(TopLevelRecord.Clear, topLevelRecordFor(Routes.BROWSER))
        // 阅读器（票 #137 收口，评审 spec Finding 1）：从首页/书柜/设置经抽屉进阅读器时，那一帧已把记录写成
        // 顶层路由；不清的话「上次停留的位置」在阅读器里退出会落到那个顶层路由，而改前口径是落回上次停留的浏览目录。
        // 清只清顶层键：`lastBrowsing` 要留给开书失败的兜底（见 `resolveStartupRead`）。
        assertEquals(TopLevelRecord.Clear, topLevelRecordFor(Routes.READER))
    }

    @Test
    fun `其余路由不动记录`() {
        // 中转页与路由未定的那一帧：启动判定要读的正是上一会话落下的值（同 readingFlagToRecord 的守卫）
        assertNull(topLevelRecordFor(Routes.STARTUP))
        assertNull(topLevelRecordFor(null))
        // 来源列表这类中层界面不是「顶层落点」：不动记录，重启落到上一次真正停过的顶层路由
        assertNull(topLevelRecordFor(Routes.LOCAL_ROOTS))
        assertNull(topLevelRecordFor(Routes.CONNS))
    }
}
