package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳页滑动条手势状态（票 #63）：钉住 [SliderGestureState] 自身的「值 → 页」口径与「预览以哪一页为中心」。
 *
 * 覆盖范围说明：本用例直接驱动状态持有者，**不经过** `ReaderMenu` 的 Compose 接线（`Slider` 的三个参数）；
 * 接线退回「取组合期旧值」的写法时本用例仍全绿——那部分按 `docs/SPEC.md` 的手动验收清单在真机上覆盖。
 * 成因（Material3 点击轨道的调用序列，未在真机复核）见 [SliderGestureState] 的说明。
 *
 * 票 #105 AC9（点滑动条行任意位置都跳到对应页）在本文件里有**两条**口径：
 * ① 滑块**值** → 页（[SliderGestureState.onValueChange] / `ReaderMenuLayout.seekTargetPage`）——
 *    由 Material3 自己换算值的那条路径用；
 * ② 按下**比例** → 页（[SliderGestureState.onTapFraction]）——`SeekSlider` 自接的点按手势用。
 * 为什么不给 ② 写 Robolectric 端到端触摸用例：同一套用例整套跑时，指针事件在这套 Harness 里
 * **时好时坏**（单跑本类能过、整套跑会丢按下），那种用例是「会闪的门」——因此把可判定的部分
 * 收在纯状态层，端到端留给真机验收（详见 evidence-impl.md）。
 */
class SliderGestureStateTest {

    @Test
    fun `点击轨道 抬手前那次值变化就是跳页目标`() {
        val bar = SliderGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(150f) // 点击路径只有这一次值变化，之后直接是手势结束
        assertEquals("点击必须跳到该值对应的页，不是点击前的页", 150, bar.onGestureFinished())
    }

    @Test
    fun `拖动多帧 松手跳到最后一帧的值对应的页`() {
        val bar = SliderGestureState(initialPage = 10, pageCount = 200)
        for (page in 20..40) bar.onValueChange(page.toFloat())
        assertEquals(40, bar.onGestureFinished())
    }

    @Test
    fun `拖动中预览跟随滑块`() {
        val bar = SliderGestureState(initialPage = 10, pageCount = 200)
        assertFalse("手势未开始", bar.gestureActive)
        assertEquals("没有未落地的目标时预览就是滑块所在页", 10, bar.previewTarget)
        bar.onValueChange(42.6f)
        assertTrue("值变化即进入手势中（点击轨道那一次也一样）", bar.gestureActive)
        assertEquals("目标页四舍五入", 43, bar.targetPage)
        assertEquals("手势中预览跟滑块", 43, bar.previewTarget)
        bar.onGestureFinished()
        assertFalse("抬手后手势结束", bar.gestureActive)
    }

    @Test
    fun `点击后跳页还没落地 预览已经以点击页为中心`() {
        val bar = SliderGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(150f)
        bar.onGestureFinished()
        // 生产接线里 currentPage 要等 goTo 落地才变：此刻预览必须已经跟到点击位置对应的页
        assertEquals(150, bar.previewTarget)
    }

    @Test
    fun `跳页落地后预览仍以该页为中心`() {
        val bar = SliderGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(150f)
        bar.onGestureFinished()
        assertEquals(150, bar.previewTarget)
    }

    @Test
    fun `拖回当前页松手 预览就是当前页`() {
        val bar = SliderGestureState(initialPage = 40, pageCount = 200)
        bar.onValueChange(90f)
        bar.onValueChange(40f)
        assertEquals(40, bar.onGestureFinished())
        assertEquals("拖回当前页松手：预览就是那一页", 40, bar.previewTarget)
    }

    @Test
    fun `滑块值越界仍夹在首末页`() {
        val bar = SliderGestureState(initialPage = 10, pageCount = 200)
        bar.onValueChange(500f)
        assertEquals(199, bar.onGestureFinished())
        bar.onValueChange(-12f)
        assertEquals(0, bar.onGestureFinished())
    }

