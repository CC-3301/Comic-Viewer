package com.cc3301.comicviewer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动图标的几何契约（票 #43 AC 的可断言落点）：
 *
 * - 环外径 / 画布 = 48.8% ± 0.5%、描边 / 画布 = 5.0% ± 0.3%（对齐 Mihon 的比例）；
 * - 字标 ink 宽 / 画布 = 21.4% ± 0.5%，且「字标 ink 宽 / 环内径」= 55% ± 3%；
 * - 环落在自适应图标安全区内（外径 < 66/108），圆形与方形遮罩都不会切到；
 * - foreground 与 monochrome 是**同一条字形路径**，只差填充色；三色与 #37 一致。
 *
 * 直接读资源 XML（矢量几何就是待验收的资产本身），并用路径坐标的极值当 ink bbox：
 * 该字形路径的极值点就是字面墨迹边界（本次改动的缩放正是按 ink bbox 做的）。
 */
class LauncherIconTest {

    private val drawableDir = File("src/main/res/drawable")

    private fun xmlOf(name: String): String {
        val file = drawableDir.resolve(name)
        assertTrue("找不到图标资源：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun pathDataOf(xml: String): List<String> =
        Regex("android:pathData=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()

    /** XML 里的数字（坐标/半径/描边宽） */
    private fun numbersOf(text: String): List<Double> =
        Regex("-?\\d+(?:\\.\\d+)?").findAll(text).map { it.value.toDouble() }.toList()

    // ---------- 环 ----------

    @Test
    fun `环外径与描边比例对齐 Mihon`() {
        val background = xmlOf("ic_launcher_background.xml")
        val stroke = Regex("android:strokeWidth=\"([^\"]+)\"").find(background)!!.groupValues[1].toDouble()
        // 环路径写成描边圆：M54,<54-r> a<r>,<r> 0 1,0 0,<2r> …
        val ringPath = pathDataOf(background).first { it.contains("a") }
        val r = Regex("a(\\d+(?:\\.\\d+)?),(\\d+(?:\\.\\d+)?)").find(ringPath)!!.groupValues[1].toDouble()
        val outer = 2 * (r + stroke / 2)

        assertEquals("环外径 / 画布 = 48.8%", 48.8, outer / 108 * 100, 0.5)
        assertEquals("描边 / 画布 = 5.0%", 5.0, stroke / 108 * 100, 0.3)
        assertTrue("环必须是真圆（圆心线半径与描边都写进路径）", r > stroke)
    }

    @Test
    fun `环落在自适应图标安全区内`() {
        val background = xmlOf("ic_launcher_background.xml")
        val stroke = Regex("android:strokeWidth=\"([^\"]+)\"").find(background)!!.groupValues[1].toDouble()
        val ringPath = pathDataOf(background).first { it.contains("a") }
        val r = Regex("a(\\d+(?:\\.\\d+)?),").find(ringPath)!!.groupValues[1].toDouble()
        val outer = 2 * (r + stroke / 2)

        // 自适应图标可见区 = 中央 72/108，安全区约 66/108：外径都远小于两者，圆/方遮罩都不会切
        assertTrue("外径 $outer 必须小于安全区 66", outer < 66.0)
        assertTrue("外径 $outer 必须小于遮罩可见直径 72", outer < 72.0)
    }

    // ---------- 字标 ----------

    @Test
    fun `字标 ink 宽与居中达标 且相对环内径约五成半`() {
        val foreground = xmlOf("ic_launcher_foreground.xml")
        val glyph = pathDataOf(foreground).single()
        val xs = coordinates(glyph, axis = 0)
        val ys = coordinates(glyph, axis = 1)
        val inkWidth = xs.max() - xs.min()
        val inkHeight = ys.max() - ys.min()

        assertEquals("字标 ink 宽 / 画布 = 21.4%", 21.4, inkWidth / 108 * 100, 0.5)
        assertEquals("字标水平居中于圆心 54", 54.0, (xs.min() + xs.max()) / 2, 0.1)
        assertEquals("字标垂直居中于圆心 54", 54.0, (ys.min() + ys.max()) / 2, 0.1)
        assertEquals("字形高宽比保持 Roboto Bold 原样", 19.94 / 38.02, inkHeight / inkWidth, 0.01)

        // 字标 ink 宽 / 环内径
        val background = xmlOf("ic_launcher_background.xml")
        val stroke = Regex("android:strokeWidth=\"([^\"]+)\"").find(background)!!.groupValues[1].toDouble()
        val ringPath = pathDataOf(background).first { it.contains("a") }
        val r = Regex("a(\\d+(?:\\.\\d+)?),").find(ringPath)!!.groupValues[1].toDouble()
        val inner = 2 * (r - stroke / 2)
        assertEquals("字标 ink 宽 / 环内径 = 55%", 55.0, inkWidth / inner * 100, 3.0)
    }

    @Test
    fun `foreground 与 monochrome 是同一条字形路径 只差填充色`() {
        val foreground = xmlOf("ic_launcher_foreground.xml")
        val monochrome = xmlOf("ic_launcher_monochrome.xml")

        assertEquals(
            "两层必须是同一条路径",
            pathDataOf(foreground),
            pathDataOf(monochrome),
        )
        assertTrue("foreground 字标是 #111111", foreground.contains("#111111"))
        assertTrue("monochrome 只取 alpha（纯白）", monochrome.contains("#FFFFFF"))
    }

    @Test
    fun `三色与层次不变`() {
        val background = xmlOf("ic_launcher_background.xml")
        assertTrue("白底 #FFFFFF", background.contains("#FFFFFF"))
        assertTrue("深蓝环 #1B3A6B", background.contains("#1B3A6B"))
        assertTrue("背景层只有白底与环两条路径", pathDataOf(background).size == 2)
    }

    @Test
    fun `图标与圆形图标仍指向同一个自适应图标`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher\""))
        val adaptive = File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        listOf("background", "foreground", "monochrome").forEach { layer ->
            assertTrue("$layer 层必须在", adaptive.contains("<$layer "))
        }
    }

    /**
     * 取路径里某一轴的坐标值：只含绝对命令 M/H/V/L/Q/Z，且
     * M/L/Q 的参数是成对的 x y、H 只有 x、V 只有 y —— 按命令逐段解析，避免把命令字母当坐标。
     */
    private fun coordinates(path: String, axis: Int): List<Double> {
        val tokens = Regex("[MHVLQZ]|-?\\d+(?:\\.\\d+)?").findAll(path).map { it.value }.toList()
        val values = mutableListOf<Double>()
        var i = 0
        var current = 'M'
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.length == 1 && token[0].isLetter()) {
                current = token[0]
                i++
                continue
            }
            when (current) {
                'M', 'L' -> {
                    if (axis == 0) values += tokens[i].toDouble() else values += tokens[i + 1].toDouble()
                    i += 2
                }
                'Q' -> {
                    val xs = listOf(tokens[i].toDouble(), tokens[i + 2].toDouble())
                    val ys = listOf(tokens[i + 1].toDouble(), tokens[i + 3].toDouble())
                    values += if (axis == 0) xs else ys
                    i += 4
                }
                'H' -> {
                    if (axis == 0) values += tokens[i].toDouble()
                    i += 1
                }
                'V' -> {
                    if (axis == 1) values += tokens[i].toDouble()
                    i += 1
                }
                else -> i += 1
            }
        }
        return values
    }
}
