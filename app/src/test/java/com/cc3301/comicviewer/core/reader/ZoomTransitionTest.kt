package com.cc3301.comicviewer.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双击缩放过渡：过渡端点的锚点、逐帧插值、缓入缓出、以及双击的目标（放大 / 复位）。
 *
 * 反馈「放大镜的动画太生硬」——本用例锁的是过渡的**几何**：**缩放后内容 ≥ 视口的那些轴上**，整段过渡里
 * 双击点（锚点）的屏幕位置必须恒定（origin 一起插值会先漂出去再回来），缩放与位移必须同时走完，
 * 且首尾精确落在端点上；钳制按**页框**算、不改写天然偏移（满宽页与窄页两侧都有用例钉住）。
 * 帧循环与「手势接管即取消动画」的接线属 Compose 组合层，按 SPEC（Testing Decisions：UI 层走手动验收）不在此测。
 */
class ZoomTransitionTest {

    private val delta = 0.001f

    /** 页显示尺寸：1000×6000 的条漫长图 */
    private val pageW = 1000f
    private val pageH = 6000f

    /** 双击页内 (250, 4500) 的放大目标（[doubleTapZoom]，2x） */
    private val zoomed = doubleTapZoom(250f, 4500f, pageW, pageH, 2f)

    // ---------- 过渡端点 ----------

    @Test
    fun `放大过渡两端都锚在双击位置`() {
        val transition = zoomTransition(ZoomState(), zoomed)

        assertEquals("起点仍是适屏", 1f, transition.from.scale, delta)
        assertEquals(0f, transition.from.offsetX, delta)
        assertEquals(0f, transition.from.offsetY, delta)
        assertEquals("终点是双击目标", 2f, transition.to.scale, delta)
        assertEquals(
            "起点锚点也换成双击位置：scale = 1 时 origin 不参与映射（x = origin·extent·(1−scale) + …），视觉上不可见",
            0.25f,
            transition.from.originX,
            delta,
        )
        assertEquals(0.75f, transition.from.originY, delta)
        assertEquals(0.25f, transition.to.originX, delta)
        assertEquals(0.75f, transition.to.originY, delta)
    }

    @Test
    fun `复位过渡保持当前锚点`() {
        val transition = zoomTransition(zoomed, ZoomState())

        assertEquals(2f, transition.from.scale, delta)
        assertEquals(1f, transition.to.scale, delta)
        assertEquals(0f, transition.to.offsetX, delta)
        assertEquals(0f, transition.to.offsetY, delta)
        assertFalse("终点即适屏（再次双击恢复适屏）", transition.to.isZoomed)
        assertEquals("终点锚点钉在放大端的锚点上：scale = 1 时 origin 不参与映射", 0.25f, transition.to.originX, delta)
        assertEquals(0.75f, transition.to.originY, delta)
    }

    @Test
    fun `手势放大后的复位过渡锚点取放大端`() {
        // 双指缩放留下的锚点是视口中心：复位过渡（也走双击）应围它收缩，而不是先跳到页中心
        val pinched = ZoomState(scale = 3f, offsetX = 120f, offsetY = 40f, originX = 0.5f, originY = 0.5f)
        val transition = zoomTransition(pinched, ZoomState())

        assertEquals(0.5f, transition.from.originX, delta)
        assertEquals(0.5f, transition.to.originX, delta)
        assertEquals(120f, transition.from.offsetX, delta)
    }

    // ---------- 逐帧插值 ----------

    @Test
    fun `过渡中双击点始终不动（纯插值 不经过钳制）`() {
        // 不变式：整段过渡里锚点的屏幕位置恒定（两端锚点被钉死 → origin 项抵消，与 scale/offset 的插值无关）。
        // 这里走的是纯插值：组合层还会把每帧喂给 clampZoomOffset，钳制后的版本见下面两条用例。
        val transitions = listOf(
            zoomTransition(ZoomState(), zoomed),
            zoomTransition(zoomed, ZoomState()),
        )

        transitions.forEach { transition ->
            val anchor = transition.from.originY * pageH
            (0..ZOOM_ANIMATION_MILLIS step 20).forEach { ms ->
                val frame = zoomTransitionFrame(transition, ms * 1_000_000L)
                assertEquals(
                    "过渡 ${ms}ms 时锚点不应移动",
                    anchor,
                    mapContentToScreen(anchor, frame.originY, pageH, frame.scale, frame.offsetY),
                    delta,
                )
            }
        }
    }

