package com.cc3301.comicviewer.core.view

import kotlin.math.abs
import kotlin.math.roundToInt
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

    /** 印刷网点的 mimic：45° 取向、每 3px 一条亮线，振幅 60、灰底 100（真源实测周期 5px，见 `CoverDecodeBytesTest`） */
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
    fun `分离实现与测试侧独立复刻的盒模糊一致`() {
        // 生产是「逐行两次盒模糊 + 逐列两次盒模糊（滑窗、边缘复制）」；这里独立复刻同一定义并逐像素比
        val descreen = CoverDecode.Descreen(1.25f)
        val src = dottedLattice(64, 48)
        val separable = src.copyOf()
        descreen.blur(separable, 64, 48)
        val direct = boxBlurReference(src, 64, 48, descreen.boxWidth)
        for (i in src.indices) {
            val d = abs(((direct[i] ushr 16) and 0xFF) - ((separable[i] ushr 16) and 0xFF))
            // 生产用倒数乘法近似除、参照用四舍五入除：逐趟取整差最多 1 级，四趟累计不超 2
            assertTrue("分离与参照实现不得差过 2：$d", d <= 2)
        }
    }

    /** 独立参照实现：直接按定义做两趟盒模糊（先行后列、边缘复制、取整），不复用生产代码 */
    private fun boxBlurReference(src: IntArray, width: Int, height: Int, box: Int): IntArray {
        val half = box / 2
        var cur = src.copyOf()
        // 与生产同序：先两趟横向、再两趟纵向；每趟都先复制（避免原地读写相撞），逐趟四舍五入
        repeat(2) {
            val next = cur.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var r = 0; var g = 0; var b = 0
                    for (k in -half..half) {
                        val p = cur[y * width + (x + k).coerceIn(0, width - 1)]
                        r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF
                    }
                    next[y * width + x] = (0xFF shl 24) or ((r.toDouble() / box).roundToInt() shl 16) or
                        ((g.toDouble() / box).roundToInt() shl 8) or (b.toDouble() / box).roundToInt()
                }
            }
            cur = next
        }
        repeat(2) {
            val next = cur.copyOf()
            for (x in 0 until width) {
                for (y in 0 until height) {
                    var r = 0; var g = 0; var b = 0
                    for (k in -half..half) {
                        val p = cur[(y + k).coerceIn(0, height - 1) * width + x]
                        r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF
                    }
                    next[y * width + x] = (0xFF shl 24) or ((r.toDouble() / box).roundToInt() shl 16) or
                        ((g.toDouble() / box).roundToInt() shl 8) or (b.toDouble() / box).roundToInt()
                }
            }
            cur = next
        }
        return cur
    }

}