    @Test
    fun `单页书点击轨道不会跳到第二页`() {
        val bar = SliderGestureState(initialPage = 0, pageCount = 1)
        bar.onValueChange(1f)
        assertEquals(0, bar.onGestureFinished())
        assertEquals(0, bar.previewTarget)
    }

    @Test
    fun `菜单外翻页让滑块跟上 手势中不回写`() {
        val bar = SliderGestureState(initialPage = 10, pageCount = 200)
        bar.syncToPage(30)
        assertEquals(30f, bar.value, 0.001f)
        assertEquals("菜单外翻页落地后预览跟到该页", 30, bar.previewTarget)
        bar.onValueChange(90f)
        bar.syncToPage(5) // 手势中：音量键/滚轮翻页不得把滑块闪回旧页
        assertEquals(90f, bar.value, 0.001f)
        assertEquals(90, bar.onGestureFinished())
    }

    @Test
    fun `初值越界时夹回合法页`() {
        assertEquals(199, SliderGestureState(initialPage = 999, pageCount = 200).targetPage)
        assertEquals(0, SliderGestureState(initialPage = -3, pageCount = 200).targetPage)
        assertEquals(0, SliderGestureState(initialPage = 0, pageCount = 0).targetPage)
    }

    // ---------- 页数少的书（票 #105 AC9：点滑动条任意位置都能跳到对应页）----------

    @Test
    fun `三页书点击轨道任意位置都跳到对应页`() {
        // 值域 0f..2f（末页页位 2）：每一页都覆盖一段连续的值区间，不是只有最左/最中/最右三个点
        val bar = SliderGestureState(initialPage = 0, pageCount = 3)
        assertEquals(2, bar.lastPage)
        for ((value, page) in listOf(0.2f to 0, 0.4f to 0, 0.6f to 1, 1.0f to 1, 1.4f to 1, 1.6f to 2, 2.0f to 2)) {
            bar.onValueChange(value)
            assertEquals("滑块值 $value 应跳到页位 $page", page, bar.onGestureFinished())
        }
    }

    @Test
    fun `三页书点按行上任意比例都跳到对应页`() {
        // AC9 的落地口径（SeekSlider 自接的点按手势走这条）：比例 → 值 = 比例 × 末页页位 → 最近页。
        // 五个位置覆盖三页，且每一段都有对应的按下区间（不是只有最左/最中/最右三点）
        val bar = SliderGestureState(initialPage = 0, pageCount = 3)
        for ((fraction, page) in listOf(
            0.0f to 0,
            0.1f to 0,
            0.3f to 1,
            0.5f to 1,
            0.7f to 1,
            0.9f to 2,
            1.0f to 2,
        )) {
            assertEquals("按下在行宽 ${fraction * 100}% 处应跳到页位 $page", page, bar.onTapFraction(fraction))
            assertEquals("滑块要停在按下位置对应的值上", fraction * 2f, bar.value, 0.001f)
            assertFalse("点按不是「手势进行中」", bar.gestureActive)
        }
    }

    @Test
    fun `点按比例越界时夹回首末页`() {
        val bar = SliderGestureState(initialPage = 1, pageCount = 3)
        assertEquals("按下位置在行左外侧也落到第一页", 0, bar.onTapFraction(-0.4f))
        assertEquals("按下位置在行右外侧也落到最后一页", 2, bar.onTapFraction(1.7f))
        // 长书：比例直接换算成页位（线性比例）
        val long = SliderGestureState(initialPage = 0, pageCount = 301)
        assertEquals(300, long.lastPage)
        assertEquals(150, long.onTapFraction(0.5f))
        assertEquals(0, long.onTapFraction(0f))
        assertEquals(300, long.onTapFraction(1f))
    }

    @Test
    fun `三页书点击后预览以该页为中心`() {
        val bar = SliderGestureState(initialPage = 0, pageCount = 3)
        bar.onValueChange(1.6f) // 最右一段：第 3 页
        assertEquals(2, bar.onGestureFinished())
        assertEquals("跳页还没落地，预览已经以点击页为中心", 2, bar.previewTarget)
    }
}
