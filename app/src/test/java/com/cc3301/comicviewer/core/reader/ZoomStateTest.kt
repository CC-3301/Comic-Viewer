package com.cc3301.comicviewer.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 缩放状态纯函数（双击放大 / 双指缩放：倍率钳制、锚点换算、平移边界钳制） */
class ZoomStateTest {

    private val delta = 0.001f

    // ---------- 倍率钳制 ----------

    @Test
    fun `双击倍率钳制到 1dot5 到 4`() {
        assertEquals(DEFAULT_DOUBLE_TAP_SCALE, clampDoubleTapScale(DEFAULT_DOUBLE_TAP_SCALE), delta)
        assertEquals(MIN_DOUBLE_TAP_SCALE, clampDoubleTapScale(1.0f), delta)
        assertEquals(MIN_DOUBLE_TAP_SCALE, clampDoubleTapScale(0f), delta)
        assertEquals(2.5f, clampDoubleTapScale(2.5f), delta)
        assertEquals(MAX_DOUBLE_TAP_SCALE, clampDoubleTapScale(9f), delta)
    }

    @Test
    fun `双指倍率钳制到 1 到 8`() {
        assertEquals(MIN_PINCH_SCALE, clampPinchScale(1f), delta)
        assertEquals(MIN_PINCH_SCALE, clampPinchScale(0.3f), delta)
        assertEquals(3.25f, clampPinchScale(3.25f), delta)
        assertEquals(MAX_PINCH_SCALE, clampPinchScale(12f), delta)
    }

    // ---------- 双击锚点换算 ----------

    @Test
    fun `双击锚点等于页内比例`() {
        // 页 1000x6000，双击页内 (250, 4500)，2x → 锚点 (0.25, 0.75)，无平移
        val state = doubleTapZoom(250f, 4500f, 1000f, 6000f, 2f)
        assertEquals(2f, state.scale, delta)
        assertEquals(0.25f, state.originX, delta)
        assertEquals(0.75f, state.originY, delta)
        assertEquals(0f, state.offsetX, delta)
        assertEquals(0f, state.offsetY, delta)
        assertTrue(state.isZoomed)
    }

    @Test
    fun `双击页角锚点贴边`() {
        assertEquals(0f, doubleTapZoom(0f, 0f, 1000f, 2000f, 2f).originX, delta)
        assertEquals(1f, doubleTapZoom(1000f, 2000f, 1000f, 2000f, 2f).originY, delta)
    }

    @Test
    fun `页尺寸缺失时锚点回退页中心`() {
        // 页尚未测量（0x0）时不应产生 NaN/越界锚点
        assertEquals(0.5f, doubleTapZoom(10f, 10f, 0f, 0f, 2f).originX, delta)
        assertEquals(0.5f, doubleTapZoom(10f, 10f, 0f, 0f, 2f).originY, delta)
    }

    @Test
    fun `适屏状态不算放大`() {
        assertFalse(ZoomState().isZoomed)
        assertFalse(ZoomState(scale = 1.0001f).isZoomed)
        assertTrue(ZoomState(scale = 1.5f).isZoomed)
    }

    // ---------- 平移边界钳制 ----------

    @Test
    fun `单页模式 双向钳制在图片显示区域内`() {
        // 视口 1000x2000、页（Fit 后）1000x2000、2x、页中心锚点 → 对称 ±500 / ±1000
        val clamped = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 900f, offsetY = 1500f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 2000f,
            constrainVertical = true,
        )
        assertEquals(500f, clamped.offsetX, delta)
        assertEquals(1000f, clamped.offsetY, delta)

        val negative = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = -900f, offsetY = -1500f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 2000f,
            constrainVertical = true,
        )
        assertEquals(-500f, negative.offsetX, delta)
        assertEquals(-1000f, negative.offsetY, delta)

        val inside = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 200f, offsetY = -300f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 2000f,
            constrainVertical = true,
        )
        assertEquals(200f, inside.offsetX, delta)
        assertEquals(-300f, inside.offsetY, delta)
    }

    @Test
    fun `锚点偏移时可用平移区间随之偏移`() {
        // 锚点在页内 0.25 处：2x 后左边缘恰好由 maxPositive 决定，右边缘决定 minNegative
        val clamped = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 999f, offsetY = 0f, originX = 0.25f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 2000f,
            constrainVertical = true,
        )
        // maxPositive = 0.25*1000*(2-1) = 250；minNegative = 1000-2000+250 = -750
        assertEquals(250f, clamped.offsetX, delta)

        val opposite = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = -999f, offsetY = 0f, originX = 0.25f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 2000f,
            constrainVertical = true,
        )
        assertEquals(-750f, opposite.offsetX, delta)
    }

    @Test
    fun `条漫模式 垂直归零 水平钳制`() {
        // 条漫：页宽 = 视口宽、页高远大于视口；垂直方向交给列表滚动 → offsetY 恒为 0
        val clamped = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 900f, offsetY = 500f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 6000f,
            constrainVertical = false,
        )
        assertEquals(500f, clamped.offsetX, delta)
        assertEquals(0f, clamped.offsetY, delta)

        val inside = clampZoomOffset(
            ZoomState(scale = 3f, offsetX = 300f, offsetY = 400f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 6000f,
            constrainVertical = false,
        )
        assertEquals(300f, inside.offsetX, delta)
        assertEquals(0f, inside.offsetY, delta)
    }

    @Test
    fun `内容小于视口时居中`() {
        // 放大后仍比视口窄：水平居中（offset = (视口 - 内容)/2）
        val centered = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 400f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 400f,
            pageH = 2000f,
            constrainVertical = false,
        )
        assertEquals(100f, centered.offsetX, delta)
    }

    @Test
    fun `未放大时回到适屏全零`() {
        val restored = clampZoomOffset(
            ZoomState(scale = 1f, offsetX = 400f, offsetY = 500f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 2000f,
            constrainVertical = true,
        )
        assertEquals(ZoomState(), restored)
        assertEquals(
            ZoomState(),
            clampZoomOffset(ZoomState(), 1000f, 2000f, 1000f, 2000f, constrainVertical = false),
        )
    }

    @Test
    fun `锚点在缩放前后保持不动`() {
        // 不变式：以锚点为中心缩放后，该页内点的屏幕位置不变（P0 类回归的锁）
        val page = 6000f
        val state = doubleTapZoom(250f, 4500f, 1000f, page, 2f)
        val anchor = state.originY * page
        assertEquals(
            anchor,
            mapContentToScreen(anchor, state.originY, page, state.scale, state.offsetY),
            delta,
        )
        // 非锚点位置必须移动，确认真的发生了缩放
        assertNotEquals(0f, mapContentToScreen(0f, state.originY, page, state.scale, state.offsetY))
    }

    @Test
    fun `条漫长图双击经钳制后仍无垂直平移`() {
        // 长图 1000x6000（页高 ≫ 视口高）：双击 + 铂制后锚点居中且 offsetY 保持 0
        val state = clampZoomOffset(
            doubleTapZoom(500f, 3000f, 1000f, 6000f, 2f),
            viewportW = 1000f,
            viewportH = 2000f,
            pageW = 1000f,
            pageH = 6000f,
            constrainVertical = false,
        )
        assertEquals(0.5f, state.originX, delta)
        assertEquals(0.5f, state.originY, delta)
        assertEquals(0f, state.offsetY, delta)
    }
}
