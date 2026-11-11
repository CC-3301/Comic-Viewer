package com.cc3301.comicviewer.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单页跳页的「在飞目标页」：动画期间报目标页、动画结束后（**含被取消**）回到 Pager 的真实页。
 *
 * 取消路径用 `runTest` 直接抛 [CancellationException] 驱动，不需要真动画——
 * 生产端 `animateScrollToPage` 被打断时抛的就是它。
 */
class PendingJumpTest {

    @Test
    fun `动画期间报目标页 结束后回到真实页`() = runTest {
        val jump = PendingJump()
        var during = -1
        jump.run(7) { animate ->
            during = jump.currentPage(actual = 3)
            assertEquals("动画收到的页就是目标页", 7, animate)
        }
        assertEquals("动画期间当前页就是目标页", 7, during)
        assertEquals("动画结束后回到 Pager 的真实页", 3, jump.currentPage(actual = 3))
    }

    @Test
    fun `动画被取消也要清掉标记`() = runTest {
        val jump = PendingJump()
        val thrown = runCatching {
            jump.run(7) { throw CancellationException("翻页被取代") }
        }.exceptionOrNull()
        assertTrue("取消必须照常抛出", thrown is CancellationException)
        assertEquals("取消后当前页必须回到真实页，不能长期停在目标页", 3, jump.currentPage(actual = 3))
    }
}
