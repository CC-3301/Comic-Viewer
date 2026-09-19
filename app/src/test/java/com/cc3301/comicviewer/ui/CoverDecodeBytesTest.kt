package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.gridCellWidth
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
 * 封面解码的真实位图字节数（票 #81）。
 *
 * [CoverDecodeTest] 锁的是纯函数口径（计划里的保留字节数），这里补上端到端的一环：真的过
 * [PageDecoder.decodeCoverBytes] 解出位图，再数 `allocationByteCount`——验的是「区域解码这条路
 * 真的按显示盒留内存」，而不是只验算数。
 *
 * `GraphicsMode.NATIVE` 走的是 AOSP 原生的 `BitmapFactory`/`BitmapRegionDecoder`（不是 Robolectric 的
 * 影子实现：影子会忽略区域解码的 `inSampleSize` 之类的语义），因此这里量到的字节数与真机同量级。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverDecodeBytesTest {

    private val grid = CoverDecode.CropTarget.GridCell
    private val list = CoverDecode.CropTarget.OwnAspect

    /** 网格 2 列在 3.0 密度下的桶（= 票面验收里的 512px）：格宽走仓库唯一来源 */
    private val gridTarget = CoverDecode.targetWidthPx(gridCellWidth(360f, 2, 12f, 6f) * 3f)

    private fun key(name: String, cropTarget: CoverDecode.CropTarget) =
        CoverDecode.key(name, 0, gridTarget, cropTarget)

    private fun decode(name: String, bytes: ByteArray, targetWidthPx: Int, cropTarget: CoverDecode.CropTarget) =
        PageDecoder.decodeCoverBytes(key(name, cropTarget), bytes, targetWidthPx, cropTarget)

    private fun bytesOf(bitmap: androidx.compose.ui.graphics.ImageBitmap) =
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
        val boxBytes =
            gridTarget * (gridTarget * CoverLayout.GRID_CELL_ASPECT).toInt() * CoverDecode.BITMAP_BYTES_PER_PIXEL
        assertTrue("解出 $longBytes 字节，不得超过显示盒 $boxBytes 字节的 4 倍", longBytes <= boxBytes * 4)
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
        // 计划的字节口径（`BITMAP_BYTES_PER_PIXEL`）必须与真解出的位图一致，否则选分支的算数是错的
        val long = decode("plan-long", png(800, 8000), gridTarget, grid)
        val normal = decode("plan-normal", png(800, 1067), gridTarget, grid)
        assertNotNull(long)
        assertNotNull(normal)
        assertEquals(
            "可见带分支：计划保留字节数必须等于真解出的字节数",
            CoverDecode.plan(800, 8000, gridTarget, grid).retainedByteCount,
            bytesOf(long!!),
        )
        assertEquals(
            "整图分支：计划保留字节数必须等于真解出的字节数",
            CoverDecode.plan(800, 1067, gridTarget, grid).retainedByteCount,
            bytesOf(normal!!),
        )
    }

    @Test
    fun `区域解码返回 null 时退回整图子采样且仍能出图`() {
        val bytes = png(800, 8000)
        val full = PageDecoder.decodeCoverBytes(
            key("fallback", grid),
            bytes,
            gridTarget,
            grid,
            regionDecoder = { _, _ -> null },
        )
        assertNotNull("退路必须仍出图（不崩、不空白）", full)
        // 退路 = 整图子采样（800 宽源在 512 桶下 sample=1），即放弃本票的收益；这里钉住它确实发生了
        assertEquals("退路解出整图宽", 800, full!!.width)
        assertEquals("退路解出整图高（长条漫封面照旧整张解出）", 8000, full.height)
        assertTrue(
            "退路字节数 ${bytesOf(full)} 必须大于可见带方案的 ${CoverDecode.plan(800, 8000, gridTarget, grid).retainedByteCount}",
            bytesOf(full) > CoverDecode.plan(800, 8000, gridTarget, grid).retainedByteCount,
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
