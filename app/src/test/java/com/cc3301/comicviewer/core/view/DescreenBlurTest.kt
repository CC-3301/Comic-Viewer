package com.cc3301.comicviewer.core.view

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 去网点高斯模糊的像素数学（纯 JVM，不经 android 位图）。
 *
 * 用例的判别对象是**印刷网点**：实体书扫描成数字版时带进的高频点阵（细间距、45° 取向）。
 * 模糊的正确性分三面钉住：
 * - 网点频段的能量在模糊后**大幅下降**（σ=1.25 量级对 2~3px 间距点阵的抑制）；
 * - 直流分量（画面平均亮度）几乎不动（轻度模糊不是调色）；
 * - 分离实现（先逐行后逐列）与直接二维卷积在数值上一致（实现结构不改变结果）。
 */
class DescreenBlurTest {

    /** 45° 取向的印刷网点：沿 x+y 方向每 3px 一条亮线（垂直向间距 ≈2.1px），振幅 60、灰底 100 */
    private fun dottedLattice(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if ((x + y) % 3 == 0) 160 else 100
                pixels[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return pixels
    }

    /** 平滑渐变（无网点）：横向 0..255 线性 */
    private fun smoothGradient(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = x * 255 / (width - 1)
                pixels[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return pixels
    }

    private fun meanGray(pixels: IntArray): Double =
        pixels.map { ((it ushr 16) and 0xFF) }.average()

    @Test
    fun `网点频段能量在模糊后大幅下降`() {
        val sigma = CoverDecode.descreenFor(3.125f)!!.sigma  // 1.25：典型书柜格宽的缩小比
        val before = dottedLattice(160, 120)
        val after = before.copyOf()
        CoverDecode.Descreen(sigma).blur(after, 160, 120)
        val rmsBefore = highFrequencyRms(before, 160, 120)
        val rmsAfter = highFrequencyRms(after, 160, 120)
        assertTrue("模糊前网点残差必须显著（判别力前提）：$rmsBefore", rmsBefore > 20.0)
        assertTrue(
            "模糊后网点残差 $rmsAfter 必须降到模糊前（$rmsBefore）的两成以下",
            rmsAfter < rmsBefore * 0.2,
        )
    }

    @Test
    fun `模糊几乎不动直流分量`() {
        val sigma = CoverDecode.descreenFor(3.125f)!!.sigma
        val before = dottedLattice(160, 120)
        val after = before.copyOf()
        CoverDecode.Descreen(sigma).blur(after, 160, 120)
        assertEquals(
            "网点被抹成的是均匀灰，不是调亮/调暗",
            meanGray(before),
            meanGray(after),
            2.0,
        )
    }

    @Test
    fun `平滑渐变经模糊后高频残差同样降低且幅度有限`() {
        val sigma = CoverDecode.descreenFor(3.125f)!!.sigma
        val before = smoothGradient(160, 120)
        val after = before.copyOf()
        CoverDecode.Descreen(sigma).blur(after, 160, 120)
        val rmsBefore = highFrequencyRms(before, 160, 120)
        val rmsAfter = highFrequencyRms(after, 160, 120)
        // 渐变的邻差恒定（≈1.6 级），模糊把它摊得更平；亮度台阶不得超过 2 级
        assertTrue("渐变的高频残差只降不升：$rmsAfter ≤ $rmsBefore", rmsAfter <= rmsBefore)
        for (i in before.indices) {
            assertTrue(
                "渐变整体形态不得被移动：像素 $i 亮度变化超限",
                abs(((before[i] ushr 16) and 0xFF) - ((after[i] ushr 16) and 0xFF)) <= 2,
            )
        }
    }

    @Test
    fun `分离实现与二维卷积一致`() {
        val sigma = CoverDecode.descreenFor(2.5f)!!.sigma  // 1.0：半径 3 的小核
        val src = dottedLattice(64, 48)
        val separable = src.copyOf()
        CoverDecode.Descreen(sigma).blur(separable, 64, 48)
        // 非分离参照：每个像素直接与半径内全部邻点做二维高斯卷积（边缘复制），独立实现
        val direct = direct2dConvolution(src, 64, 48, sigma)
        for (i in src.indices) {
            val d = abs(((direct[i] ushr 16) and 0xFF) - ((separable[i] ushr 16) and 0xFF))
            assertTrue(
                "分离与二维卷积在像素 $i 不得差过舍入余量：$d > 1",
                d <= 1,
            )
        }
    }

    /** 独立参照实现：二维高斯卷积（边缘复制），只算灰度（红通道）后组回灰度像素 */
    private fun direct2dConvolution(src: IntArray, width: Int, height: Int, sigma: Float): IntArray {
        val radius = CoverDecode.Descreen(sigma).radius
        val out = IntArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0
                var wsum = 0.0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val yy = (y + dy).coerceIn(0, height - 1)
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val w = kotlin.math.exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma))
                        sum += w * ((src[yy * width + xx] ushr 16) and 0xFF)
                        wsum += w
                    }
                }
                val v = kotlin.math.round(sum / wsum).toInt()
                out[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return out
    }
}
