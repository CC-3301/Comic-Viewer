package com.cc3301.comicviewer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动图标的几何契约（票 #55 AC 的可断言落点；#43 定环比例、#55 字标改「CV」并放大）：
 *
 * - 环外径 / 画布 = 48.8% ± 0.5%、描边 / 画布 = 5.0% ± 0.3%（#43 比例，本票未动）；
 * - 字标 ink 宽 / 画布 = 25.9%（= 28/108，满足 AC 的 ≥ 26/108，且明显大于 #43 的 23/108）；
 *   工单「环内径的 60%~65%」与「大约 28–30/108」两句换算口径不同（前者按描边内缘 41.9，
 *   后者按环圆心线直径 47.3），因此测试锁死 AC 的 26–30/108 区间而非某个百分比；
 * - 字标居中于圆心、不与环相切；高宽比是 Roboto Bold「CV」的比例（区别于旧「CM」）；
 * - 环色比 #1B3A6B 更蓝（饱和度更高、色相仍在蓝色带）；
 * - 环落在自适应图标安全区内（外径 < 66/108），圆形与方形遮罩都不会切到；
 * - foreground 与 monochrome 是**同一条字形路径**，只差填充色。
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

    private fun strokeWidthOf(background: String): Double =
        Regex("android:strokeWidth=\"([^\"]+)\"").find(background)!!.groupValues[1].toDouble()

    private fun ringCenterRadiusOf(background: String): Double {
        val ringPath = pathDataOf(background).first { it.contains("a") }
        return Regex("a(\\d+(?:\\.\\d+)?),").find(ringPath)!!.groupValues[1].toDouble()
    }

    // ---------- 环 ----------

    @Test
    fun `环外径与描边比例对齐 Mihon`() {
        val background = xmlOf("ic_launcher_background.xml")
        val stroke = strokeWidthOf(background)
        // 环路径写成描边圆：M54,<54-r> a<r>,<r> 0 1,0 0,<2r> …
        val r = ringCenterRadiusOf(background)
        val outer = 2 * (r + stroke / 2)

        assertEquals("环外径 / 画布 = 48.8%", 48.8, outer / 108 * 100, 0.5)
        assertEquals("描边 / 画布 = 5.0%", 5.0, stroke / 108 * 100, 0.3)
        assertTrue("环必须是真圆（圆心线半径与描边都写进路径）", r > stroke)
    }

    @Test
    fun `环落在自适应图标安全区内`() {
        val background = xmlOf("ic_launcher_background.xml")
        val stroke = strokeWidthOf(background)
        val r = ringCenterRadiusOf(background)
        val outer = 2 * (r + stroke / 2)

        // 自适应图标可见区 = 中央 72/108，安全区约 66/108：外径都远小于两者，圆/方遮罩都不会切
        assertTrue("外径 $outer 必须小于安全区 66", outer < 66.0)
        assertTrue("外径 $outer 必须小于遮罩可见直径 72", outer < 72.0)
    }

    @Test
    fun `环色比 1B3A6B 更蓝`() {
        val background = xmlOf("ic_launcher_background.xml")
        val ringHex = Regex("android:strokeColor=\"#([0-9A-Fa-f]{6})\"")
            .find(background)!!.groupValues[1]
        val ring = ringHex.toInt(16)
        val before = 0x1B3A6B

        assertTrue(
            "新环色 #$ringHex 的饱和度 ${saturation(ring)} 必须明显高于 #1B3A6B 的 ${saturation(before)}",
            saturation(ring) > saturation(before) + 0.1,
        )
        assertEquals("新环色仍是蓝色（色相 200–240°）", 220.0, hue(ring), 25.0)
        assertTrue("环色必须已经换掉 #1B3A6B", !ringHex.equals("1B3A6B", ignoreCase = true))
    }

    // ---------- 字标 ----------

    @Test
    fun `字标 ink 宽放大到 28 且居中不碰环`() {
        val foreground = xmlOf("ic_launcher_foreground.xml")
        val glyph = pathDataOf(foreground).single()
        val xs = coordinates(glyph, axis = 0)
        val ys = coordinates(glyph, axis = 1)
        val inkWidth = xs.max() - xs.min()
        val inkHeight = ys.max() - ys.min()

        assertTrue("字标 ink 宽 $inkWidth 必须 ≥ 26/108（#43 现值 23/108）", inkWidth >= 26.0)
        assertEquals("字标 ink 宽 / 画布 = 25.9%", 25.9, inkWidth / 108 * 100, 0.5)
        assertEquals("字标水平居中于圆心 54", 54.0, (xs.min() + xs.max()) / 2, 0.1)
        assertEquals("字标垂直居中于圆心 54", 54.0, (ys.min() + ys.max()) / 2, 0.1)
        // Roboto Bold「CV」的高宽比（C 是圆头略高于 cap、V 无降部）——与旧「CM」的 0.5245 不同
        assertEquals("字形高宽比保持 Roboto Bold「CV」原样", 0.5929, inkHeight / inkWidth, 0.01)

        // 字标不与环相切：ink 半宽/半高都小于环内半径
        val background = xmlOf("ic_launcher_background.xml")
        val innerRadius = ringCenterRadiusOf(background) - strokeWidthOf(background) / 2
        assertTrue(
            "字标横向不碰环（半宽 ${inkWidth / 2} < 环内半径 $innerRadius）",
            inkWidth / 2 < innerRadius,
        )
        assertTrue(
            "字标纵向不碰环（半高 ${inkHeight / 2} < 环内半径 $innerRadius）",
            inkHeight / 2 < innerRadius,
        )
        // 口径说明：28/108 相对「环内径」（描边内缘直径 41.9）为 66.8%，
        // 相对「环圆心线直径」（47.3）为 59.2% —— 工单的「60%~65% 量级」及其
        // 「大约 28–30/108」换算用的是后者；此处只锁死「≥ 26/108 且 ≤ 30/108」这一档量级。
        assertTrue("字标 ink 宽 $inkWidth 不得超过 30/108（仍要与环留白）", inkWidth <= 30.0)
    }

    @Test
    fun `字形路径的命令与参数个数合法`() {
        // pathData 写错（漏坐标、命令拼错）会让图标静默渲染成空白，而极值断言不一定发现，
        // 所以按命令逐段核对参数个数：M/L 两个、H/V 一个、Q 四个、Z 零个。
        val expectedArity = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'Q' to 4, 'Z' to 0)
        listOf("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml").forEach { name ->
            val tokens = Regex("[MHVLQZ]|-?\\d+(?:\\.\\d+)?")
                .findAll(pathDataOf(xmlOf(name)).single()).map { it.value }.toList()
            var i = 0
            while (i < tokens.size) {
                val command = tokens[i]
                assertEquals("$name：${i}th token 必须是命令字母", 1, command.length)
                val arity = expectedArity[command[0]]
                    ?: throw AssertionError("$name：不支持的路径命令 $command")
                i++
                repeat(arity) {
                    assertTrue(
                        "$name：命令 $command 缺少参数（位置 $i）",
                        i < tokens.size && tokens[i].length > 1,
                    )
                    i++
                }
            }
        }
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

    // ---------- 颜色工具 ----------

    private fun channels(hex: Int): Triple<Int, Int, Int> =
        Triple(hex shr 16 and 0xFF, hex shr 8 and 0xFF, hex and 0xFF)

    /** HSL 饱和度（0–1），越大越「艳」 */
    private fun saturation(hex: Int): Double {
        val (r, g, b) = channels(hex)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return if (max + min == 0) 0.0 else (max - min).toDouble() / (max + min)
    }

    /** HSL 色相（0–360°），蓝色带约 200–240° */
    private fun hue(hex: Int): Double {
        val (r, g, b) = channels(hex)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == min) return 0.0
        val span = (max - min).toDouble()
        val h = when (max) {
            r -> 60.0 * (((g - b) / span) % 6)
            g -> 60.0 * ((b - r) / span + 2)
            else -> 60.0 * ((r - g) / span + 4)
        }
        return (h + 360) % 360
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
