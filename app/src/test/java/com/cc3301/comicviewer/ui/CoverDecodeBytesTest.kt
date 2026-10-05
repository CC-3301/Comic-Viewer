package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CELL_HORIZONTAL_PADDING_BEFORE_60
import com.cc3301.comicviewer.core.view.gridBoxByteCount
import com.cc3301.comicviewer.core.view.gridCellWidth
import com.cc3301.comicviewer.core.view.highFrequencyRms
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 封面解码的真实位图字节数。
 *
 * [CoverDecodeTest] 锁的是纯函数口径（计划里的保留字节数），这里补上端到端的一环：真的过
 * [PageDecoder.decodeCoverBytes] 解出位图，再数 `allocationByteCount`——验的是「裁剪解码这条路
 * 真的按显示盒留内存」，而不是只验算数。
 *
 * `GraphicsMode.NATIVE` 走的是 AOSP 原生的 `BitmapFactory`/`BitmapRegionDecoder`/`ImageDecoder`（不是
 * Robolectric 的影子实现：影子会忽略区域解码的 `inSampleSize` 之类的语义），因此这里量到的字节数与设备同量级，
 * 也能验「交付给 `ImageDecoder` 的 `setCrop` + `setTargetSize` 几何是否真的落到了那块源区域上」。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverDecodeBytesTest {

    private val grid = CoverDecode.CropTarget.GridCell
    private val list = CoverDecode.CropTarget.OwnAspect

    /** 本机（Robolectric 的 API 34）走裁剪解码；API 26/27 的退路在下面两个用例里单独验 */
    private val crop = CoverDecode.BandDecoder.CropToTarget
    private val region = CoverDecode.BandDecoder.Region

    /**
     * 网格 2 列、3.0 密度、生产**改成 20dp 那一次之前**的水平外边距（[CELL_HORIZONTAL_PADDING_BEFORE_60] = 12dp）下的桶 = 512px。
     * 本文件量的是「裁剪解码真的按显示盒留内存」的比值与量级，与桶的具体值无关，故沿用改动前的场景、不重算
     * （**出货几何** = 格宽 157dp / 桶 480，由 `CoverDecodeTest.出货格宽 157dp…` 记录）。
     */
    private val gridTarget =
        CoverDecode.targetWidthPx(gridCellWidth(360f, 2, CELL_HORIZONTAL_PADDING_BEFORE_60, 6f) * 3f)

    private fun key(name: String, cropTarget: CoverDecode.CropTarget) =
        CoverDecode.key(name, 0, gridTarget, cropTarget)

    private fun decode(name: String, bytes: ByteArray, targetWidthPx: Int, cropTarget: CoverDecode.CropTarget) =
        PageDecoder.decodeCoverBytes(key(name, cropTarget), bytes, targetWidthPx, cropTarget)

    private fun bytesOf(bitmap: ImageBitmap) =
        bitmap.asAndroidBitmap().allocationByteCount

    @Test
    fun `800x8000 封面的位图字节数与 3-4 封面同量级`() {
        val long = decode("long", png(800, 8000), gridTarget, grid)
        val normal = decode("normal", png(800, 1067), gridTarget, grid)
        assertNotNull("超长封面必须解得出位图", long)
        assertNotNull("3:4 封面必须解得出位图", normal)
        val longBytes = bytesOf(long!!)
        val normalBytes = bytesOf(normal!!)
        assertTrue(
            "800×8000 解出 $longBytes 字节，3:4 封面解出 $normalBytes 字节：不得超过其 2 倍",
            longBytes <= normalBytes * 2,
        )
        assertTrue("可见带横向 ${long.width}px 不得低于格宽 $gridTarget", long.width >= gridTarget)
        assertTrue("可见带只解显示盒需要的高度（不是 8000px）", long.height < 8000)
        // 绝对上界：保留位图的像素量级只与**显示盒**有关（非源尺寸），长条封面因此不再随源高增长
        val boxBytes = gridBoxByteCount(gridTarget)
        assertTrue("解出 $longBytes 字节，不得超过显示盒 $boxBytes 字节", longBytes <= boxBytes)
    }

    @Test
    fun `宽源长条封面 保留字节数不超同宽 3-4 封面的 2 倍`() {
        // 1080 宽的条漫首页：区域解码按源宽解出的带必须缩到显示盒
        val long = decode("wide-long", png(1080, 15000), gridTarget, grid)
        val normal = decode("wide-normal", png(1080, 1440), gridTarget, grid)
        assertNotNull(long)
        assertNotNull(normal)
        val longBytes = bytesOf(long!!)
        val normalBytes = bytesOf(normal!!)
        assertTrue(
            "1080×15000 解出 $longBytes 字节，同宽 3:4 封面解出 $normalBytes 字节：不得超过其 2 倍",
            longBytes <= normalBytes * 2,
        )
        assertTrue("可见带横向 ${long.width}px 不得低于格宽 $gridTarget", long.width >= gridTarget)
        assertEquals("保留位图宽度 = 目标宽度（只缩到显示盒，不按源宽留内存）", gridTarget, long.width)
    }

    @Test
    fun `保留位图字节数等于计划的字节口径`() {
        // 计划的字节口径（`BITMAP_BYTES_PER_PIXEL`）必须与真解出的位图一致，否则选分支的算数是错的；
        // 计划也用**本台设备**那条解码器（`crop`，与 `decodeCoverBytes` 内部一致）
        val long = decode("plan-long", png(800, 8000), gridTarget, grid)
        val normal = decode("plan-normal", png(800, 1067), gridTarget, grid)
        assertNotNull(long)
        assertNotNull(normal)
        assertEquals(
            "可见带分支：计划保留字节数必须等于真解出的字节数",
            CoverDecode.plan(800, 8000, gridTarget, grid, crop).retainedByteCount,
            bytesOf(long!!),
        )
        assertEquals(
            "整图分支：计划保留字节数必须等于真解出的字节数",
            CoverDecode.plan(800, 1067, gridTarget, grid, crop).retainedByteCount,
            bytesOf(normal!!),
        )
    }

    @Test
    fun `裁剪解码解不出时退回整图子采样且仍能出图`() {
        val bytes = png(800, 8000)
        val full = PageDecoder.decodeCoverBytes(
            key("fallback", grid),
            bytes,
            gridTarget,
            grid,
            bandDecoder = { _, _, _ -> null },
        )
        assertNotNull("退路必须仍出图（不崩、不空白）", full)
        // 退路 = 整图子采样（800 宽源在 512 桶下 sample=1），即放弃裁剪解码的收益；这里钉住它确实发生了
        assertEquals("退路解出整图宽", 800, full!!.width)
        assertEquals("退路解出整图高（长条漫封面照旧整张解出）", 8000, full.height)
        assertTrue(
            "退路字节数 ${bytesOf(full)} 必须大于可见带方案的 " +
                "${CoverDecode.plan(800, 8000, gridTarget, grid, crop).retainedByteCount}",
            bytesOf(full) > CoverDecode.plan(800, 8000, gridTarget, grid, crop).retainedByteCount,
        )
        assertSame("退路结果同样入缓存", full, PageDecoder.cachedCover(key("fallback", grid)))
    }

    @Test
    fun `超宽封面不整张 1-1 解码`() {
        val wide = decode("wide", png(8000, 800), gridTarget, grid)
        assertNotNull(wide)
        val wideBytes = bytesOf(wide!!)
        val normalBytes = bytesOf(decode("normal", png(800, 1067), gridTarget, grid)!!)
        assertTrue("8000×800 解出 $wideBytes 字节，不得超过 3:4 封面的 $normalBytes", wideBytes <= normalBytes)
        assertTrue("横向分辨率 ${wide.width}px 不低于格宽 $gridTarget", wide.width >= gridTarget)
    }

    @Test
    fun `列表档大比例封面走去网点分支并裁到显示盒`() {
        // 列表档行内封面列宽 56dp（BrowserScreen.LIST_COVER_WIDTH，私有常量，同 CoverDecodeTest 用字面量代）
        val listTarget = CoverDecode.targetWidthPx(56f * 3f)  // 192px 桶
        val cover = decode("list-normal", png(800, 1067), listTarget, list)
        assertNotNull(cover)
        // 缩小比 800/192 ≥ 1.5 ⇒ 去网点分支（整源解码 → 模糊 → 裁带缩）
        assertNotNull(
            "列表档大比例封面应当去网点",
            CoverDecode.plan(800, 1067, listTarget, list, crop).descreen,
        )
        // 保留位图就是显示盒（192×256），不再是原先那张 200×267 的整图子采样
        assertEquals(192, cover!!.width)
        assertEquals(256, cover.height)
    }

    @Test
    fun `同一解码键命中内存缓存 不同裁剪目标不共用缓存`() {
        val bytes = png(800, 1067)
        val first = decode("cached", bytes, gridTarget, grid)
        assertNotNull(first)
        assertSame("同键必须命中同一张位图（不得重解）", first, PageDecoder.cachedCover(key("cached", grid)))
        assertNull("另一档的键不得命中这一档的位图（不串图）", PageDecoder.cachedCover(key("cached", list)))
    }

    @Test
    fun `GIF 封面仍解得出静态首帧`() {
        // 格式与裁剪路线的兼容性：解不出时解码器会退回整图子采样，结果必须照常可用
        val cover = decode("gif", gif(4, 40), 32, grid)
        assertNotNull(cover)
        assertTrue("GIF 首帧必须解出像素", cover!!.width > 0 && cover.height > 0)
    }

    // ---------- 裁剪 + 缩放一步解出显示盒 ----------

    @Test
    fun `4000x20000 封面按裁剪解码 保留位图不超同宽 3-4 封面的 2 倍`() {
        val long = decode("huge-long", streamPng(4000, 20000), gridTarget, grid)
        val normal = decode("huge-normal", streamPng(4000, 5333), gridTarget, grid)
        assertNotNull("4000×20000 必须解得出位图（不再被 12.8MB 上限挡住）", long)
        assertNotNull("同宽 3:4 封面必须解得出位图", normal)
        assertNotNull(
            "这一尺寸类必须走裁剪 + 缩放一步（源分辨率的带是 42.7MB）",
            CoverDecode.plan(4000, 20000, gridTarget, grid, crop).cropToTarget,
        )
        val longBytes = bytesOf(long!!)
        assertEquals("解出 = 计划里的保留位图（显示盒尺寸）", CoverDecode.plan(4000, 20000, gridTarget, grid, crop).retainedByteCount, longBytes)
        assertEquals("保留宽度 = 格宽桶", gridTarget, long.width)
        assertEquals("保留高度 = 格比例", 683, long.height)
        assertTrue(
            "4000×20000 解出 $longBytes 字节，同宽 3:4 封面解出 ${bytesOf(normal!!)} 字节：不得超过其 2 倍",
            longBytes <= bytesOf(normal) * 2,
        )
        assertTrue("不得超显示盒 ${gridBoxByteCount(gridTarget)} 字节", longBytes <= gridBoxByteCount(gridTarget))
    }

    @Test
    fun `裁剪解码的可见区域与 ContentScale-Crop 逐项一致`() {
        // 位置编码图（R = x/W、G = y/H）：解出的像素能反推它是源的哪一块，从而验「交付给 setCrop 的几何
        // 真的落在居中带上」——同一条带按显示盒比例缩到盒子里，就是 Compose ContentScale.Crop 的画面。
        // 尺寸选 90×200：缩小比 90/64 = 1.41 < 1.5（不去网点，仍走裁剪解码——大比例源会被去网点强制
        // 回区域带，插不进这组断言），且裁剪解码的目标面比区域带轻（90×200 的目标面 64×142 < 区域带 90×120）。
        // 期望值是**由源尺寸 + 盒比例（4:3）独立算出来的常量**（不从 plan 取）：90×200 的源高宽比 2.22 > 4/3
        // ⇒ 裁高不裁宽 ⇒ 带 90×120、带左上 (0,40)；目标 64 桶 ⇒ 盒 64×85。
        val width = 90
        val height = 200
        val target = 64
        val bandLeft = 0
        val bandTop = 40           // (200 - 120) / 2
        val bandWidth = 90
        val bandHeight = 120       // round(90 × 4/3)
        val boxWidth = 64
        val boxHeight = 85         // round(64 × 4/3)
        // 先确认计划就是这条带（否则下面的期望值没有意义）——这一条是「计划 = 独立复算」的正向交叉校验
        val plan = CoverDecode.plan(width, height, target, grid, crop)
        assertNotNull("这一尺寸必须走裁剪解码", plan.cropToTarget)
        assertEquals(bandLeft, plan.left)
        assertEquals(bandTop, plan.top)
        assertEquals(bandWidth, plan.width)
        assertEquals(bandHeight, plan.height)
        assertEquals(boxWidth, plan.retainedWidth)
        assertEquals(boxHeight, plan.retainedHeight)
        val decoded = decode("crop-content", gradientPng(width, height), target, grid)
        assertNotNull(decoded)
        val bitmap = decoded!!.asAndroidBitmap()
        assertEquals("盒宽", boxWidth, bitmap.width)
        assertEquals("盒高", boxHeight, bitmap.height)
        // 逐项：输出像素中心对应的源坐标（居中带内线性映射）与该像素的 R/G（源上的位置编码）比
        val scaleX = bandWidth.toFloat() / boxWidth
        val scaleY = bandHeight.toFloat() / boxHeight
        listOf(0, boxWidth / 2, boxWidth - 1).forEach { col ->
            listOf(0, boxHeight / 2, boxHeight - 1).forEach { row ->
                val expectedR = (bandLeft + (col + 0.5f) * scaleX) / (width - 1) * 255
                val expectedG = (bandTop + (row + 0.5f) * scaleY) / (height - 1) * 255
                val pixel = bitmap.getPixel(col, row)
                // 容差按 RGB_565 的量化步长 + 采样相位（半像素）定，本测例的判别力就在这个量级：
                // R 只有 5 位（步长 255/31 ≈ 8.2）→ 7；G 是 6 位（步长 255/63 ≈ 4.05）→ 5
                // （本图最大偏离：R 7、G 4.1，缩放比 1.4 时半像素相位折进 G 后比 4 略大）
                assertWithin(7, "($col,$row)", expectedR, Color.red(pixel).toFloat(), "R（横向位置）")
                assertWithin(5, "($col,$row)", expectedG, Color.green(pixel).toFloat(), "G（纵向位置）")
            }
        }
        // 这条带的纵向跨度（不能是整张源、也不能是别的带）：带高/源高 × 255 = 153（容差同 G：两个量化值相减）
        val topRow = Color.green(bitmap.getPixel(0, 0)).toFloat()
        val bottomRow = Color.green(bitmap.getPixel(0, boxHeight - 1)).toFloat()
        assertWithin(5, "纵向跨度", bandHeight.toFloat() / (height - 1) * 255, bottomRow - topRow, "带高")
        assertTrue("横向必须满宽（带宽 = 源宽）", Color.red(bitmap.getPixel(boxWidth - 1, 0)) >= 240)
        // 判别力（写明而不是假高）：G 的 4/255 ≈ 1/4 个 565 绿色级 ≈ 32 源行 ≈ 5 输出行（帯高 85 行）；
        // 因此这条用例能抓住「取成整张源」「取成别的带」「取上下颠倒」这一类，但不宣称亚像素居中。
    }

    @Test
    fun `带 alpha 的封面也解成显示盒尺寸的 RGB-565`() {
        // ImageDecoder 的 LOW_RAM 只对不透明源给 RGB_565（PNG 带 alpha 时会给 ARGB_8888），
        // 解码器因此要转一次 565，否则保留位图翻倍、与 [CoverDecode.BITMAP_BYTES_PER_PIXEL] 的口径不符。
        // 尺寸同上（90×200@64）：缩小比 < 1.5 且裁剪解码被选中，走 ImageDecoder 通路。
        val decoded = decode("alpha", gradientPng(90, 200, transparentLeftHalf = true), 64, grid)
        assertNotNull(decoded)
        assertEquals(Bitmap.Config.RGB_565, decoded!!.asAndroidBitmap().config)
        assertEquals(64, decoded.width)
        assertEquals(
            "alpha 源也要按显示盒尺寸（1 像素 2 字节）留内存",
            CoverDecode.plan(90, 200, 64, grid, crop).retainedByteCount,
            bytesOf(decoded),
        )
    }

    @Test
    fun `API 26-27 没有 ImageDecoder 时仍按区域解码出图`() {
        // API 门槛只由 coverBandDecoder 判（单一入口）：计划与解码器看到的是同一个值
        assertEquals("API 26 只有 BitmapRegionDecoder", region, PageDecoder.coverBandDecoder(26))
        assertEquals("API 27 只有 BitmapRegionDecoder", region, PageDecoder.coverBandDecoder(27))
        assertEquals("API 28 起有 ImageDecoder", crop, PageDecoder.coverBandDecoder(28))
        assertEquals(crop, PageDecoder.coverBandDecoder(android.os.Build.VERSION.SDK_INT))
        val bytes = png(800, 8000)
        // 注入的 sdkInt 与宿主（34）不一致时，行为也由这个判定单一决定：接缝拿到的就是 coverBandDecoder 的值，
        // 且计划里的裁剪几何与它一致——不会出现「计划里给了裁剪几何、解码器却另按宿主 API 静默回退」
        listOf(26, 27, 28, 34).forEach { injected ->
            var planSeen: CoverDecode.Plan? = null
            var decoderSeen: CoverDecode.BandDecoder? = null
            PageDecoder.decodeCoverBytes(key("api-$injected", grid), bytes, gridTarget, grid, sdkInt = injected) { _, plan, decoder ->
                planSeen = plan
                decoderSeen = decoder
                null
            }
            assertEquals("sdkInt=$injected：接缝收到的判定", PageDecoder.coverBandDecoder(injected), decoderSeen)
            assertTrue(
                "sdkInt=$injected：计划里的裁剪几何不得越过接缝判定",
                planSeen!!.cropToTarget == null || decoderSeen == crop,
            )
            if (decoderSeen == region) {
                assertNull("sdkInt=$injected：区域解码上不得给出裁剪几何", planSeen!!.cropToTarget)
            }
        }
        // 真的解一次（sdkInt=26 走区域解码 + 缩到显示盒）：照常出图
        val decoded = PageDecoder.decodeCoverBytes(key("api26-real", grid), bytes, gridTarget, grid, sdkInt = 26)
        assertNotNull("API 26/27 退路必须仍出图", decoded)
        assertEquals("退路同样只留显示盒尺寸", gridTarget, decoded!!.width)
        // 同一条 4000×20000：26/27 上区域带的瞬态超上限 → 退回整图子采样（保留按源比例，与改动前一致）；
        // 28+ 才走裁剪 + 缩放一步（保留位图 = 显示盒）
        val big = streamPng(4000, 20000)
        val old = PageDecoder.decodeCoverBytes(key("api26-big", grid), big, gridTarget, grid, sdkInt = 26)
        assertNotNull("API 26/27 上长条封面仍须出图", old)
        assertEquals("退回整图子采样：4000/4 = 1000px", 1000, old!!.width)
        val modern = PageDecoder.decodeCoverBytes(key("api34-big", grid), big, gridTarget, grid)
        assertNotNull("API 28+ 上按裁剪 + 缩放一步出图", modern)
        assertEquals("保留位图 = 显示盒宽", gridTarget, modern!!.width)
        assertEquals("保留位图 = 显示盒高", 683, modern.height)
    }

    // ---------- 去网点（descreen） ----------

    /**
     * 带印刷网点的带位图像素：灰底 100 + 每 3px 一条 45° 亮线（亮 160，振幅 60）——
     * 实体书扫描带进的那种 2.5px 间距 45° 取向规则点阵的 mimic。斑点量尺 = [highFrequencyRms]。
     */
    private fun dottedPixels(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if ((x + y) % 3 == 0) 160 else 100
                pixels[y * width + x] = Color.argb(255, v, v, v)
            }
        }
        return pixels
    }

    private fun dotsRms(bitmap: Bitmap): Double = highFrequencyRms(bandToPixels(bitmap), bitmap.width, bitmap.height)

    private fun bandToPixels(bitmap: Bitmap): IntArray {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return px
    }

    @Test
    fun `大缩小比封面去网点后 5px 网点被抹平`() {
        // 合成源模仿真源实测的网点：5px 间距的规则点阵（真源自相关峰在 lag 5px、强度 0.79）
        val bytes = dottedGridPng(1600, 2400, period = 5)
        // 计划确定走去网点（缩小比 1600/512 = 3.125 → σ=1.25）
        val plan = CoverDecode.plan(1600, 2400, gridTarget, grid, crop)
        assertNotNull("1600×2400 应当去网点", plan.descreen)
        val decoded = decode("descreen-lattice", bytes, gridTarget, grid)
        assertNotNull(decoded)
        assertEquals("保留位图 = 显示盒宽", gridTarget, decoded!!.width)
        // 现状参照（测试侧独立复刻：整图解码 → 裁带 → 缩，不模糊；两步都本地可信）
        val reference = referenceBandScale(bytes, 1600, 2400, gridTarget)
        val refRms = dotsRms(reference)
        val outRms = dotsRms(decoded.asAndroidBitmap())
        assertTrue("现状参照的网点残差必须显著（判别力前提）：$refRms", refRms > 15.0)
        assertTrue("去网点后的残差 $outRms 必须远低于现状 $refRms", outRms < refRms * 0.25)
        // 绝对上界只作「模糊彻底没跑」的回归护栏（没跑时就是参照那一档 20+）；
        // 合成的 5px 点阵比真源网点更硬（单像素、130 级对比度），残余主要是 565 量化与少量网点
        assertTrue("去网点后的残差 $outRms 应远低于参照量级", outRms < 10.0)
        // 值域不变式：窗口取错/通道溢出会立刻越界或出现黑条纹（源值域 60..190）
        val px = bandToPixels(decoded.asAndroidBitmap())
        val minGray = px.minOf { (it ushr 16) and 0xFF }
        val maxGray = px.maxOf { (it ushr 16) and 0xFF }
        assertTrue("输出值域 [$minGray,$maxGray] 必须落在源值域 [60,190] 内", minGray >= 59 && maxGray <= 191)
        // 直流不变式：网点被抹成均匀灰、画面不变亮也不变暗。参照取**源带实测均值**（RGB_565 下 190/60
        // 已各自量化过），容差取输出再量化的半档（红通道 5 位、步长 ≈8.2 ⇒ 4.1）
        val sourceMean = bandToPixels(referenceBandSource(bytes, 1600, 2400))
            .map { (it ushr 16) and 0xFF }.average()
        val outputMean = px.map { (it ushr 16) and 0xFF }.average()
        assertEquals("输出均值必须贴着源带均值（除数/归一化没串）", sourceMean, outputMean, 4.5)
    }

    /** 源带（整图解码后按显示盒居中裁出的那条带）：直流不变式的参照 */
    private fun referenceBandSource(bytes: ByteArray, srcWidth: Int, srcHeight: Int): Bitmap {
        val full = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
        )!!
        val boxAspect = 4f / 3f
        val bandHeight = (srcWidth * boxAspect).roundToInt().coerceAtMost(srcHeight)
        return Bitmap.createBitmap(full, 0, (srcHeight - bandHeight) / 2, srcWidth, bandHeight)
    }

    /**
     * **现状（不去网点）的独立复刻**：整图解码（`BitmapFactory`，本地可信）→ 按显示盒裁居中带 → 缩到显示盒。
     * 几何在测试侧独立复算（不从生产计划取），用于与去网点结果对比。
     */
    private fun referenceBandScale(bytes: ByteArray, srcWidth: Int, srcHeight: Int, targetWidthPx: Int): Bitmap {
        val full = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
        )!!
        val boxAspect = 4f / 3f
        val bandHeight = if (srcHeight.toFloat() / srcWidth > boxAspect) {
            (srcWidth * boxAspect).roundToInt()
        } else {
            srcHeight
        }
        val bandWidth = if (srcHeight.toFloat() / srcWidth < boxAspect) {
            (srcHeight / boxAspect).roundToInt()
        } else {
            srcWidth
        }
        val band = Bitmap.createBitmap(
            full,
            (srcWidth - bandWidth) / 2,
            (srcHeight - bandHeight) / 2,
            bandWidth,
            bandHeight,
        )
        val retainedWidth = minOf(bandWidth, targetWidthPx)
        return Bitmap.createScaledBitmap(band, retainedWidth, retainedWidth * bandHeight / bandWidth, true)
    }

    /** 5px 间距规则点阵的 PNG（真源网点的 mimic）：灰底 190 + 点处 60 */
    private fun dottedGridPng(width: Int, height: Int, period: Int): ByteArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x % period == 0 && y % period == 0) 60 else 190
                pixels[y * width + x] = Color.argb(255, v, v, v)
            }
        }
        return ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    @Test
    fun `重采样窗口映射正确 边缘不偏`() {
        // 位置编码图（R = x 向线性梯度、G = y 向线性梯度）：窗口是矩形平均 ⇒ 线性梯度上等于窗口两端的中点。
        // 这条用例盯的是「窗口起点/样本数/除数」这组索引：数错或除错（例如边缘按整窗口宽除）会让首末行/列明显偏。
        val width = 800
        val height = 1200
        val target = 512
        val plan = CoverDecode.plan(width, height, target, grid, crop)
        val descreen = plan.descreen!!
        assertNotNull("800×1200 在 512 桶下应当去网点", descreen)
        val decoded = decode("resample-map", gradientPng(width, height), target, grid)!!.asAndroidBitmap()
        assertEquals(plan.retainedWidth, decoded.width)
        assertEquals(plan.retainedHeight, decoded.height)
        val kernelX = descreen.kernelWidth(plan.width.toFloat() / decoded.width)
        val kernelY = descreen.kernelWidth(plan.height.toFloat() / decoded.height)
        // 横向：源列坐标（带内）→ 期望 R
        val scaleX = plan.width.toDouble() / decoded.width
        listOf(0, decoded.width / 2, decoded.width - 1).forEach { ox ->
            val cx = ((ox + 0.5) * scaleX - 0.5).roundToInt()
            val start = (cx - kernelX / 2).coerceIn(0, plan.width - 1)
            val end = (cx + kernelX / 2).coerceIn(0, plan.width - 1)
            val expectedR = (plan.left + (start + end) / 2.0) / (width - 1) * 255
            assertWithin(
                7,
                "列 $ox",
                expectedR.toFloat(),
                Color.red(decoded.getPixel(ox, decoded.height / 2)).toFloat(),
                "R（窗口映射）",
            )
        }
        // 纵向：源行坐标（带内 + 带的偏移）→ 期望 G
        val scaleY = plan.height.toDouble() / decoded.height
        listOf(0, decoded.height / 2, decoded.height - 1).forEach { oy ->
            val cy = ((oy + 0.5) * scaleY - 0.5).roundToInt()
            val start = (cy - kernelY / 2).coerceIn(0, plan.height - 1)
            val end = (cy + kernelY / 2).coerceIn(0, plan.height - 1)
            val expectedG = (plan.top + (start + end) / 2.0) / (height - 1) * 255
            assertWithin(
                5,
                "行 $oy",
                expectedG.toFloat(),
                Color.green(decoded.getPixel(decoded.width / 2, oy)).toFloat(),
                "G（窗口映射）",
            )
        }
        // 单调性：线性梯度的输出必须逐列/逐行不减（窗口滑动不会把顺序打乱）
        var lastR = -1
        for (ox in 0 until decoded.width) {
            val r = Color.red(decoded.getPixel(ox, 0))
            assertTrue("R 在列 $ox 处回退（$r < $lastR）", r >= lastR)
            lastR = r
        }
        var lastG = -1
        for (oy in 0 until decoded.height) {
            val g = Color.green(decoded.getPixel(0, oy))
            assertTrue("G 在行 $oy 处回退（$g < $lastG）", g >= lastG)
            lastG = g
        }
    }

    @Test
    fun `去网点计划与回退口径`() {
        // 1600×2400：去网点（解整源、带上有 σ）；4000×20000：源像素数超预算 → 回退（裁剪解码、无 σ）
        val descreened = CoverDecode.plan(1600, 2400, gridTarget, grid, crop)
        assertEquals(1, descreened.sampleSize)
        assertEquals("解的是整个源", 1600, descreened.decodedWidth)
        assertEquals(2400, descreened.decodedHeight)
        assertNull("去网点分支不带裁剪几何", descreened.cropToTarget)
        val fallback = CoverDecode.plan(4000, 20000, gridTarget, grid, crop)
        assertNull("超预算的超大源不去网点", fallback.descreen)
        assertNotNull("超大源照旧走裁剪解码", fallback.cropToTarget)
        // 去网点分支的端到端：解出的位图就是显示盒
        val decoded = decode("descreen-box", png(1600, 2400), gridTarget, grid)
        assertNotNull(decoded)
        assertEquals(gridTarget, decoded!!.width)
        assertEquals(683, decoded.height)
    }

    private fun assertWithin(tolerance: Int, label: String, expected: Float, actual: Float, what: String) {
        assertTrue(
            "$label $what：期望 ${expected.roundToInt()}（容差 $tolerance），实得 ${actual.roundToInt()}",
            kotlin.math.abs(expected - actual) <= tolerance,
        )
    }

    /**
     * 同一张位置编码图的无损 PNG（带 alpha 通道：ImageDecoder 给 ARGB_8888，解码器再转 565）。
     * 位置编码用例用无损 PNG 是因为 JPEG 的色度子采样会把**横向**编码（R）抹平（偏移可达 7/255），
     * 损失掉「取错带/取错位置」的判别力；[transparentLeftHalf] 那份给「带 alpha 的封面」用例。
     */
    private fun gradientPng(width: Int, height: Int, transparentLeftHalf: Boolean = false): ByteArray =
        ByteArrayOutputStream().use { out ->
            gradient(width, height, transparentLeftHalf = transparentLeftHalf)
                .compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    private fun gradient(width: Int, height: Int, transparentLeftHalf: Boolean): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 左半透明：逼 Skia 把图当成带 alpha 的（整张不透明时 ImageDecoder 会直接给 565）
                val alpha = if (transparentLeftHalf && x < width / 2) 0 else 255
                pixels[y * width + x] = Color.argb(alpha, x * 255 / (width - 1), y * 255 / (height - 1), 0)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** 全黑灰度 PNG，按行喂 Deflater：大尺寸（4000×20000 = 80MB 原始像素）不至于先建一张 80MB 的 raw 数组 */
    private fun streamPng(width: Int, height: Int): ByteArray {
        val ihdr = ByteArrayOutputStream().apply {
            writeInt(width)
            writeInt(height)
            write(8)   // 位深
            write(0)   // 颜色类型：灰度
            write(0)   // 压缩方法
            write(0)   // 过滤方法
            write(0)   // 隔行扫描
        }.toByteArray()
        val deflater = Deflater(1)
        val row = ByteArray(width + 1)  // 每行 1 字节过滤标记 + width 像素
        val buffer = ByteArray(1 shl 16)
        val idat = ByteArrayOutputStream()
        try {
            repeat(height) {
                deflater.setInput(row)
                while (!deflater.needsInput()) {
                    val n = deflater.deflate(buffer)
                    if (n > 0) idat.write(buffer, 0, n)
                }
            }
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n > 0) idat.write(buffer, 0, n)
            }
        } finally {
            // native 资源：无论成败都必须 end()（题面点名的缺口）
            deflater.end()
        }
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr))
            write(chunk("IDAT", idat.toByteArray()))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }

    // ---------- 测试用图（合成的极小 PNG/GIF，仓库不存二进制 fixture） ----------

    /** 单色 PNG（实现在 [SyntheticPng]：两处测试共用一份） */
    private fun png(width: Int, height: Int): ByteArray = SyntheticPng.of(width, height)

    private fun chunk(type: String, data: ByteArray): ByteArray = SyntheticPng.chunk(type, data)

    /** 4 色 GIF87a（首帧）：覆盖「区域解码未必支持的格式」这条退路 */
    private fun gif(width: Int, height: Int): ByteArray = ByteArrayOutputStream().apply {
        write("GIF87a".toByteArray())
        writeShort(width)
        writeShort(height)
        write(0xF0)  // 有全局色表 + 2 色
        write(0)
        write(0)
        write(byteArrayOf(0, 0, 0, 255.toByte(), 255.toByte(), 255.toByte()))
        write(0x2C)              // 图像描述符
        writeShort(0)            // 左
        writeShort(0)            // 上
        writeShort(width)        // 宽
        writeShort(height)       // 高
        write(0)                 // 无局部色表、非隔行
        write(2)                 // LZW 最小码长
        write(2)                 // 数据子块长度
        write(byteArrayOf(0x44, 0x06))  // 清空 + 两个像素 + 结束
        write(0)                 // 子块结束
        write(0x3B)              // 文件结束
    }.toByteArray()

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
