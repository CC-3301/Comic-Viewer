package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面解码宽度、解码区域与解码缓存键（票 #56 + 票 #81）。
 *
 * 票 #56 验收口径（原根因：解码宽度写死 128px，网格 2 列格宽数百 px 被放大数倍）：
 * - 解码宽度 ≥ 本次实际显示宽度，分桶过冲 ≤32px（列表档 = 56dp 对应像素宽）
 * - 缓存键含真实目标宽度：列表档与网格档不得互相串图
 * - 换列数（3/4 列）解码宽度随之变小
 * - ±1px 抖动落在同一桶，不为同一张封面留多份缓存
 *
 * 票 #81 验收口径（原根因：`inSampleSize` 只按宽度定，源高宽比 ≫ 格比例的封面整张解码）：
 * - 800×8000 封面在网格 2 列（桶 512）下走可见带，位图字节数 ≦ 3:4 封面的 2 倍
 * - 可见带的横向分辨率仍 ≥ 格宽（不因裁剪变糊）、且居中、比例等于显示盒比例
 * - 缓存键含裁剪目标
 * - 超长/超宽/正常/桶界四种源都有覆盖，且任何源都不比整图子采样多解（不回退现状）
 */
class CoverDecodeTest {

    /**
     * 格宽公式走仓库唯一来源 [gridCellWidth]（360dp 屏、外边距 12dp、列间距 6dp）。
     * 两个尺寸参数仍是字面量：`GRID_CONTENT_PADDING`/`GRID_HORIZONTAL_SPACING` 在 `BrowserScreen` 里是私有的，
     * 改它们时本文件（同 [GridLayoutTest]）不会自动跟上，需把这里的 12f/6f 一并改。
     */
    private fun cellDp(columns: Int): Float = gridCellWidth(360f, columns, 12f, 6f)

    /** 列表档行内封面列宽（BrowserScreen.LIST_COVER_WIDTH） */
    private val listCoverDp = 56f

    /** 两个裁剪目标（网格档=固定格比例；列表档=源比例夹到兜底区间） */
    private val GRID = CoverDecode.CropTarget.GridCell
    private val LIST = CoverDecode.CropTarget.OwnAspect