    @Test
    fun `过渡首尾取端点 超时也不越界`() {
        val transition = zoomTransition(ZoomState(), zoomed)

        assertEquals("0ms 就是起点", transition.from, zoomTransitionFrame(transition, 0L))
        assertEquals(
            "到时长即终点",
            transition.to,
            zoomTransitionFrame(transition, ZOOM_ANIMATION_MILLIS * 1_000_000L),
        )
        assertEquals(
            "超时仍钳在终点（不越界、不反弹）",
            transition.to,
            zoomTransitionFrame(transition, 10_000_000_000L),
        )
        assertEquals("负值（时钟回退）也钳在起点", transition.from, zoomTransitionFrame(transition, -5L))
    }

    @Test
    fun `缩放与位移一起过渡`() {
        // 拖过的放大态复位：scale、offsetX 同一条进度走完
        val dragged = ZoomState(scale = 2f, offsetX = 200f, offsetY = 0f, originX = 0.5f, originY = 0.5f)
        val transition = zoomTransition(dragged, ZoomState())

        val half = zoomTransitionFrame(transition, (ZOOM_ANIMATION_MILLIS / 2) * 1_000_000L)
        assertEquals(1.5f, half.scale, delta)
        assertEquals(100f, half.offsetX, delta)
        assertEquals(0f, half.offsetY, delta)
    }

    @Test
    fun `过渡是缓入缓出而不是匀速`() {
        val transition = zoomTransition(ZoomState(), zoomed)

        val quarter = zoomTransitionFrame(transition, (ZOOM_ANIMATION_MILLIS / 4) * 1_000_000L).scale
        val half = zoomTransitionFrame(transition, (ZOOM_ANIMATION_MILLIS / 2) * 1_000_000L).scale
        val threeQuarters = zoomTransitionFrame(transition, (ZOOM_ANIMATION_MILLIS * 3 / 4) * 1_000_000L).scale

        assertEquals("半程到中点", 1.5f, half, delta)
        assertTrue("前 1/4 比匀速慢（缓入）：$quarter", quarter < 1.25f)
        assertTrue("后 1/4 比匀速快（缓出）：$threeQuarters", threeQuarters > 1.75f)
    }

    @Test
    fun `过渡在到时长之前不出终态 到时长那帧已落终态`() {
        // 行为断言（不是复述常量）：过渡真的花了时间（到时长之前每一帧都在两端之间）、
        // 且到时长那帧逐字段落在终点；终点与改动前的直接写入（双击目标过既有钳制）完全一致
        val transition = zoomTransition(ZoomState(), zoomed)
        val frameNanos = 16_666_667L
        val earlyFrames = generateSequence(frameNanos) { it + frameNanos }
            .takeWhile { it < ZOOM_ANIMATION_MILLIS * 1_000_000L }
            .map { zoomTransitionFrame(transition, it) }
            .toList()

        assertTrue("过渡必须有中间帧（不是一帧瞬变）：${earlyFrames.size} 帧", earlyFrames.size >= 10)
        earlyFrames.forEach { frame ->
            assertTrue("到时长之前不能先到终点：${frame.scale}", frame.scale < transition.to.scale)
            assertTrue("到时长之前不能停在起点：${frame.scale}", frame.scale > transition.from.scale)
        }
        assertEquals(
            "到时长那帧逐字段落终态",
            transition.to,
            zoomTransitionFrame(transition, ZOOM_ANIMATION_MILLIS * 1_000_000L),
        )
        assertEquals(
            "终态与改动前的直接写入逐字段一致（双击目标过既有钳制；钳制按页框算）",
            clampZoomOffset(zoomed, pageW, pageH),
            transition.to,
        )
    }

