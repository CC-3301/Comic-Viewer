package com.cc3301.comicviewer.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `双击偏右下 点击点保持不动`() {
        // viewport 1000x2000，点击 (1000,1000)，scale=2 → 位移 = 点击点相对中心距离 × (1-2)
        val state = doubleTapZoom(1000f, 1000f, 1000f, 2000f, 2f)
        assertEquals(2f, state.scale, delta)
        assertEquals(-500f, state.offsetX, delta)
        assertEquals(0f, state.offsetY, delta)
        assertTrue(state.isZoomed)
    }

    @Test
    fun `双击视图正中 无位移`() {
        val state = doubleTapZoom(500f, 1000f, 1000f, 2000f, 2f)
        assertEquals(0f, state.offsetX, delta)
        assertEquals(0f, state.offsetY, delta)
        assertTrue(state.isZoomed)
    }

    @Test
    fun `适屏状态不算放大`() {
        assertFalse(ZoomState().isZoomed)
        assertFalse(ZoomState(scale = 1.0001f).isZoomed)
        assertTrue(ZoomState(scale = 1.5f).isZoomed)
    }

    // ---------- 平移边界钳制 ----------

    @Test
    fun `条漫模式 垂直归零 水平钳制`() {
        // 条漫：垂直方向留给列表滚动，offsetY 强制 0；maxX = W*(scale-1)/2 = 500
        val clamped = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 900f, offsetY = 300f),
            viewportW = 1000f,
            viewportH = 2000f,
            constrainVertical = false,
        )
        assertEquals(2f, clamped.scale, delta)
        assertEquals(500f, clamped.offsetX, delta)
        assertEquals(0f, clamped.offsetY, delta)

        val negative = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = -900f, offsetY = -300f),
            viewportW = 1000f,
            viewportH = 2000f,
            constrainVertical = false,
        )
        assertEquals(-500f, negative.offsetX, delta)
        assertEquals(0f, negative.offsetY, delta)
    }

    @Test
    fun `条漫模式 界内平移原样保留`() {
        val state = ZoomState(scale = 3f, offsetX = 300f, offsetY = 400f)
        val clamped = clampZoomOffset(state, 1000f, 2000f, constrainVertical = false)
        assertEquals(300f, clamped.offsetX, delta)
        assertEquals(0f, clamped.offsetY, delta)
    }

    @Test
    fun `单页模式 双向钳制在图片边界内`() {
        // 单页：maxX = 500，maxY = 2000*(2-1)/2 = 1000
        val clamped = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 900f, offsetY = 1500f),
            viewportW = 1000f,
            viewportH = 2000f,
            constrainVertical = true,
        )
        assertEquals(500f, clamped.offsetX, delta)
        assertEquals(1000f, clamped.offsetY, delta)

        val negative = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = -900f, offsetY = -1500f),
            viewportW = 1000f,
            viewportH = 2000f,
            constrainVertical = true,
        )
        assertEquals(-500f, negative.offsetX, delta)
        assertEquals(-1000f, negative.offsetY, delta)

        val inside = clampZoomOffset(
            ZoomState(scale = 2f, offsetX = 200f, offsetY = -300f),
            viewportW = 1000f,
            viewportH = 2000f,
            constrainVertical = true,
        )
        assertEquals(200f, inside.offsetX, delta)
        assertEquals(-300f, inside.offsetY, delta)
    }

    @Test
    fun `未放大时回到适屏全零`() {
        val restored = clampZoomOffset(
            ZoomState(scale = 1f, offsetX = 400f, offsetY = 500f),
            viewportW = 1000f,
            viewportH = 2000f,
            constrainVertical = true,
        )
        assertEquals(ZoomState(), restored)
        assertEquals(ZoomState(), clampZoomOffset(ZoomState(), 1000f, 2000f, constrainVertical = false))
    }
}
