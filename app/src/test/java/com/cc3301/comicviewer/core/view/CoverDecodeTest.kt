package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * - 长条漫封面（800×8000 及 1080×5400 / 2000×10000 / 2048×20480 这类宽源长条页）走可见带，
 *   **保留位图**字节数 ≤ 同宽 3:4 封面的 2 倍
 * - 可见带的横向分辨率仍 ≥ 格宽（不因裁剪变糊）、且居中、比例等于显示盒比例
 * - 缓存键含裁剪目标
 * - 超长/超宽/正常/桶界四种源都有覆盖，且带的三条入选规则（真裁了像素、保留更小、瞬态在预算内）
 *   在任何源尺寸与两档口径下都成立
 *
 * 票 #85 验收口径（原根因：`BitmapRegionDecoder` 解出即源分辨率，源宽 ≳2170px 的长条封面撞上 12.8MB 上限
 * 退回整图子采样，保留位图重新按源比例算——4000×20000 保留 10MB ≈ 同宽 3:4 封面的 3.75 倍）：
 * - 同一批源改用 `ImageDecoder` 的 `setCrop` + `setTargetSize`（[CoverDecode.BandDecoder.CropToTarget]）后
 *   走带分支：4000×20000 这一档的保留位图与**瞬态峰值**都 ≤ 同宽 3:4 封面的 2 倍；同类里更窄的长条
 *   （2200×20000）峰值由「不超改动前的整图子采样」兜（2× 无法对整类成立，机制见 SPEC）
 * - 裁剪解码解出的是目标面（整张源 × `s`，不是显示盒），目标面进峰值；交给 `setCrop` 的矩形落在目标尺寸内（否则 API 会抛）、
 *   且居中——偏离 1px 以上的几何会在真解码用例里现形（`CoverDecodeBytesTest`）
 * - API 26/27 的 [CoverDecode.BandDecoder.Region] 行为（含 12.8MB 上限）不变
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

    /** 两条裁剪解码器：`REGION` = BitmapRegionDecoder（API 26/27）；`CROP` = ImageDecoder 的 setCrop+setTargetSize（API 28+） */
    private val REGION = CoverDecode.BandDecoder.Region
    private val CROP = CoverDecode.BandDecoder.CropToTarget

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

    // ---------- 按显示盒解码可见带（票 #81，显式走区域解码；票 #85 的裁剪解码见下一节） ----------

    /** 网格 2 列在 3.0 密度下的解码目标宽度（= 验收口径里的 512px 桶） */
    private val gridTarget = CoverDecode.targetWidthPx(cellDp(2) * 3f)

    /**
     * 同宽“普通 3:4 封面”的计划（长条封面都拿它比保留位图字节数与峰值）：它就是一张比例恰好等于盒比例的封面，
     * 两条解码器都走整图分支（不裁），因此传本票的解码器即可。
     */
    private fun normalCoverPlan(width: Int, targetWidthPx: Int) =
        CoverDecode.plan(width, width * 4 / 3, targetWidthPx, GRID, CROP)

    @Test
    fun `超长源按可见带解码 字节数与 3-4 封面同量级`() {
        val long = CoverDecode.plan(800, 8000, gridTarget, GRID, REGION)
        val normal = normalCoverPlan(800, gridTarget)
        assertTrue("源高宽比 10 的封面必须走可见带（整图子采样要 12.8MB）", long.region)
        assertTrue(
            "800×8000 的保留位图 ${long.retainedByteCount} 字节不得超过 3:4 封面 ${normal.retainedByteCount} 的 2 倍",
            long.retainedByteCount <= normal.retainedByteCount * 2,
        )
        assertTrue(
            "可见带横向分辨率 ${long.retainedWidth} 不得低于格宽 $gridTarget",
            long.retainedWidth >= gridTarget,
        )
    }

    @Test
    fun `保留位图等于显示盒需要的像素 不按源宽留内存`() {
        val long = CoverDecode.plan(800, 8000, gridTarget, GRID, REGION)
        assertEquals("保留宽度 = 目标宽度（512）", gridTarget, long.retainedWidth)
        assertEquals("保留高度 = 目标宽度 × 格比例（683）", 683, long.retainedHeight)
        assertTrue(
            "保留位图 ${long.retainedByteCount} 字节不得超过显示盒 ${gridBoxByteCount(gridTarget)} 字节",
            long.retainedByteCount <= gridBoxByteCount(gridTarget),
        )
        assertTrue(
            "带的瞬态 ${long.peakByteCount} 必须在上限 ${CoverDecode.BAND_PEAK_BUDGET_BYTES} 内",
            long.peakByteCount <= CoverDecode.BAND_PEAK_BUDGET_BYTES,
        )
        // 带本身仍是源分辨率（区域解码没有缩放能力），缩小只发生在保留那一步
        assertEquals("解出的带仍是源宽", 800, long.decodedWidth)
    }

    @Test
    fun `宽源长条封面 保留字节数不超同宽 3-4 封面的 2 倍`() {
        // 1080 宽的条漫首页：带按源宽解出后必须缩到显示盒，否则同宽 3:4 封面（sample=2）反而更小
        val long = CoverDecode.plan(1080, 15000, gridTarget, GRID, REGION)
        val normal = normalCoverPlan(1080, gridTarget)
        assertTrue("高宽比约 14 的封面必须走可见带", long.region)
        assertTrue(
            "1080×15000 的保留位图 ${long.retainedByteCount} 字节不得超过同宽 3:4 封面 ${normal.retainedByteCount} 的 2 倍",
            long.retainedByteCount <= normal.retainedByteCount * 2,
        )
        assertEquals("保留宽度 = 目标宽度", gridTarget, long.retainedWidth)
        assertTrue("可见带横向分辨率 ${long.retainedWidth} 不得低于格宽 $gridTarget", long.retainedWidth >= gridTarget)
    }

    @Test
    fun `三条宽源长条封面都走可见带 保留位图不超同宽 3-4 封面的 2 倍`() {
        // r3 评审 P1 点名的三条：改动前分别是 3.75× / 3.75× / 7.5×（整图子采样把源比例带进缓存）
        listOf(1080 to 5400, 2000 to 10000, 2048 to 20480).forEach { (width, height) ->
            val long = CoverDecode.plan(width, height, gridTarget, GRID, REGION)
            val normal = normalCoverPlan(width, gridTarget)
            assertTrue("${width}×$height 必须走可见带", long.region)
            assertTrue(
                "${width}×$height 的保留位图 ${long.retainedByteCount} 字节不得超过同宽 3:4 封面 " +
                    "${normal.retainedByteCount} 的 2 倍",
                long.retainedByteCount <= normal.retainedByteCount * 2,
            )
            assertTrue(
                "${width}×$height 的可见宽度 ${long.retainedWidth} 不得低于格宽 $gridTarget",
                long.retainedWidth >= gridTarget,
            )
            assertTrue(
                "${width}×$height 的瞬态 ${long.peakByteCount} 必须在上限 ${CoverDecode.BAND_PEAK_BUDGET_BYTES} 内",
                long.peakByteCount <= CoverDecode.BAND_PEAK_BUDGET_BYTES,
            )
        }
    }

    @Test
    fun `超宽源的带超过瞬态上限时退回整图子采样`() {
        // 4000×3000 的带 = 2250×3000（13.5MB）+ 缩小后的 0.7MB = 14.2MB > 上限 12.8MB：不能把那 12.8MiB
        // 的 OOM 换个形式换回来，退回票 #56 的整图子采样（s=4 → 保留 1.5MB）
        val wide = CoverDecode.plan(4000, 3000, gridTarget, GRID, REGION)
        assertTrue("超宽源的带超上限时必须走整图子采样", !wide.region)
        assertEquals("退回后保留位图 = 整图子采样（改动前的量）", 1_500_000, wide.retainedByteCount)
        assertTrue("上限必须低于这张源的带瞬态（否则这条用例的前提就不成立）", 14_199_392 > CoverDecode.BAND_PEAK_BUDGET_BYTES)
    }

    @Test
    fun `超长源的可见带贴显示盒比例且居中`() {
        val plan = CoverDecode.plan(800, 8000, gridTarget, GRID, REGION)
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
        assertEquals(
            "保留位图的比例同样是显示盒比例",
            CoverLayout.GRID_CELL_ASPECT.toDouble(),
            plan.retainedHeight.toDouble() / plan.retainedWidth,
            0.001,
        )
    }

    @Test
    fun `超宽源方向带宽更小 走整图子采样`() {
        // 8000×800：带缩到显示盒是 0.7MB，而整图子采样只有 0.2MB —— 保留位图更小的那条赢
        val wide = CoverDecode.plan(8000, 800, gridTarget, GRID, REGION)
        val normal = normalCoverPlan(800, gridTarget)
        assertTrue("横向带宽更小的源不得为裁剪多留内存", !wide.region)
        assertTrue(
            "8000×800 的保留位图 ${wide.retainedByteCount} 字节不得超过 3:4 封面 ${normal.retainedByteCount}",
            wide.retainedByteCount <= normal.retainedByteCount,
        )
        assertTrue("横向分辨率 ${wide.retainedWidth} 不低于格宽 $gridTarget", wide.retainedWidth >= gridTarget)
    }

    @Test
    fun `比例已是格比例的封面不裁 保持整图子采样`() {
        // 网格档：4:3 源与格比例一致 → 带与整图同义（没有裁掉任何像素），走能在解码时缩采的整图分支
        val grid = CoverDecode.plan(800, 1067, gridTarget, GRID, REGION)
        assertTrue("4:3 源在网格档不必裁剪", !grid.region)
        assertEquals(1, grid.sampleSize)
        assertEquals(800, grid.decodedWidth)
        assertEquals(1067, grid.decodedHeight)
        assertEquals("整图分支不缩放：解出即保留", grid.decodedByteCount, grid.retainedByteCount)
        assertEquals("整图分支的峰值就是它自己（没有第二份位图）", grid.retainedByteCount, grid.peakByteCount)
        // 列表档：56dp → 192px 桶，比例 1.33 在兜底区间内 → 不裁（与 CoverLayout.needsCrop 同口径）
        val list = CoverDecode.plan(800, 1067, 192, LIST, REGION)
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
            val plan = CoverDecode.plan(800, 8000, target, GRID, REGION)
            assertTrue("display=$displayWidth target=$target：必须走可见带", plan.region)
            assertTrue(
                "display=$displayWidth：横向分辨率不得低于桶宽与源宽中较小的那个",
                plan.retainedWidth >= minOf(800, target),
            )
            val normal = normalCoverPlan(800, target)
            assertTrue(
                "display=$displayWidth：保留位图 ${plan.retainedByteCount} 字节不得超过 3:4 封面 " +
                    "${normal.retainedByteCount} 的 2 倍",
                plan.retainedByteCount <= normal.retainedByteCount * 2,
            )
            assertTrue(
                "display=$displayWidth：瞬态 ${plan.peakByteCount} 必须在上限 ${CoverDecode.BAND_PEAK_BUDGET_BYTES} 内",
                plan.peakByteCount <= maxOf(CoverDecode.BAND_PEAK_BUDGET_BYTES, normal.peakByteCount),
            )
        }
    }

    @Test
    fun `任何源尺寸与两档口径下都满足带的三条入选规则`() {
        val sizes = listOf(
            1 to 1,
            100 to 100,
            800 to 1067,
            800 to 8000,
            8000 to 800,
            1080 to 5400,
            1600 to 16000,
            2000 to 10000,
            2048 to 20480,
            2200 to 220000,
            4000 to 3000,
            4000 to 20000,
            6000 to 20000,
        )
        listOf(192, 512, 1024).forEach { target ->
            sizes.forEach { (width, height) ->
                listOf(GRID, LIST).forEach { cropTarget ->
                    listOf(REGION, CROP).forEach { decoder ->
                        val plan = CoverDecode.plan(width, height, target, cropTarget, decoder)
                        // 区域解码那条路的结果（可能是区域带，也可能是第 3 条退掉后的整图分支）——第 4 条要跟它比
                        val regionPlan = if (decoder == CROP) {
                            CoverDecode.plan(width, height, target, cropTarget, REGION)
                        } else {
                            plan
                        }
                        val fullRetained = fullImageRetainedBytes(width, height, target)
                        val label = "${width}×$height target=$target $cropTarget $decoder"
                        assertTrue(
                            "$label：保留位图 ${plan.retainedByteCount} 不得大于整图子采样 $fullRetained" +
                                "（这条保证缓存占用不回退）",
                            plan.retainedByteCount <= fullRetained,
                        )
                        // 第 3 条：选中的计划（含裁剪解码的目标面）不得超过上限——即票面要的那道守卫
                        assertTrue(
                            "$label：瞬态 ${plan.peakByteCount} 不得超上限 " +
                                "${maxOf(CoverDecode.BAND_PEAK_BUDGET_BYTES, fullRetained)}",
                            plan.peakByteCount <= maxOf(CoverDecode.BAND_PEAK_BUDGET_BYTES, fullRetained),
                        )
                        if (decoder == CROP && plan.cropToTarget != null && regionPlan.region) {
                            // 第 4 条：选了裁剪解码就必须比**区域带**更小（否则就该退回区域带）。区域带自己也被
                            // 第 3 条退掉时（regionPlan 已是整图分支）无从比较，那种情况的保证在上面那条上限断言里
                            assertTrue(
                                "$label：选了裁剪解码（峰值 ${plan.peakByteCount}）就不得超过区域带" +
                                    "（${regionPlan.peakByteCount}）",
                                plan.peakByteCount <= regionPlan.peakByteCount,
                            )
                        }
                        if (plan.cropToTarget != null) {
                            // 第 4 条的另一半（票 #85 r2 评审 P1）：裁剪解码也不得比**改动前那条整图子采样**重
                            assertTrue(
                                "$label：裁剪解码的峰值 ${plan.peakByteCount} 不得超整图子采样 $fullRetained",
                                plan.peakByteCount <= fullRetained,
                            )
                        }
                        if (plan.region) {
                            assertTrue(
                                "$label：走带必须真的裁掉了像素（带 ${plan.width}×${plan.height} < 源 ${width}×$height）",
                                plan.width.toLong() * plan.height < width.toLong() * height,
                            )
                            assertTrue(
                                "$label：保留宽度 ${plan.retainedWidth} 不得超过目标宽度 $target（只缩不放）",
                                plan.retainedWidth <= target,
                            )
                            assertTrue(
                                "$label：保留位图必须比整图子采样小（否则没有走带的理由）",
                                plan.retainedByteCount < fullRetained,
                            )
                            if (decoder == CROP) assertCropRectInsideTarget(plan, label)
                        }
                    }
                }
            }
        }
    }

    // ---------- 裁剪 + 缩放一步解出显示盒（票 #85） ----------

    /**
     * 票 #85 里**真的走裁剪解码**的尺寸类：源宽 ≥ 2170px 的长条封面中，目标面比整图子采样更轻的那一档
     * （按源分辨率解带 = 2.667×宽² > 12.8MB 上限，裁剪解码一步解出目标面）。
     */
    private val cropTargetSources = listOf(4000 to 20000, 3000 to 12000)

    /** `setCrop` 的矩形必须落在 `setTargetSize` 的框内（AOSP 的 checkSubset），否则解码直接抛异常退回整图 */
    private fun assertCropRectInsideTarget(plan: CoverDecode.Plan, label: String) {
        val crop = plan.cropToTarget ?: return
        assertTrue(
            "$label：裁剪矩形右/下 （${crop.left + plan.retainedWidth}×${crop.top + plan.retainedHeight}）" +
                "不得越出目标尺寸 ${crop.targetWidth}×${crop.targetHeight}（越出会被 API 拒掉）",
            crop.left + plan.retainedWidth <= crop.targetWidth && crop.top + plan.retainedHeight <= crop.targetHeight,
        )
        assertTrue(
            "$label：裁剪矩形居中（左右边距差 ≤1px）",
            kotlin.math.abs((crop.targetWidth - plan.retainedWidth) - 2 * crop.left) <= 1,
        )
        assertTrue(
            "$label：裁剪矩形居中（上下边距差 ≤1px）",
            kotlin.math.abs((crop.targetHeight - plan.retainedHeight) - 2 * crop.top) <= 1,
        )
    }

    @Test
    fun `源宽 2170 以上的长条封面走裁剪解码 保留位图不超同宽 3-4 封面的 2 倍`() {
        cropTargetSources.forEach { (width, height) ->
            val long = CoverDecode.plan(width, height, gridTarget, GRID, CROP)
            val normal = normalCoverPlan(width, gridTarget)
            assertTrue("${width}×$height 必须走裁剪解码的带分支（保留量取不了源比例）", long.region)
            assertNotNull("${width}×$height 的带必须是裁剪 + 缩放一步", long.cropToTarget)
            assertTrue(
                "${width}×$height 的保留位图 ${long.retainedByteCount} 字节不得超过同宽 3:4 封面 " +
                    "${normal.retainedByteCount} 的 2 倍",
                long.retainedByteCount <= normal.retainedByteCount * 2,
            )
            assertTrue(
                "${width}×$height 的保留位图 ${long.retainedByteCount} 不得超过显示盒 ${gridBoxByteCount(gridTarget)}",
                long.retainedByteCount <= gridBoxByteCount(gridTarget),
            )
            assertTrue(
                "${width}×$height 的可见宽度 ${long.retainedWidth} 不得低于格宽 $gridTarget",
                long.retainedWidth >= gridTarget,
            )
        }
    }

    @Test
    fun `裁剪解码的瞬态峰值把目标面算进去 且不超本票要守的量级`() {
        // 峰值口径（票 #85 r1 评审 P2-1）：`setTargetSize` 交给解码器的是「整张源 × s」的目标面
        // （像素数 = 格宽² × 源高宽比，与源宽无关），它是解码器真建的一张位图，与区域带同一把尺子。
        cropTargetSources.forEach { (width, height) ->
            val long = CoverDecode.plan(width, height, gridTarget, GRID, CROP)
            val crop = long.cropToTarget!!
            assertEquals(
                "${width}×$height：峰值 = 解出的目标面 + 保留位图（两张同时在世）",
                long.decodedByteCount + long.retainedByteCount,
                long.peakByteCount,
            )
            assertEquals(
                "${width}×$height：解出的那张就是目标面",
                (crop.targetWidth * crop.targetHeight).toLong(),
                (long.decodedWidth * long.decodedHeight).toLong(),
            )
            assertTrue(
                "${width}×$height 的峰值 ${long.peakByteCount} 必须 ≤ 本票点名的 12.8MB 量级",
                long.peakByteCount <= CoverDecode.BAND_PEAK_BUDGET_BYTES,
            )
        }
        // 票面点名的那一条（4000×20000，即「源宽 ≥ 2170 一类」的头部尺寸）上「≤ 同宽 3:4 封面的 2 倍」成立。
        // 不能对该类里的每个尺寸都成立：参照值本身是「按 2 的幂子采样的 3:4 封面」（宽度可能只走到格宽的 1.5 倍，
        // 如 6000 → s=8 → 750px，峰值 1.5MB），而目标面成本是固定的 2 × 格宽² × 源高宽比（约 2.6MB）；
        // 目标面成本是固定的 2 × 格宽² × 源高宽比，因此这一档里偏窄的长条（2200×20000：峰值 5.47MB >
        // 2 × 参考峰值 1.61MB）不满足 2× 口径，它由「第 4 条：不超整图子采样（5.50MB）」兜住、仍走裁剪解码；
        // 目标面真正不划算的极长源（2200×220000：目标面 52MB）才退回区域带，见下一条用例。
        val headline = CoverDecode.plan(4000, 20000, gridTarget, GRID, CROP)
        val headlineNormal = normalCoverPlan(4000, gridTarget)
        assertTrue(
            "4000×20000 的峰值 ${headline.peakByteCount} 不得超过同宽 3:4 封面峰值 " +
                "${headlineNormal.peakByteCount} 的 2 倍",
            headline.peakByteCount <= headlineNormal.peakByteCount * 2,
        )
    }

    @Test
    fun `裁剪解码的峰值恒不超改动前的整图子采样`() {
        // 票 #85 r2 评审 P1 的实质要求（监督者 2026-09-20 核对更正：2200×20000 **自己**的「改动前」是
        // 整图子采样 550×5000×2 = 5,500,000 B；806,300 B 是同宽 3:4 参考封面的量，不是这条源的）：
        // 第 4 条要求 CropToTarget 同时轻过区域带与整图子采样，因此只要它被选中就恒不比改动前重，
        // 而它的保留位图 = 显示盒，比整图子采样小得多。反过来，目标面真不划算的源就不选它。
        listOf(2200 to 20000, 4000 to 20000, 3000 to 12000).forEach { (width, height) ->
            val long = CoverDecode.plan(width, height, gridTarget, GRID, CROP)
            val baseline = fullImageRetainedBytes(width, height)
            assertNotNull("${width}×$height 必须走裁剪解码（目标面比整图子采样轻）", long.cropToTarget)
            assertTrue(
                "${width}×$height 的峰值 ${long.peakByteCount} 必须 ≤ 同时的整图子采样峰值 $baseline",
                long.peakByteCount <= baseline,
            )
            assertTrue(
                "${width}×$height 的保留位图 ${long.retainedByteCount} 必须 < 整图子采样保留量 $baseline",
                long.retainedByteCount < baseline,
            )
        }
        // 两条验收口径在这一档的实情：保留位图 ≤ 2× 同宽 3:4 封面（AC#1 成立）；峰值只保证「不超改动前」——
        // 票面那个 2× 参考峰值的口径在窄长源上不成立（2200×20000：5,466,112 vs 2 × 806,300 = 1,612,600），
        // 机制原因见 SPEC：`setCrop` 的坐标在缩放后空间 ⇒ 目标面必然是「整张源 × s」。
        val mid = CoverDecode.plan(2200, 20000, gridTarget, GRID, CROP)
        val midNormal = normalCoverPlan(2200, gridTarget)
        assertTrue(
            "2200×20000 的保留位图 ${mid.retainedByteCount} 不得超过同宽 3:4 封面 " +
                "${midNormal.retainedByteCount} 的 2 倍（AC#1）",
            mid.retainedByteCount <= midNormal.retainedByteCount * 2,
        )
        // 前提：这张源的区域带确实超上限（所以改动前才会退回整图子采样）
        assertTrue(
            "2200×20000 的区域带必须超上限（否则这条用例的前提不成立）",
            !CoverDecode.plan(2200, 20000, gridTarget, GRID, REGION).region,
        )
    }

    @Test
    fun `裁剪解码解出的是目标面 保留仍是显示盒尺寸`() {
        val long = CoverDecode.plan(4000, 20000, gridTarget, GRID, CROP)
        val crop = long.cropToTarget!!
        assertEquals("解出的那张 = 目标面宽", crop.targetWidth, long.decodedWidth)
        assertEquals("解出的那张 = 目标面高", crop.targetHeight, long.decodedHeight)
        assertEquals("目标面与源同比例（4000×20000 × s=0.128）", 2560, long.decodedHeight)
        assertEquals("保留仍是显示盒宽（目标宽度）", gridTarget, long.retainedWidth)
        assertEquals("保留仍是显示盒高（格比例）", 683, long.retainedHeight)
        assertTrue(
            "解出的字节数 %d 不按源分辨率（42.7MB）算".format(long.decodedByteCount),
            long.decodedByteCount < 3_000_000,
        )
        assertEquals(
            "峰值 = 目标面 + 保留（票 #85 r1 评审 P2-1：不再只算保留位图）",
            long.decodedByteCount + long.retainedByteCount,
            long.peakByteCount,
        )
    }

    @Test
    fun `裁剪解码的目标面比区域带更大时退回区域解码`() {
        // 票 #85 r1 评审的真瞬态回归：800×8000 的裁剪解码目标面 = 512×5120 = 5.2MB，
        // 比改动前的区域带（800×1067 = 1.7MB）大 3 倍——第 4 条按同一把尺子选了区域带。
        val long = CoverDecode.plan(800, 8000, gridTarget, GRID, CROP)
        val region = CoverDecode.plan(800, 8000, gridTarget, GRID, REGION)
        assertNull("目标面更大时不得走裁剪解码", long.cropToTarget)
        assertEquals("这时两条解码器给出同一份计划（区域带）", region, long)
        assertTrue(
            "区域带的峰值 ${region.peakByteCount} 必须小于裁剪解码的目标面成本",
            region.peakByteCount < 512L * 5120 * CoverDecode.BITMAP_BYTES_PER_PIXEL,
        )
        // 源高宽比很大时（2200×220000 → 目标面 52MB）同理：区域带更小，同样不走裁剪解码
        val extreme = CoverDecode.plan(2200, 220000, gridTarget, GRID, CROP)
        assertNull("目标面 52MB 的源不得走裁剪解码", extreme.cropToTarget)
        assertTrue(
            "退回后的峰值 ${extreme.peakByteCount} 仍满足第 3 条口径（不超上限与整图子采样的较大者）",
            extreme.peakByteCount <= maxOf(
                CoverDecode.BAND_PEAK_BUDGET_BYTES,
                fullImageRetainedBytes(2200, 220000),
            ),
        )
    }

    /** 整图子采样（票 #56 口径）的保留字节数 = 第 3 条上限里的「替代它的整图子采样」那一项 */
    private fun fullImageRetainedBytes(width: Int, height: Int, targetWidthPx: Int = gridTarget): Int {
        val sample = CoverDecode.sampleSizeForFullImage(width, targetWidthPx)
        return (width / sample) * (height / sample) * CoverDecode.BITMAP_BYTES_PER_PIXEL
    }

    @Test
    fun `裁剪解码的目标尺寸是整张源按带-盒比例缩放 矩形居中且落在框内`() {
        // 4000×20000 的带 = 4000×5333（源宽满宽），保留 512×683：比例 s = 512/4000 = 0.128
        val long = CoverDecode.plan(4000, 20000, gridTarget, GRID, CROP)
        val crop = long.cropToTarget!!
        assertEquals("目标宽 = 源宽 × s", gridTarget, crop.targetWidth)
        assertEquals("目标高 = 源高 × s", (20000 * 0.128f).roundToInt(), crop.targetHeight)
        assertEquals("横向不裁时矩形贴左", 0, crop.left)
        assertEquals("纵向居中", (crop.targetHeight - long.retainedHeight) / 2, crop.top)
        assertCropRectInsideTarget(long, "4000×20000")
        // 横向被裁的源（带宽 < 源宽）：目标面按第 4 条要同时轻过区域带与整图子采样才选中——3000×2000 这个
        // 尺寸上整图子采样只要 0.75MB（750×500），比目标面（1024×683 ≈ 1.4MB）轻，因此不走裁剪解码；
        // 区域带仍按第 1〜3 条被选中（保留 0.7MB < 整图 0.75MB）。目标面的横向矩形因此只在「裁宽 + 目标面
        // 更轻」的组合上才出现（当前尺寸类里选不到），居中/不越框的断言由矩阵用例逐尺寸覆盖。
        val wide = CoverDecode.plan(3000, 2000, gridTarget, GRID, CROP)
        assertNull("宽源的目标面不比整图子采样轻，不得走裁剪解码", wide.cropToTarget)
        assertTrue("区域带照旧被选中（保留位图比整图子采样小）", wide.region)
    }

    @Test
    fun `裁剪解码不改入选规则 同比例与超宽源照旧走整图子采样`() {
        // ④ 票 #81 的第 1 条：比例已等于盒比例的封面不走带（整图分支能在解码时缩采）
        assertTrue("4:3 源在网格档仍不必裁", !CoverDecode.plan(800, 1067, gridTarget, GRID, CROP).region)
        // ⑤ 票 #81 的第 2 条：超宽源方向带的保留位图更大，仍走整图子采样
        assertTrue("8000×800 的带保留 0.7MB > 整图子采样 0.2MB", !CoverDecode.plan(8000, 800, gridTarget, GRID, CROP).region)
    }

    @Test
    fun `API 26-27 的区域解码仍受瞬态上限约束`() {
        // 同一条 4000×20000 在 BitmapRegionDecoder（解出即源分辨率）下仍被上限挡住、退回整图子采样，
        // 保留量与改动前一致（票 #85 不动 API 26/27 的行为，只把它写清楚）
        val region = CoverDecode.plan(4000, 20000, gridTarget, GRID, REGION)
        assertTrue("源分辨率的带 42.7MB 超上限，必须退回整图子采样", !region.region)
        assertEquals("退回后保留 = 整图子采样（与改动前一致）", 10_000_000, region.retainedByteCount)
        assertTrue(
            "上限必须低于这张源的带瞬态（否则这条用例的前提不成立）",
            (4000L * 5333 * CoverDecode.BITMAP_BYTES_PER_PIXEL) > CoverDecode.BAND_PEAK_BUDGET_BYTES,
        )
        // 上限之内的长条源在两条解码器下都走带（票 #81 的行为不变）
        assertTrue("2048×20480 的带 11.9MB 在预算内", CoverDecode.plan(2048, 20480, gridTarget, GRID, REGION).region)
        assertTrue("同一条源在裁剪解码下也走带", CoverDecode.plan(2048, 20480, gridTarget, GRID, CROP).region)
    }
}
