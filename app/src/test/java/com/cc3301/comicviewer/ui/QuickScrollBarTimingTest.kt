package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条的**动画口径**（票 #60 批次 6 定版 **D7-A** 的 AC15/AC16）：
 * 出现淡入 **120ms**；滚动中与按住滑条期间**一直可见且不计时**（不重排计时）；
 * 静止 **1.2s** 后**只淡出一次**、**200ms**。
 *
 * 为什么要抽 [quickScrollBarTimerArmed]：真机上的「明灭/抽搐」根因是**两条时间线**——每次滚动事件都重新
 * 开始淡入，同时上一次的 1.2s 计时仍在跑，于是滚动中也会熄一次。修法是把「何时计时」收成一条判定：
 * **只有「没滚动也没按住」才计时**（滚动/按住一开始就把这趟计时取消，见 `ui/QuickScrollBar.kt` 的
 * `busy` 键）。判定是纯函数，因此在这里钉住；Compose 侧的 `animateFloatAsState` 淡入淡出在单测里起不了帧
 * （同 [QuickScrollBarSizeTest] 的限制：仓库没有 compose-ui-test 假时钟基建），长短由常量钉、接线由真机验收覆盖。
 *
 * 判别力：淡入/淡出走回旧值（100ms / 250ms）、静止时长走回旧语义（滚动中也计时，即 `armed` 在滚动中为 true）
 * 都变红。
 */
class QuickScrollBarTimingTest {

    /** AC16：出现动画 120ms 淡入（旧值 100ms，批次 6 定版） */
    @Test
    fun `淡入是 120ms`() {
        assertEquals(120, QUICK_SCROLL_BAR_FADE_IN_MS)
    }

    /** AC15：淡出 200ms、只执行一次（旧值 250ms，批次 6 定版） */
    @Test
    fun `淡出是 200ms`() {
        assertEquals(200, QUICK_SCROLL_BAR_FADE_OUT_MS)
    }

    /** AC15/AC16：静止 1.2s 后淡出（倒计时只由「静止」触发） */
    @Test
    fun `静止 1_2 秒后淡出`() {
        assertEquals(1200L, QUICK_SCROLL_BAR_HIDE_DELAY_MS)
    }

    /** AC15：滚动中不计时（保持可见；旧实现在滚动中也跑计时，就是「明灭」的来源） */
    @Test
    fun `滚动中不计时`() {
        assertFalse(quickScrollBarTimerArmed(scrolling = true, held = false))
    }

    /** AC16：按住滑条期间不计时（松手后按「静止 1.2s」重新开始） */
    @Test
    fun `按住期间不计时`() {
        assertFalse(quickScrollBarTimerArmed(scrolling = false, held = true))
    }

    /** 没滚动也没按住：这是唯一会计时的状态 */
    @Test
    fun `没滚动也没按住才计时`() {
        assertTrue(quickScrollBarTimerArmed(scrolling = false, held = false))
    }
}
