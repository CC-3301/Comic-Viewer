package com.cc3301.comicviewer.core.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放大状态的接缝：喂一帧进去，看状态出来——锚点反解、平移钳制、按页记忆、视口变化重钳制、
 * 双击过渡的逐帧与让位。全部不需要 Compose：几何按 [ZoomGeometry] 直接给，渲染节拍是一个注入的时钟。
 *
 * 纯函数那几层（[doubleTapZoom] / [clampZoomOffset] / [zoomTransitionFrame]）由 [ZoomStateTest] 与
 * [ZoomTransitionTest] 单独钉住，这里钉的是**它们的接线**：状态从哪读、写到哪一页、什么时候取消过渡。
 */
class ZoomControllerTest {

    private val delta = 0.001f

    /** 满宽页：页 1000×2000、视口 1000×2000、页贴在视口左上角 */
    private fun geometry(
        page: Int = 0,
        pageLeft: Float = 0f,
        pageTop: Float = 0f,
        pageW: Float = 1000f,
        pageH: Float = 2000f,
        viewW: Float = 1000f,
        viewH: Float = 2000f,
    ) = ZoomGeometry(viewW, viewH, page, pageLeft, pageTop, pageW, pageH)

    /** 一帧手势输入：几何取上面的满宽页（[pageW]/[pageH] 给 0 = 页未测量） */
    private fun frame(
        page: Int = 0,
        segmentStart: Boolean = false,
        count: Int = 1,
        x: Float = 0f,
        y: Float = 0f,
        span: Float = 0f,
        slop: Float = 10f,
        pageW: Float = 1000f,
        pageH: Float = 2000f,
    ) = ZoomFrame(
        geometry = geometry(page = page, pageW = pageW, pageH = pageH),
        segmentStart = segmentStart,
        pointerCount = count,
        centroidX = x,
        centroidY = y,
        spanPx = span,
        panSlopPx = slop,
    )

    /** 每次调用的渲染节拍前进 16ms：过渡一次跑到时长 */
    private fun steppingClock(stepNanos: Long = 16_000_000L): suspend () -> Long {
        var now = 0L
        return {
            now += stepNanos
            now
        }
    }

    /** 控制器：状态回调收进 [writes]（页号 + 新状态） */
    private fun TestScope.controller(
        writes: MutableList<Pair<Int, ZoomState>>,
        clock: suspend () -> Long,
    ): ZoomController = ZoomController(this, clock) { page, state -> writes += page to state }

    // ---------- 双指缩放的锚点反解 ----------

    @Test
    fun `双指缩放的锚点反解把质心下的内容点钉住`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        // 首帧只建立基线
        zoom.onGestureFrame(frame(segmentStart = true, count = 2, x = 250f, y = 500f, span = 50f))
        assertTrue("基线帧不产生状态", writes.isEmpty())
        // 第二帧：指距 50 → 100 = 2 倍，质心在 (250, 500)
        zoom.onGestureFrame(frame(count = 2, x = 250f, y = 500f, span = 100f))

