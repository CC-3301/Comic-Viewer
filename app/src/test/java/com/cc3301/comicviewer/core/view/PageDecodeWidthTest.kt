package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页面解码目标宽度（票 #108 r3，评审 P2）：**唯一出处**，浏览页前置与阅读页都调它。
 *
 * AC2「切过去首帧就是图」依赖两条路拿到**同一个整数**（宽度写进 `PageDecoder` 的解码缓存键，差 1px 就不命中）。
 * 以前两处各写一次 `toInt()`：浏览器侧取 `LocalView.current.width`（Int），阅读页取 `maxWidth.toPx()`（Float）——
 * JVM 测不了「真机上两个来源是否相等」，但能钉住**换算本身只有一套口径**：同一宽度在两条路上得到同一个值，
 * 且不会因为「一个走 Int、一个走 Float」而分叉。
 */
class PageDecodeWidthTest {

    @Test
    fun `同一宽度只有一种结果`() {
        assertEquals("整窗宽（View 侧）", 1080, pageDecodeWidthPx(1080f))
        assertEquals("同一个宽度从 dp 换算来的 px（阅读页侧）也是同一个值", 1080, pageDecodeWidthPx(1080.0f))
        assertEquals("小数部分按截断（口径只有一处）", 1080, pageDecodeWidthPx(1080.99f))
        assertEquals(1079, pageDecodeWidthPx(1079.4f))
    }

    @Test
    fun `非正数兜底成 1`() {
        // 布局首帧可能给出 0 宽：0 进解码缓存键会让「同一页」在布局前后变成两个键
        assertEquals(1, pageDecodeWidthPx(0f))
        assertEquals(1, pageDecodeWidthPx(-12f))
    }
}
