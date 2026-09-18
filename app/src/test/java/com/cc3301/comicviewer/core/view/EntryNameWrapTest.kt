package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目名称断行（票 #47 AC 的纯函数落点）：
 * - references/7.jpg 的三个名称必须得到「尾巴里也有断点」的结果（改动前 `訳]-1600x` 是整段不可断单元）；
 * - 可见内容不变（零宽空格宽度为 0）、幂等、不撑破两行封顶的既有口径。
 *
 * 像素级验收（第一行右端空余 ≤ 一个字宽）要在维护者设备上看截图；这里用**等宽估算的贪心断行模拟**
 * 把「填充率明显变好」钉成回归守护：改动前两条只填到 ~77%，处理后 ≥ 90%。
 */
class EntryNameWrapTest {

    private val SB = EntryNameWrap.SOFT_BREAK

    private val magicGirl = "[RAITA] 魔法少女17 [中国翻訳]-1600x"
    private val muddyCat = "[RAITA] 泥棒猫の横恋慕 [中国翻訳]-1600x"
    private val android = "[RAITA] セックスアンドロイド [中国翻訳] [DL版]-1600x"

    // ---------- 可见内容与幂等 ----------

    @Test
    fun `补断点不改变可见内容`() {
        listOf(magicGirl, muddyCat, android, "纯中文名称", "无空格长串ABCDEFG", "").forEach { name ->
            assertEquals(name, EntryNameWrap.withoutSoftBreaks(EntryNameWrap.withSoftBreaks(name)))
        }
    }

    @Test
    fun `幂等 重复处理不会重复插入`() {
        val once = EntryNameWrap.withSoftBreaks(magicGirl)
        assertEquals(once, EntryNameWrap.withSoftBreaks(once))
    }

    // ---------- 核心：原本不可断的尾巴现在有断点 ----------

    @Test
    fun `三个 reference 名称的尾巴里都有了断点`() {
        // 尾巴 `訳]-1600x`：改动前右括号前、连字符前、纯数字内部、数字与字母之间都不可断
        val processed = EntryNameWrap.withSoftBreaks(magicGirl)
        assertTrue("右括号之后要有断点", processed.contains("]" + EntryNameWrap.SOFT_BREAK))
        assertTrue("连字符之后要有断点", processed.contains("-" + EntryNameWrap.SOFT_BREAK))
        assertTrue("数字与字母交界要有断点", processed.contains("0" + EntryNameWrap.SOFT_BREAK + "x"))
        assertTrue("原本一个断点都没有 → 现在 >= 3 个", processed.count { it == EntryNameWrap.SOFT_BREAK } >= 3)
    }

    @Test
    fun `三条名称的第一行填充率都被拉到九成以上`() {
        // 改动前：`訳]-1600x` 不可断，贪心只能退到「翻」后面换行，前两条只填到 ~77%；
        // 第三条本来就能顶到右边界（维护者说「下面的其他书就正常」），处理后不许变差。
        val improved = mutableListOf<String>()
        listOf(
            magicGirl to "魔法少女17",
            muddyCat to "泥棒猫の横恋慕",
            android to "セックスアンドロイド",
        ).forEach { (name, marker) ->
            val before = firstLineFill(EntryNameWrap.withoutSoftBreaks(name))
            val after = firstLineFill(EntryNameWrap.withSoftBreaks(name))
            assertTrue("$marker：处理后的填充率不得变差（$before → $after）", after >= before)
            assertTrue("$marker：处理后第一行应填到九成以上，实测 $after", after >= 0.9)
            if (after > before) improved += marker
        }
        assertEquals(
            "原本提前换行的两条都必须变好",
            listOf("魔法少女17", "泥棒猫の横恋慕"),
            improved,
        )
    }

    // ---------- 各类名称都不被搅坏 ----------

