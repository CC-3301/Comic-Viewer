package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳页滑动条手势状态（票 #63）：点击轨道与拖动必须给出同一个跳页目标。
 *
 * 回归的失败模式：Material3 的点击轨道路径在同一次手势回调里依次调用 `onValueChange` →
 * `onValueChangeFinished`（`SliderState.dispatchRawDelta(0f)` 之后 `gestureEndAction()`），
 * **两次调用之间不发生重新组合**。旧实现把跳页目标放在组合期算出的局部量里，点击时它还是点击前的页，
 * `onSeek(旧页)` → 页面不跳、预览不动（拖动跨多帧、有重新组合，所以一直是对的）。
 * 因此这里用「值变化 → 手势结束」的调用序列（中间不重新组合）把点击路径钉住。
 */
class ReaderSeekBarTest {

    @Test
    fun `点击轨道 抬手前那次值变化就是跳页目标`() {
        val bar = ReaderSeekBar(initialPage = 10, pageCount = 200)
        bar.onValueChange(150f) // 点击路径只有这一次值变化，之后直接是手势结束
        assertEquals("点击必须跳到该值对应的页，不是点击前的页", 150, bar.onGestureFinished())
    }

    @Test
    fun `拖动多帧 松手跳到最后一帧的值对应的页`() {
        val bar = ReaderSeekBar(initialPage = 10, pageCount = 200)
        for (page in 20..40) bar.onValueChange(page.toFloat())
        assertEquals(40, bar.onGestureFinished())
    }

    @Test
    fun `拖动中预览跟随滑块 手势结束后不再跟随`() {
        val bar = ReaderSeekBar(initialPage = 10, pageCount = 200)
        assertFalse("手势未开始时预览跟随当前页", bar.dragging)
        bar.onValueChange(42.6f)
        assertTrue("值变化即进入手势中", bar.dragging)
        assertEquals("预览跟随目标页（四舍五入）", 43, bar.targetPage)
        bar.onGestureFinished()
        assertFalse("抬手后手势结束，预览回到当前页", bar.dragging)
    }

    @Test
    fun `滑块值越界仍夹在首末页`() {
        val bar = ReaderSeekBar(initialPage = 10, pageCount = 200)
        bar.onValueChange(500f)
        assertEquals(199, bar.onGestureFinished())
        bar.onValueChange(-12f)
        assertEquals(0, bar.onGestureFinished())
    }

    @Test
    fun `单页书点击轨道不会跳到第二页`() {
        val bar = ReaderSeekBar(initialPage = 0, pageCount = 1)
        bar.onValueChange(1f)
        assertEquals(0, bar.onGestureFinished())
    }

    @Test
    fun `菜单外翻页让滑块跟上 拖动中不回写`() {
        val bar = ReaderSeekBar(initialPage = 10, pageCount = 200)
        bar.syncToPage(30)
        assertEquals(30f, bar.value, 0.001f)
        bar.onValueChange(90f)
        bar.syncToPage(5) // 拖动中：音量键/滚轮翻页不得把滑块闪回旧页
        assertEquals(90f, bar.value, 0.001f)
        assertEquals(90, bar.onGestureFinished())
    }

    @Test
    fun `初值越界时夹回合法页`() {
        assertEquals(199, ReaderSeekBar(initialPage = 999, pageCount = 200).targetPage)
        assertEquals(0, ReaderSeekBar(initialPage = -3, pageCount = 200).targetPage)
        assertEquals(0, ReaderSeekBar(initialPage = 0, pageCount = 0).targetPage)
    }
}
