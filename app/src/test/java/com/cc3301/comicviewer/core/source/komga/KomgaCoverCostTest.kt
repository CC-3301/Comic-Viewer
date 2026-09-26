package com.cc3301.comicviewer.core.source.komga

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import com.cc3301.comicviewer.core.source.InMemoryProgressStore
import com.cc3301.comicviewer.core.view.CoverDecode
import com.cc3301.comicviewer.core.view.CoverDiagnostics
import com.cc3301.comicviewer.core.view.gridBoxByteCount
import com.cc3301.comicviewer.ui.PageDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * 票 #140 A 案的**实测成本**（字节数 + 解码耗时）——本票验收里「代价已知并接受」的那两条要数字，不要估计值。
 *
 * 量的是「书封面 = 第 1 页原图」这条新通路真实交付的字节：用一张 1272×1800 的 JPEG 页面
 * （维护者真机上那批 Komga 页面的尺寸：服务端缩略图 212×300 就是它按高 300 缩出来的）
 * 走 [KomgaSource.coverBytes]，再交给真解码器（Robolectric 的 `GraphicsMode.NATIVE` = AOSP 原生
 * `BitmapFactory`/`ImageDecoder`，与 `CoverDecodeBytesTest` 同一口径），量解码耗时与保留位图字节数。
 *
 * 数字与内容强相关（漫画页压得比这更好或更差都正常），所以这里只断言**判据**（字节原样交付、`upscale=false`、
 * 保留位图 = 显示盒），具体数值打进测试输出供证据引用。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KomgaCoverCostTest {

    private val config = KomgaConnectionConfig(baseUrl = "http://komga:25600", apiKey = "k")
    private val prefix = KomgaIds.prefix(config.baseUrl)

    /** 网格 2 列的解码宽度 = **576px**（权威坐标：`docs/spec/browsing.md` 第 205 行 / `CONTEXT.md` 第 101 行；本票「不再糊」的判据线） */
    private val gridTarget = CoverDecode.targetWidthPx(576f)

    @Test
    fun `第 1 页原图的字节数与解码耗时`() = runBlocking<Unit> {
        val page = syntheticPageJpeg(width = 1272, height = 1800)
        val bookId = KomgaIds.bookId(prefix, "s1", "b1")
        val fake = FakeKomgaApi(
            series = listOf(KomgaSeries(id = "s1", title = "Series A", booksCount = 1)),
            books = mapOf(
                "s1" to listOf(KomgaBook("b1", "s1", "Vol 1", "", 1, "2020-01-01")),
            ),
            pages = mapOf("b1" to listOf(KomgaPage(1, "image/jpeg"))),
            firstPageBytes = mapOf("b1" to page),
        )
        val src = KomgaSource(api = fake, config = config, progressStore = InMemoryProgressStore())

        val cover = src.coverBytes(bookId)!!

        assertEquals("封面交给解码器的就是第 1 页的原始字节（不重编、不裁剪）", page.size, cover.size)
        assertEquals("只问第 1 页", listOf("page1/b1"), fake.coverRequests)

        val boxBytes = gridBoxByteCount(gridTarget)
        // 解两次（不同缓存键）：第一次含 JVM/解码器的冷启动，第二次更接近稳态——两个数都给出来，不取最好看的那个
        val decodeMs = (1..2).map { round ->
            val key = CoverDecode.key(bookId + "#" + round, 0, gridTarget, CoverDecode.CropTarget.GridCell)
            val started = System.nanoTime()
            val decoded = PageDecoder.decodeCoverBytes(key, cover, gridTarget, CoverDecode.CropTarget.GridCell)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertNotNull("第 1 页原图必须解得出封面位图", decoded)
            val retained = decoded!!.asAndroidBitmap().allocationByteCount
            val line = CoverDiagnostics.coverSourceLine(
                key,
                srcWidth = 1272,
                srcHeight = 1800,
                targetWidthPx = gridTarget,
                cropTarget = CoverDecode.CropTarget.GridCell,
                plan = CoverDecode.plan(1272, 1800, gridTarget, CoverDecode.CropTarget.GridCell, CoverDecode.BandDecoder.CropToTarget),
            )
            println("票 #140 证据：" + cover.size + " 字节的 1272x1800 JPEG → 第 " + round + " 次解码 " + ms + "ms，保留位图 " + retained + " 字节")
            if (round == 1) println("票 #140 证据（真机日志同一行）：" + line)
            assertTrue("源宽 1272 ≥ 目标 $gridTarget ⇒ 不再被放大（真机日志应为 upscale=false）：$line", line.contains("upscale=false"))
            assertTrue("保留位图 $retained 字节不得超过显示盒 $boxBytes 字节", retained <= boxBytes)
            ms
        }
        println("票 #140 证据：两次解码耗时（冷/暖）= " + decodeMs + " ms")
    }

    @Test
    fun `长条漫首页的保留位图仍只等于显示盒 不吃封面缓存分区`() {
        // 票面验收第 5 条：封面换成第 1 页原图后，长条漫（源高 ≫ 宽）首页必须仍走裁剪解码，
        // 保留位图只等于显示盒——否则一张 1080×15000 的首页会按源分辨率占掉整个封面缓存分区。
        val plan = CoverDecode.plan(1080, 15000, gridTarget, CoverDecode.CropTarget.GridCell, CoverDecode.BandDecoder.CropToTarget)
        val boxBytes = gridBoxByteCount(gridTarget)

        assertTrue("长条漫首页必须走裁剪解码（不是整图子采样）", plan.region)
        assertTrue(
            "保留位图 " + plan.retainedByteCount + " 字节不得超过显示盒 " + boxBytes + " 字节",
            plan.retainedByteCount <= boxBytes,
        )
    }

    /** 合成一张「像漫画页」的 JPEG（渐变 + 分块纹理 + 轻噪声）：不透明、有细节，压缩后是几百 KB 量级 */
    private fun syntheticPageJpeg(width: Int, height: Int): ByteArray {
        val pixels = IntArray(width * height)
        var seed = 20260927L
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                // 分块（16px 格）+ 渐变 + 轻噪声：既不是纯色（那样压缩后只有几 KB，量不出真实量级）
                // 也不是纯噪声（那样比真实漫画页大得多）
                val block = if (((x / 16) + (y / 16)) % 2 == 0) 40 else 0
                val noise = ((seed ushr 33).toInt() and 0x1F) - 16
                val r = (x * 255 / width + block + noise).coerceIn(0, 255)
                val g = (y * 255 / height + block + noise).coerceIn(0, 255)
                val b = (128 + block + noise).coerceIn(0, 255)
                pixels[y * width + x] = Color.argb(255, r, g, b)
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