    @Test
    fun `纯中文名称按字断行 不需要补断点`() {
        assertEquals("魔法少女", EntryNameWrap.withSoftBreaks("魔法少女"))
    }

    @Test
    fun `纯英文单词不被拆出多余额外断点`() {
        assertEquals("ComicInfo", EntryNameWrap.withSoftBreaks("ComicInfo"))
        // 词间空格已经是断点，不再补
        assertEquals("Hello World", EntryNameWrap.withSoftBreaks("Hello World"))
    }

    @Test
    fun `数字与字母交界补断点 两向都补`() {
        assertEquals("第" + SB + "1" + SB + "话", EntryNameWrap.withSoftBreaks("第1话"))
        assertEquals("1600\u200Bx", EntryNameWrap.withSoftBreaks("1600x"))
        assertEquals("x\u200B1600", EntryNameWrap.withSoftBreaks("x1600"))
    }

    @Test
    fun `括号与连字符之后补断点`() {
        assertEquals("(" + "第" + SB + "1" + SB + "话)", EntryNameWrap.withSoftBreaks("(第1话)"))
        assertEquals("话)" + SB + "下", EntryNameWrap.withSoftBreaks("话)下"))
        assertEquals("vol.1-\u200B2", EntryNameWrap.withSoftBreaks("vol.1-2"))
    }

    @Test
    fun `无空格长串仍可断行 不会漏掉断点`() {
        val processed = EntryNameWrap.withSoftBreaks("ABCDEFG-1234567890hello")
        assertTrue(processed.contains("-" + EntryNameWrap.SOFT_BREAK))
        assertTrue(processed.contains("0" + EntryNameWrap.SOFT_BREAK + "h"))
    }

    @Test
    fun `已是两行内的短名称不受影响`() {
        listOf("第1话", "1.jpg", "S01E02").forEach { short ->
            assertEquals(short, EntryNameWrap.withoutSoftBreaks(EntryNameWrap.withSoftBreaks(short)))
        }
    }

    @Test
    fun `空名称与单个字符安全`() {
        assertEquals("", EntryNameWrap.withSoftBreaks(""))
        assertEquals("A", EntryNameWrap.withSoftBreaks("A"))
    }

    // ---------- 等宽估算的贪心断行模拟（像素验收在真机） ----------

    /**
     * 简化模型：CJK 宽度 2、其余宽度 1；行宽 = 33 个半角单位（对 1163px 截图、密度 3.21 的
     * 文本列 841px 的一个估算）；断点 = 空格后、两个 CJK 之间（UAX#14 允许）、零宽空格处。
     * 返回第一行占用比例——用来比较「改动前 vs 改动后」，不做像素级断言。
     */
    private fun firstLineFill(processedName: String): Double {
        val lineWidth = 33
        var width = 0
        var lastBreak = 0
        var index = 0
        while (index < processedName.length) {
            width += charWidth(processedName[index])
            if (width > lineWidth) break
            if (isBreakOpportunity(processedName, index)) lastBreak = width
            index++
        }
        return lastBreak.toDouble() / lineWidth
    }

    private fun charWidth(ch: Char): Int = if (isWide(ch)) 2 else if (ch == EntryNameWrap.SOFT_BREAK) 0 else 1

    private fun isWide(ch: Char): Boolean = ch.code >= 0x2E80

    /**
     * 断点判定（模拟用）：零宽空格处恒可断；空格之后可断；两个 CJK 之间本来就可断
     * （真实 UAX#14 里 `訳]` 这种闭合标点前不可断——这正是改动前留空的原因）。
     */
    private fun isBreakOpportunity(processedName: String, index: Int): Boolean {
        val ch = processedName[index]
        if (ch == EntryNameWrap.SOFT_BREAK) return true
        if (ch == ' ') return true
        val next = processedName.getOrNull(index + 1) ?: return false
        if (next == EntryNameWrap.SOFT_BREAK) return true
        return isWide(ch) && isWide(next)
    }
}
