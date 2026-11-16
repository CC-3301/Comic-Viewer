package com.cc3301.comicviewer.core.touch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底边系统手势带判定（spec §底边系统手势带）：
 * 带贴着屏幕底边、带高从底边向上量；按下点的纵坐标从顶边向下量。
 * 落在带内（含带的上边界）的按下不算点击，与横坐标落在哪个触摸区无关。
 */
class BottomGestureStripTest {

    @Test
    fun `带内按下不算点击 两种屏高一致`() {
        // 2400px 高、带 48px：y=2352 起落在带内
        assertTrue(inBottomGestureStrip(2352f, 2400f, 48f))
        assertTrue(inBottomGestureStrip(2399f, 2400f, 48f))
        // 800px 高、带 48px：同一口径从底边量出带
        assertTrue(inBottomGestureStrip(752f, 800f, 48f))
        assertTrue(inBottomGestureStrip(799f, 800f, 48f))
    }

    @Test
    fun `带的上边界算带内 再往上一像素算带外`() {
        assertTrue("带的上边界本身属于带内", inBottomGestureStrip(2352f, 2400f, 48f))
        assertFalse("带的上边界之上 1px 已带外", inBottomGestureStrip(2351f, 2400f, 48f))
    }

    @Test
    fun `带外按下照旧算点击`() {
        assertFalse(inBottomGestureStrip(0f, 2400f, 48f))
        assertFalse(inBottomGestureStrip(1200f, 2400f, 48f))
    }

    @Test
    fun `没有手势带时一律带外`() {
        // 三键导航：系统手势区底部 inset = 0
        assertFalse(inBottomGestureStrip(0f, 2400f, 0f))
        assertFalse(inBottomGestureStrip(2399f, 2400f, 0f))
    }
}