    @Test
    fun `过渡时长落在票面要求的 200 到 300 毫秒之间`() {
        // 对行为断言而不是对常量断言：时长由帧函数决定（帧循环就是拿它算进度）
        val transition = zoomTransition(ZoomState(), zoomed)

        assertTrue(
            "200ms 时仍在过渡中（时长 ≥ 200ms）：${zoomTransitionFrame(transition, 200_000_000L).scale}",
            zoomTransitionFrame(transition, 200_000_000L).scale < transition.to.scale,
        )
        assertEquals(
            "300ms 时已逐字段落终态（时长 ≤ 300ms）",
            transition.to,
            zoomTransitionFrame(transition, 300_000_000L),
        )
    }

    // ---------- 钳制不改写锚点（满宽页与窄页两侧都钉）----------

    @Test
    fun `窄于视口的页 过渡中双击点同样不动`() {
        // 算例 = 横屏单页：页 fit 后 771.4×1080（左右留黑边），页内 (578.5, 540) 双击、2x。
        // 钳制按**页框**算：2x 后内容 1542.8 > 771.4，可平移区间 = [-192.85, 578.55]，天然偏移 0 落在里面
        // ⇒ 钳制不改写插值出来的偏移，双击点整段不动（旧口径按视口钳制，这条轴会被强制挪走）。
        val viewportW = 1920f
        val viewportH = 1080f
        val narrowPageW = 771.4f
        val narrowPageH = 1080f
        val transition = zoomTransition(
            ZoomState(),
            doubleTapZoom(narrowPageW * 0.75f, narrowPageH * 0.5f, narrowPageW, narrowPageH, 2f),
        )
        val anchorX = transition.from.originX * narrowPageW

        (0..ZOOM_ANIMATION_MILLIS step 20).forEach { ms ->
            val clamped = clampZoomOffset(
                zoomTransitionFrame(transition, ms * 1_000_000L),
                narrowPageW,
                narrowPageH,
            )
            assertEquals(
                "${ms}ms 时窄页上的水平锚点不应移动",
                anchorX,
                mapContentToScreen(anchorX, clamped.originX, narrowPageW, clamped.scale, clamped.offsetX),
                delta,
            )
        }
        assertTrue("视口宽大于页宽（本算例的前提）", viewportW > narrowPageW)
        assertTrue("视口高不小于页高（单页 Fit 的前提）", viewportH >= narrowPageH)
    }

    @Test
    fun `满宽页 过渡中双击点不动`() {
        // 与上一条成对：页宽 = 视口宽时同样整段不动（下面用的是条漫长图那条过渡）
        val transition = zoomTransition(ZoomState(), zoomed)
        val anchorX = transition.from.originX * pageW

        (0..ZOOM_ANIMATION_MILLIS step 20).forEach { ms ->
            val clamped = clampZoomOffset(zoomTransitionFrame(transition, ms * 1_000_000L), pageW, pageH)
            assertEquals(
                "${ms}ms 时水平锚点不应移动",
                anchorX,
                mapContentToScreen(anchorX, clamped.originX, pageW, clamped.scale, clamped.offsetX),
                delta,
            )
        }
    }

    // ---------- 双击的目标 ----------

    @Test
    fun `双击的目标：放大态复位 否则以双击点为锚放大`() {
        val zoomIn = doubleTapZoomTarget(ZoomState(), 250f, 4500f, pageW, pageH, 2f)
        assertEquals(2f, zoomIn.scale, delta)
        assertEquals(0.25f, zoomIn.originX, delta)
        assertEquals(0.75f, zoomIn.originY, delta)
        assertEquals(0f, zoomIn.offsetX, delta)

        assertEquals(
            "再次双击恢复适屏",
            ZoomState(),
            doubleTapZoomTarget(zoomed, 500f, 3000f, pageW, pageH, 2f),
        )
    }

    @Test
    fun `双击目标以当前状态为准`() {
        // 动画/手势中途的当前状态已是放大态 → 双击一律判为复位（不回跳到放大）
        assertEquals(
            ZoomState(),
            doubleTapZoomTarget(ZoomState(scale = 1.2f, originX = 0.25f, originY = 0.75f), 250f, 4500f, pageW, pageH, 2f),
        )
        // 当前状态是适屏 → 双击仍是放大，且锚点用这一次的落点
        val rezoom = doubleTapZoomTarget(ZoomState(), 800f, 1000f, pageW, pageH, 2f)
        assertEquals(0.8f, rezoom.originX, delta)
        assertEquals(1000f / pageH, rezoom.originY, delta)
    }
}
