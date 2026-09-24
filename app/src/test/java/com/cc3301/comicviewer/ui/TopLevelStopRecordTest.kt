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
 * 写点只认三类路由：首页/书柜/设置（记下该顶层路由）、浏览层（清掉——此刻位置由「上次停留的位置」说话），
 * 其余路由一律不动记录（阅读器尤其不能动：`lastBrowsing` 还要给开书失败兜底用，见 [resolveStartupRead]）。
 */
class TopLevelStopRecordTest {

    @Test
    fun `首页书柜设置 记下该顶层路由`() {
        assertEquals(TopLevelRecord.At(LastTopLevel.HOME), topLevelRecordFor(Routes.HOME))
        assertEquals(TopLevelRecord.At(LastTopLevel.BOOKSHELF), topLevelRecordFor(Routes.BOOKSHELF))
        assertEquals(TopLevelRecord.At(LastTopLevel.SETTINGS), topLevelRecordFor(Routes.SETTINGS))
    }

    @Test
    fun `浏览层清掉顶层落点记录`() {
        // 进了浏览层，位置就该由「上次停留的位置」说话；留着旧顶层记录会让重启落到早就不在的首页/书柜
        assertEquals(TopLevelRecord.Clear, topLevelRecordFor(Routes.BROWSER))
    }

    @Test
    fun `其余路由不动记录`() {
        // 中转页与路由未定的那一帧：启动判定要读的正是上一会话落下的值（同 readingFlagToRecord 的守卫）
        assertNull(topLevelRecordFor(Routes.STARTUP))
        assertNull(topLevelRecordFor(null))
        // 阅读器：进阅读器不许动任何落盘状态（`lastBrowsing` 还要给开书失败兜底用）
        assertNull(topLevelRecordFor(Routes.READER))
        // 来源列表这类中层界面不是「顶层落点」：不动记录，重启落到上一次真正停过的顶层路由
        assertNull(topLevelRecordFor(Routes.LOCAL_ROOTS))
        assertNull(topLevelRecordFor(Routes.CONNS))
    }
}
