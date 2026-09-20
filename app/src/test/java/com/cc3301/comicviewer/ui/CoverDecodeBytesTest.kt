package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.graphics.Color
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.gridBoxByteCount
import com.cc3301.comicviewer.core.view.gridCellWidth
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
 * 封面解码的真实位图字节数（票 #81 + 票 #85）。
 *
 * [CoverDecodeTest] 锁的是纯函数口径（计划里的保留字节数），这里补上端到端的一环：真的过
 * [PageDecoder.decodeCoverBytes] 解出位图，再数 `allocationByteCount`——验的是「裁剪解码这条路
 * 真的按显示盒留内存」，而不是只验算数。
 *
 * `GraphicsMode.NATIVE` 走的是 AOSP 原生的 `BitmapFactory`/`BitmapRegionDecoder`/`ImageDecoder`（不是
 * Robolectric 的影子实现：影子会忽略区域解码的 `inSampleSize` 之类的语义），因此这里量到的字节数与真机同量级，
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

    /** 网格 2 列在 3.0 密度下的桶（= 票面验收里的 512px）：格宽走仓库唯一来源 */
    private val gridTarget = CoverDecode.targetWidthPx(gridCellWidth(360f, 2, 12f, 6f) * 3f)

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
        // 1080 宽的条漫首页：区域解码按源宽解出的带必须缩到显示盒（票 #81 r1 评审 P2-2）
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
        // 退路 = 整图子采样（800 宽源在 512 桶下 sample=1），即放弃本票的收益；这里钉住它确实发生了
        assertEquals("退路解出整图宽", 800, full!!.width)
        assertEquals("退路解出整图高（长条漫封面照旧整张解出）", 8000, full.height)
        assertTrue(
            "退路字节数 ${bytesOf(full)} 必须大于可见带方案的 " +
                "${CoverDecode.plan(800, 8000, gridTarget, grid, crop).retainedByteCount}",
            bytesOf(full) > CoverDecode.plan(800, 8000, gridTarget, grid, crop).retainedByteCount,
        )
        assertSame("退路结果同样入缓存", full, PageDecoder.cached(key("fallback", grid)))
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
    fun `列表档的普通封面仍按宽度子采样 不因裁剪多解`() {
        // 列表档行内封面列宽 56dp（BrowserScreen.LIST_COVER_WIDTH，私有常量，同 CoverDecodeTest 用字面量代）
        val listTarget = CoverDecode.targetWidthPx(56f * 3f)  // 192px 桶
        val cover = decode("list-normal", png(800, 1067), listTarget, list)
        assertNotNull(cover)
        // 计划是整图子采样：800/4 = 200px 宽（与票 #56 的现状一致，没为“裁剪”多解）
        assertEquals("列表档普通封面应解出 200px 宽（800 宽源 × 192px 桶的子采样档）", 200, cover!!.width)
    }

    @Test
    fun `同一解码键命中内存缓存 不同裁剪目标不共用缓存`() {
        val bytes = png(800, 1067)
        val first = decode("cached", bytes, gridTarget, grid)
        assertNotNull(first)
        assertSame("同键必须命中同一张位图（不得重解）", first, PageDecoder.cached(key("cached", grid)))
        assertNull("另一档的键不得命中这一档的位图（不串图）", PageDecoder.cached(key("cached", list)))
    }

    @Test
    fun `GIF 封面仍解得出静态首帧`() {
        // 格式与裁剪路线的兼容性：解不出时解码器会退回整图子采样，结果必须照常可用
        val cover = decode("gif", gif(4, 40), 32, grid)
        assertNotNull(cover)
        assertTrue("GIF 首帧必须解出像素", cover!!.width > 0 && cover.height > 0)
    }

    // ---------- 裁剪 + 缩放一步解出显示盒（票 #85） ----------

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
        // 期望值是**由源尺寸 + 盒比例（4:3）独立算出来的常量**（不从 plan 取）：400×2000 的源高宽比 5 > 4/3
        // ⇒ 裁高不裁宽 ⇒ 带 400×533、带左上 (0,733)；目标 64 桶 ⇒ 盒 64×85。
        val width = 400
        val height = 2000
        val target = 64
        val bandLeft = 0
        val bandTop = 733          // (2000 - 533) / 2
        val bandWidth = 400
        val bandHeight = 533       // round(400 × 4/3)
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
                // R 只有 5 位（步长 255/31 ≈ 8.2）→ 7；G 是 6 位（步长 255/63 ≈ 4.05）→ 4
                // （实测本图最大偏离：R 7、G 3.3；容差不可能收到 3——量化步长本身就大于 3）
                assertWithin(7, "($col,$row)", expectedR, Color.red(pixel).toFloat(), "R（横向位置）")
                assertWithin(4, "($col,$row)", expectedG, Color.green(pixel).toFloat(), "G（纵向位置）")
            }
        }
        // 这条带的纵向跨度（不能是整张源、也不能是别的带）：带高/源高 × 255 = 68（容差同 G：两个量化值相减）
        val topRow = Color.green(bitmap.getPixel(0, 0)).toFloat()
        val bottomRow = Color.green(bitmap.getPixel(0, boxHeight - 1)).toFloat()
        assertWithin(4, "纵向跨度", bandHeight.toFloat() / (height - 1) * 255, bottomRow - topRow, "带高")
        assertTrue("横向必须满宽（带宽 = 源宽）", Color.red(bitmap.getPixel(boxWidth - 1, 0)) >= 240)
        // 判别力（写明而不是假高）：G 的 4/255 ≈ 1/4 个 565 绿色级 ≈ 32 源行 ≈ 5 输出行（帯高 85 行）；
        // 因此这条用例能抓住「取成整张源」「取成别的带」「取上下颠倒」这一类，但不宣称亚像素居中。
    }

    @Test
    fun `带 alpha 的封面也解成显示盒尺寸的 RGB-565`() {
        // ImageDecoder 的 LOW_RAM 只对不透明源给 RGB_565（实测 PNG 带 alpha 时会给 ARGB_8888），
        // 解码器因此要转一次 565，否则保留位图翻倍、与 [CoverDecode.BITMAP_BYTES_PER_PIXEL] 的口径不符
        val decoded = decode("alpha", gradientPng(400, 2000, transparentLeftHalf = true), 64, grid)
        assertNotNull(decoded)
        assertEquals(Bitmap.Config.RGB_565, decoded!!.asAndroidBitmap().config)
        assertEquals(64, decoded.width)
        assertEquals(
            "alpha 源也要按显示盒尺寸（1 像素 2 字节）留内存",
            CoverDecode.plan(400, 2000, 64, grid, crop).retainedByteCount,
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

    private fun assertWithin(tolerance: Int, label: String, expected: Float, actual: Float, what: String) {
        assertTrue(
            "$label $what：期望 ${expected.roundToInt()}（容差 $tolerance），实得 ${actual.roundToInt()}",
            kotlin.math.abs(expected - actual) <= tolerance,
        )
    }

    /**
     * 同一张位置编码图的无损 PNG（带 alpha 通道：ImageDecoder 给 ARGB_8888，解码器再转 565）。
     * 位置编码用例用无损 PNG 是因为 JPEG 的色度子采样会把**横向**编码（R）抹平（实测偏移可达 7/255），
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
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr))
            write(chunk("IDAT", idat.toByteArray()))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }

    // ---------- 测试用图（合成的极小 PNG/GIF，仓库不存二进制 fixture） ----------

    /** 单色 PNG（无过滤 + Deflater）：只为给解码器一份真实尺寸的图 */
    private fun png(width: Int, height: Int): ByteArray {
        val ihdr = ByteArrayOutputStream().apply {
            writeInt(width)
            writeInt(height)
            write(8)   // 位深
            write(0)   // 颜色类型：灰度
            write(0)   // 压缩方法
            write(0)   // 过滤方法
            write(0)   // 隔行扫描
        }.toByteArray()
        val raw = ByteArray(height * (width + 1))  // 每行 1 字节过滤标记 + width 像素
        val deflater = Deflater(9)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArray(raw.size + 1024)
        val compressedSize = deflater.deflate(compressed)
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr))
            write(chunk("IDAT", compressed.copyOf(compressedSize)))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray() + data
        val crc = CRC32().apply { update(body) }
        return ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(body)
            writeInt(crc.value.toInt())
        }.toByteArray()
    }

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

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