        val state = zoom.stateOf(0)
        assertEquals(2f, state.scale, delta)
        assertEquals("锚点 = 质心在页内的比例", 0.25f, state.originX, delta)
        assertEquals(0.25f, state.originY, delta)
        assertEquals("质心下的内容点本来就贴着锚点，因此没有平移量", 0f, state.offsetX, delta)
        assertEquals(0f, state.offsetY, delta)
        // 不变式：质心下的那个页内内容点，缩放前后落在同一处
        assertEquals(250f, mapContentToScreen(250f, state.originX, 1000f, state.scale, state.offsetX), delta)
        assertEquals(500f, mapContentToScreen(500f, state.originY, 2000f, state.scale, state.offsetY), delta)
    }

    @Test
    fun `页未测量时双指只当平移 尺寸回退视口`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        zoom.onGestureFrame(frame(segmentStart = true, count = 2, x = 100f, y = 100f, span = 50f, pageW = 0f, pageH = 0f))
        zoom.onGestureFrame(frame(count = 2, x = 110f, y = 110f, span = 100f, pageW = 0f, pageH = 0f))

        val state = zoom.stateOf(0)
        assertEquals(2f, state.scale, delta)
        assertEquals("页内坐标算不出来：锚点留在页中心", 0.5f, state.originX, delta)
        assertEquals("质心位移当平移", 10f, state.offsetX, delta)
        assertEquals(10f, state.offsetY, delta)
    }

    // ---------- 平移与钳制 ----------

    @Test
    fun `平移在页框内钳制`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        // 先以页中心为锚放大到 2x（状态 = 2x、无平移、锚点页中心）
        zoom.onGestureFrame(frame(segmentStart = true, count = 2, x = 500f, y = 1000f, span = 50f))
        zoom.onGestureFrame(frame(count = 2, x = 500f, y = 1000f, span = 100f))

        // 单指从 (100,100) 拖到 (700,1300)：增量 (600, 1200)，两个方向都越过可平移区间
        assertFalse(zoom.onGestureFrame(frame(segmentStart = true, count = 1, x = 100f, y = 100f)))
        assertTrue("放大态的单指拖动由本手势接管", zoom.onGestureFrame(frame(count = 1, x = 700f, y = 1300f)))

        assertEquals("水平上限 = 0.5·1000·(2−1)", 500f, zoom.stateOf(0).offsetX, delta)
        assertEquals("纵向上限 = 0.5·2000·(2−1)", 1000f, zoom.stateOf(0).offsetY, delta)
    }

    @Test
    fun `未放大的页上单指不消费也不动状态`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        assertFalse(zoom.onGestureFrame(frame(segmentStart = true, count = 1, x = 100f, y = 100f)))
        assertFalse("未放大：整段让位给列表滚动", zoom.onGestureFrame(frame(count = 1, x = 700f, y = 700f)))
        assertTrue("全程没有状态写入", writes.isEmpty())
        assertEquals(ZoomState(), zoom.stateOf(0))
    }

    // ---------- 按页记忆 ----------

    @Test
    fun `放大状态按页记忆 另一页不受影响`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        zoom.onDoubleTap(geometry(page = 0), 250f, 500f, 2f)
        advanceUntilIdle()
        assertTrue("第 0 页已放大", zoom.stateOf(0).isZoomed)

        // 第 1 页未放大：单指拖动让位，第 0 页的状态一个字节都不动
        assertFalse(zoom.onGestureFrame(frame(page = 1, segmentStart = true, count = 1, x = 0f, y = 0f)))
        assertFalse(zoom.onGestureFrame(frame(page = 1, count = 1, x = 500f, y = 500f)))

        assertEquals(2f, zoom.stateOf(0).scale, delta)
        assertEquals(ZoomState(), zoom.stateOf(1))
    }

    // ---------- 视口变化重钳制 ----------

    @Test
    fun `视口变化后按新页框重钳制`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        // 2x + 平移到旧页框的水平上限 500
        zoom.onGestureFrame(frame(segmentStart = true, count = 2, x = 500f, y = 1000f, span = 50f))
        zoom.onGestureFrame(frame(count = 2, x = 500f, y = 1000f, span = 100f))
        zoom.onGestureFrame(frame(segmentStart = true, count = 1, x = 100f, y = 100f))
        zoom.onGestureFrame(frame(count = 1, x = 700f, y = 100f))
        assertEquals(500f, zoom.stateOf(0).offsetX, delta)

        // 旋转后这页变窄（页框 400 宽）：新的水平上限 = 0.5·400·(2−1)
        zoom.onViewportChanged { page -> geometry(page = page, pageW = 400f) }

        assertEquals(200f, zoom.stateOf(0).offsetX, delta)
        assertEquals("纵向没有越界，重钳制不改它", 0f, zoom.stateOf(0).offsetY, delta)
    }

    // ---------- 双击过渡 ----------

    @Test
    fun `双击过渡逐帧写入 双击点整段不动 到时长落终点`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        // 条漫长页：页 1000×6000、视口 1000×2000
        zoom.onDoubleTap(geometry(pageH = 6000f, viewH = 2000f), 250f, 1500f, 2f)
        advanceUntilIdle()

        assertTrue("过渡必须有中间帧（不是一帧瞬变）：${writes.size} 帧", writes.size >= 10)
        assertEquals(
            "到时长逐字段落终点（与改动前的直接写入同一份钳制）",
            clampZoomOffset(doubleTapZoom(250f, 1500f, 1000f, 6000f, 2f), 1000f, 6000f),
            writes.last().second,
        )
        // 不变式：整段过渡里双击点（两轴）的屏幕位置恒定
        writes.forEach { (page, state) ->
            assertEquals(0, page)
            assertEquals(
                "双击点整段过渡不动",
                250f,
                mapContentToScreen(250f, state.originX, 1000f, state.scale, state.offsetX),
                delta,
            )
            assertEquals(
                1500f,
                mapContentToScreen(1500f, state.originY, 6000f, state.scale, state.offsetY),
                delta,
            )
        }
    }

    @Test
    fun `再次双击从当前状态接上 不回跳`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        val zoom = controller(writes, steppingClock())

        zoom.onDoubleTap(geometry(), 250f, 500f, 2f)
        advanceUntilIdle()
        assertEquals(2f, zoom.stateOf(0).scale, delta)

        writes.clear()
        // 已放大 → 复位过渡；起点是**当前显示状态**，不回到适屏再重来
        zoom.onDoubleTap(geometry(), 500f, 1000f, 2f)
        advanceUntilIdle()

        assertEquals("复位到适屏", ZoomState(), zoom.stateOf(0))
        assertTrue("起点接着当前显示状态（不回跳）：${writes.first().second.scale}", writes.first().second.scale > 1.9f)
        assertEquals("终点即适屏", 1f, writes.last().second.scale, delta)
    }

    @Test
    fun `手势接管取消在飞的过渡`() = runTest {
        val writes = mutableListOf<Pair<Int, ZoomState>>()
        // 这个节拍会挂起：过渡停在半路，等下一帧
        var now = 0L
        val zoom = controller(writes) {
            delay(16)
            now += 16_000_000L
            now
        }

        zoom.onDoubleTap(geometry(), 250f, 500f, 2f)
        advanceTimeBy(100)
        runCurrent()
        val before = writes.size
        assertTrue("过渡在飞：$before 帧", before >= 1)

        // 双指接管（第二帧才产生倍率）
        zoom.onGestureFrame(frame(segmentStart = true, count = 2, x = 100f, y = 100f, span = 50f))
        zoom.onGestureFrame(frame(count = 2, x = 110f, y = 110f, span = 100f))
        val after = writes.size
        assertTrue("接管那一帧本身要落地", after > before)

        advanceUntilIdle()
        assertEquals("手势接管后过渡不得再写帧（动画让位于手指）", after, writes.size)
    }
}