    /** 常见屏幕密度（mdpi/hdpi/xhdpi/420dpi/xxhdpi/560dpi） */
    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f)

    private fun assertCoversDisplayWidth(displayDp: Float, density: Float, label: String) {
        val displayPx = displayDp * density
        val target = CoverDecode.targetWidthPx(displayPx)
        assertTrue(
            "$label density=$density: 解码宽度 $target 必须 ≥ 显示宽度 $displayPx（否则又要靠拉伸）",
            target >= displayPx,
        )
        assertTrue(
            "$label density=$density: 分桶过冲 ${target - displayPx} 必须 ≤32px（票面口径）",
            target - displayPx <= 32f,
        )
    }

    @Test
    fun `网格 2 列各密度下解码宽度不小于格宽且过冲不超过 32px`() {
        densities.forEach { assertCoversDisplayWidth(cellDp(2), it, "网格2列") }
    }

    @Test
    fun `列表档各密度下解码宽度不小于 56dp 对应像素`() {
        densities.forEach { assertCoversDisplayWidth(listCoverDp, it, "列表档") }
    }

    @Test
    fun `3 列 4 列的解码宽度随列数变小且各自不小于格宽`() {
        val density = 3f
        val two = CoverDecode.targetWidthPx(cellDp(2) * density)
        val three = CoverDecode.targetWidthPx(cellDp(3) * density)
        val four = CoverDecode.targetWidthPx(cellDp(4) * density)
        assertTrue("列数越多格宽越小：解码宽度必须跟着变小（2列 $two / 3列 $three / 4列 $four）", three < two && four < three)
        assertTrue(three >= cellDp(3) * density)
        assertTrue(four >= cellDp(4) * density)
    }

    @Test
    fun `亚像素抖动落在同一个桶里 不会为同一张封面多解`() {
        // 布局约束抖动几百分之一 px 不能让缓存键漂移
        assertEquals(CoverDecode.targetWidthPx(470f), CoverDecode.targetWidthPx(470.6f))
        assertEquals(CoverDecode.targetWidthPx(330f), CoverDecode.targetWidthPx(330.4f))
    }

    @Test
    fun `解码宽度始终是桶的整数倍`() {
        listOf(1f, 31.9f, 32f, 32.1f, 128f, 495.4f, 1080f).forEach { width ->
            assertEquals("width=$width", 0, CoverDecode.targetWidthPx(width) % CoverDecode.BUCKET_PX)
        }
    }

    @Test
    fun `布局未就绪的零宽或负宽不会产生 0 解码宽度`() {
        // 0 会成为 BitmapFactory 子采样循环的除零/失控输入
        assertTrue(CoverDecode.targetWidthPx(0f) >= CoverDecode.BUCKET_PX)
        assertTrue(CoverDecode.targetWidthPx(-10f) >= CoverDecode.BUCKET_PX)
    }

    @Test
    fun `缓存键含真实目标宽度 异宽异键同宽同键`() {
        assertEquals(
            "同条目同宽度同重取键必须同键（否则每次都要重解）",
            CoverDecode.key("entry-1", 0, 512, GRID),
            CoverDecode.key("entry-1", 0, 512, GRID),
        )
        assertNotEquals(
            "列表档与网格档宽度不同，键必须不同（不得互相串图）",
            CoverDecode.key("entry-1", 0, 192, LIST),
            CoverDecode.key("entry-1", 0, 512, GRID),
        )
    }

    @Test
    fun `缓存键含裁剪目标 两档同宽也不串图`() {
        assertNotEquals(
            "列表档与网格档碰巧落在同一个宽度桶时，裁剪目标必须把两键分开",
            CoverDecode.key("entry-1", 0, 512, LIST),
            CoverDecode.key("entry-1", 0, 512, GRID),
        )
    }

    @Test
    fun `缓存键仍按条目与重取键区分`() {
        assertNotEquals(CoverDecode.key("entry-1", 0, 512, GRID), CoverDecode.key("entry-2", 0, 512, GRID))
        assertNotEquals(CoverDecode.key("entry-1", 0, 512, GRID), CoverDecode.key("entry-1", 1, 512, GRID))
        assertNotEquals(CoverDecode.key("entry-1", null, 512, GRID), CoverDecode.key("entry-1", 1, 512, GRID))
    }

    // ---------- 按显示盒解码可见带（票 #81） ----------

    /** 网格 2 列在 3.0 密度下的解码目标宽度（= 验收口径里的 512px 桶） */
    private val gridTarget = CoverDecode.targetWidthPx(cellDp(2) * 3f)

    /** 同尺寸下“普通 3:4 封面”的计划（超长/超宽封面都拿它比字节数） */
    private fun normalCoverPlan(targetWidthPx: Int) = CoverDecode.plan(800, 1067, targetWidthPx, GRID)

    @Test
    fun `超长源按可见带解码 字节数与 3-4 封面同量级`() {
        val long = CoverDecode.plan(800, 8000, gridTarget, GRID)
        val normal = normalCoverPlan(gridTarget)
        assertTrue("源高宽比 10 的封面必须走可见带（整图子采样要 12.8MiB）", long.region)
        assertTrue(
            "800×8000 的位图字节数 ${long.decodedByteCount} 不得超过 3:4 封面 ${normal.decodedByteCount} 的 2 倍",
            long.decodedByteCount <= normal.decodedByteCount * 2,
        )
        assertEquals("可见带横向保持源分辨率", 800, long.decodedWidth)
        assertTrue("可见带横向分辨率 ${long.decodedWidth} 不得低于格宽 $gridTarget", long.decodedWidth >= gridTarget)
    }

    @Test
    fun `超长源的可见带贴显示盒比例且居中`() {
        val plan = CoverDecode.plan(800, 8000, gridTarget, GRID)
        assertEquals("横向不裁：带宽 = 源宽", 800, plan.width)
        assertEquals("左侧不裁", 0, plan.left)
        assertEquals("带高 = 宽 × 格比例 4:3", 1067, plan.height)
        assertEquals("带在源里垂直居中", (8000 - plan.height) / 2, plan.top)
        assertEquals(
            "带的宽高比就是显示盒比例（则 Compose 侧不必再裁、横向不会二次变糊）",
            CoverLayout.GRID_CELL_ASPECT.toDouble(),
            plan.height.toDouble() / plan.width,
            0.001,
        )
    }

    @Test
    fun `超宽源同样不整张 1-1 解码 字节数不超过 3-4 封面`() {
        val wide = CoverDecode.plan(8000, 800, gridTarget, GRID)
        assertTrue(
            "8000×800 的位图字节数 ${wide.decodedByteCount} 不得超过 3:4 封面 ${normalCoverPlan(gridTarget).decodedByteCount}",
            wide.decodedByteCount <= normalCoverPlan(gridTarget).decodedByteCount,
        )
        assertTrue("横向分辨率 ${wide.decodedWidth} 不低于格宽 $gridTarget", wide.decodedWidth >= gridTarget)
    }

    @Test
    fun `正常比例封面保持整图子采样 现状不变`() {
        // 网格档：4:3 源与格比例一致，可见带就是整图 —— 不该为“裁剪”多解
        val grid = CoverDecode.plan(800, 1067, gridTarget, GRID)
        assertTrue("4:3 源在网格档不必裁剪", !grid.region)
        assertEquals(1, grid.sampleSize)
        assertEquals(800, grid.decodedWidth)
        assertEquals(1067, grid.decodedHeight)
        // 列表档：56dp → 192px 桶，比例 1.33 在兜底区间内 → 不裁（与 CoverLayout.needsCrop 同口径）
        val list = CoverDecode.plan(800, 1067, 192, LIST)
        assertTrue("比例在兜底区间内的封面不得裁", !list.region)
        assertEquals(4, list.sampleSize)
        assertEquals(200, list.decodedWidth)
    }

    @Test
    fun `裁剪目标的口径 网格固定格比例 列表夹到兜底区间`() {
        assertEquals(CoverLayout.GRID_CELL_ASPECT, CoverDecode.boxAspect(GRID, 10f), 0f)
        assertEquals(
            "列表档比 1.6 更长就夹到 MAX_ASPECT",
            CoverLayout.MAX_ASPECT,
            CoverDecode.boxAspect(LIST, 10f),
            0f,
        )
        assertEquals(
            "列表档比 0.6 更扁就夹到 MIN_ASPECT",
            CoverLayout.MIN_ASPECT,
            CoverDecode.boxAspect(LIST, 0.1f),
            0f,
        )
        assertEquals("列表档区间内保持源比例（不裁）", 1.2f, CoverDecode.boxAspect(LIST, 1.2f), 0f)
    }

    @Test
    fun `桶界两侧的超长封面仍是可见带且字节数不超 3-4 封面的 2 倍`() {
        listOf(511.9f, 512f, 512.1f, 544f, 1024f).forEach { displayWidth ->
            val target = CoverDecode.targetWidthPx(displayWidth)
            val plan = CoverDecode.plan(800, 8000, target, GRID)
            assertTrue("display=$displayWidth target=$target：必须走可见带", plan.region)
            assertTrue(
                "display=$displayWidth：横向分辨率不得低于桶宽与源宽中较小的那个",
                plan.decodedWidth >= minOf(800, target),
            )
            assertTrue(
                "display=$displayWidth：字节数 ${plan.decodedByteCount} 不得超过 3:4 封面 2 倍",
                plan.decodedByteCount <= normalCoverPlan(target).decodedByteCount * 2,
            )
        }
    }

    @Test
    fun `任何源尺寸与两档口径下都不比整图子采样多解`() {
        val sizes = listOf(
            1 to 1,
            100 to 100,
            800 to 1067,
            800 to 8000,
            8000 to 800,
            1600 to 16000,
            4000 to 3000,
        )
        listOf(192, 512, 1024).forEach { target ->
            sizes.forEach { (width, height) ->
                listOf(GRID, LIST).forEach { cropTarget ->
                    val plan = CoverDecode.plan(width, height, target, cropTarget)
                    val sample = CoverDecode.sampleSizeForFullImage(width, target)
                    val fullBytes = (width / sample) * (height / sample) * CoverDecode.RGB_565_BYTES_PER_PIXEL
                    assertTrue(
                        "${width}×$height target=$target $cropTarget：区域分支 ${plan.decodedByteCount} " +
                            "不得多于整图子采样 $fullBytes（否则就是把现状改差）",
                        plan.decodedByteCount <= fullBytes,
                    )
                }
            }
        }
    }
}
