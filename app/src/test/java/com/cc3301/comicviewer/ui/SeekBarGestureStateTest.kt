package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳页滑动条手势状态（票 #63）：钉住 [SeekBarGestureState] 自身的「值 → 页」口径与「预览以哪一页为中心」。
 *
 * 覆盖范围说明：本用例直接驱动状态持有者，**不经过** `ReaderMenu` 的 Compose 接线（`Slider` 的三个参数）；
 * 接线退回「取组合期旧值」的写法时本用例仍全绿——那部分按 `docs/SPEC.md` 的手动验收清单在真机上覆盖。
 * 成因（Material3 点击轨道的调用序列，未在真机复核）见 [SeekBarGestureState] 的说明。
 */
class SeekBarGestureStateTest {

    @Test
    fun `点击轨道 抬手前那次值变化就是跳页目标`() {
        val bar = SeekBarGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(150f) // 点击路径只有这一次值变化，之后直接是手势结束
        assertEquals("点击必须跳到该值对应的页，不是点击前的页", 150, bar.onGestureFinished())
    }

    @Test
    fun `拖动多帧 松手跳到最后一帧的值对应的页`() {
        val bar = SeekBarGestureState(initialPage = 10, pageCount = 200)
        for (page in 20..40) bar.onValueChange(page.toFloat())
        assertEquals(40, bar.onGestureFinished())
    }

    @Test
    fun `拖动中预览跟随滑块`() {
        val bar = SeekBarGestureState(initialPage = 10, pageCount = 200)
        assertFalse("手势未开始", bar.gestureActive)
        assertEquals("没有未落地的目标时预览跟当前页", 10, bar.previewTarget(currentPage = 10))
        bar.onValueChange(42.6f)
        assertTrue("值变化即进入手势中（点击轨道那一次也一样）", bar.gestureActive)
        assertEquals("目标页四舍五入", 43, bar.targetPage)
        assertEquals("手势中预览跟滑块", 43, bar.previewTarget(currentPage = 10))
        bar.onGestureFinished()
        assertFalse("抬手后手势结束", bar.gestureActive)
    }

    @Test
    fun `点击后跳页还没落地 预览已经以点击页为中心`() {
        val bar = SeekBarGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(150f)
        bar.onGestureFinished()
        // 生产接线里 currentPage 要等 goTo 落地才变：此刻预览必须已经跟到点击位置对应的页
        assertEquals(150, bar.previewTarget(currentPage = 10))
    }

    @Test
    fun `跳页落地后预览仍以该页为中心`() {
        val bar = SeekBarGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(150f)
        bar.onGestureFinished()
        assertEquals(150, bar.previewTarget(currentPage = 150))
    }

    @Test
    fun `拖回当前页松手 预览就是当前页`() {
        val bar = SeekBarGestureState(initialPage = 40, pageCount = 200)
        bar.onValueChange(90f)
        bar.onValueChange(40f)
        assertEquals(40, bar.onGestureFinished())
        assertEquals("目标页与当前页相同 → 跟当前页", 40, bar.previewTarget(currentPage = 40))
    }

    @Test
    fun `滑块值越界仍夹在首末页`() {
        val bar = SeekBarGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(500f)
        assertEquals(199, bar.onGestureFinished())
        bar.onValueChange(-12f)
        assertEquals(0, bar.onGestureFinished())
    }

    @Test
    fun `单页书点击轨道不会跳到第二页`() {
        val bar = SeekBarGestureState(initialPage = 0, pageCount = 1)
        bar.onValueChange(1f)
        assertEquals(0, bar.onGestureFinished())
        assertEquals(0, bar.previewTarget(currentPage = 0))
    }

    @Test
    fun `菜单外翻页让滑块跟上 手势中不回写`() {
        val bar = SeekBarGestureState(initialPage = 10, pageCount = 200)
        bar.syncToPage(30)
        assertEquals(30f, bar.value, 0.001f)
        assertEquals("翻页落地后预览跟当前页", 30, bar.previewTarget(currentPage = 30))
        bar.onValueChange(90f)
        bar.syncToPage(5) // 手势中：音量键/滚轮翻页不得把滑块闪回旧页
        assertEquals(90f, bar.value, 0.001f)
        assertEquals(90, bar.onGestureFinished())
    }

    @Test
    fun `初值越界时夹回合法页`() {
        assertEquals(199, SeekBarGestureState(initialPage = 999, pageCount = 200).targetPage)
        assertEquals(0, SeekBarGestureState(initialPage = -3, pageCount = 200).targetPage)
        assertEquals(0, SeekBarGestureState(initialPage = 0, pageCount = 0).targetPage)
    }
}
