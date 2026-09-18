package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「上次退出时是否停在阅读器」的写点守卫（票 26 r3 修正 A，纯函数）。
 *
 * 启动判定读的是**上一会话**落盘的 `was_reading`；写点若在中转页（或路由未定的那一帧）就执行，
 * 慢来源冷启动期间会把上一会话的 true 覆盖成 false，旋转屏幕/进程被杀后的补跑就再也读不到
 * 「上次正在看书」（故事 47 直接打开那本书的语义丢失）。把写点限定在已离开中转页的路由上，
 * 读与写的先后是结构性保证，不依赖 effect 的启动顺序。
 */
class StartupReadingFlagTest {

    @Test
    fun `中转页与路由未定的那一帧不写 was_reading`() {
        assertNull("STARTUP 中转页：判定期间绝不能写", readingFlagToRecord(Routes.STARTUP))
        assertNull("路由为 null 的那一帧同样不写", readingFlagToRecord(null))
    }

    @Test
    fun `离开中转页后按当前路由落盘 阅读器为真 其余为假`() {
        assertEquals(true, readingFlagToRecord(Routes.READER))
        assertEquals(false, readingFlagToRecord(Routes.HOME))
        assertEquals(false, readingFlagToRecord(Routes.LOCAL_ROOTS))
        assertEquals(false, readingFlagToRecord(Routes.BOOKSHELF))
        assertEquals(false, readingFlagToRecord(Routes.BROWSER))
        assertEquals(false, readingFlagToRecord(Routes.SETTINGS))
        assertEquals(false, readingFlagToRecord(Routes.CONNS))
    }
}
