package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目名称断行（票 #47 AC 的纯函数落点；票 #92 加「长 ASCII 字母/数字串内断」）：
 * - 三条**合成**样本名必须得到「尾巴里也有断点」的结果（改动前 `訳]-1600x` 是整段不可断单元）；
 *   样本壳形 `[标签] <标题> [中国翻訳]-1600x` 与维护者报障时的真实条目名逐项等价
 *   （字符数 / CJK 数 / 假名数 / 数字位数 / 拉丁片段长度），但**不含任何真实书名或作者标签**——仓库是 PUBLIC；
 *   票 #92 的样本一律用**通用英文词**（`SAMPLE` / `TEXT` / `EXCEED`）与票面给的合成壳形，不用任何真实作品名。
 * - **票 #92 新增口径**：长度 ≥ [EntryNameWrap.MIN_RUN_FOR_INNER_BREAK]（4）的 **ASCII 字母/数字串**，
 *   串内每 [EntryNameWrap.INNER_BREAK_STEP]（2）个字符补一个断点——维护者真机反馈是「**6 字符**的英文词
 *   整词挪到下一行、行尾留一大块空白」，阈值取 4 才能盖住它（阈值 8 时那本书一个字都不会断）；
 *   3 字符以下的串保持原子；其它文字（西里尔/阿拉伯/天城文等）不在本规则内，保持原子。
 * - 可见内容不变（零宽空格宽度为 0）、幂等、不撑破两行封顶的既有口径。
 *
 * 像素级验收（第一行右端空余 ≤ 一个字宽）要在维护者设备上看截图；这里用**等宽估算的贪心断行模拟**
 * 把「填充率明显变好」钉成回归守护：改动前两条只填到 ~77%，处理后 ≥ 90%。
 */
class EntryNameWrapTest {

    private val SB = EntryNameWrap.SOFT_BREAK

    private val withDigits = "[SYNTH] 試作読本17 [中国翻訳]-1600x"
    private val plainTitle = "[SYNTH] 試作猫の見本帳 [中国翻訳]-1600x"
    private val withDlTag = "[SYNTH] フィクションタイトル [中国翻訳] [DL版]-1600x"

    /**
     * 票 #92 新口径的样本（维护者报障形状的合成壳形：短 CJK 标题 + 6 字符通用英文词）。
     * 壳形与字段取自票面正文（`作者`/`示例篇名`/`系列`/`汉化组` 都是占位词，不是真实标签）。
     */
    private val reportedShape = "(0)[作者] 示例篇名 SAMPLE (系列) [汉化组]-1600x"

    /** 长串填充率样本：16 字符通用英文词（最后一次串内断点因此能落在行尾附近） */
    private val withLongWord = "[SYNTH] 試作短題 SAMPLETEXTEXCEED"

    // ---------- 可见内容与幂等 ----------

    @Test
    fun `补断点不改变可见内容`() {
        listOf(withDigits, plainTitle, withDlTag, reportedShape, withLongWord, "合成名称", "无空格长串ABC", "").forEach { name ->
            assertEquals(name, EntryNameWrap.withoutSoftBreaks(EntryNameWrap.withSoftBreaks(name)))
        }
    }

    @Test
    fun `幂等 重复处理不会重复插入`() {
        val once = EntryNameWrap.withSoftBreaks(withDigits)
        assertEquals(once, EntryNameWrap.withSoftBreaks(once))
    }

    // ---------- 黄金样本：逐字符钉住处理结果（<ZW> = 零宽空格） ----------

    @Test
    fun `黄金样本 短串样本的完整产物`() {
        // 票 #92 新口径下 withDigits 的完整产物：ASCII 串内的断点（`SYNTH` / `1600x` 都 ≥ 4）
        // 与 #47 的标点/数字字母断点叠加；CJK 与空格一字未动。
        assertEquals(
            "[SY" + SB + "NT" + SB + "H] 試作読本" + SB + "17 [中国翻訳]" + SB + "-" + SB + "16" + SB + "00" + SB + "x",
            EntryNameWrap.withSoftBreaks(withDigits),
        )
    }

    @Test
    fun `黄金样本 长串内断的完整产物`() {
        // 16 字符的通用英文词在串内每 2 字符一个断点；其余片段（`[SYNTH]`、CJK 标题、空格）只受 #47 规则影响。
        assertEquals(
            "[SY" + SB + "NT" + SB + "H] 試作短題 SA" + SB + "MP" + SB + "LE" + SB + "TE" + SB + "XT" + SB + "EX" + SB + "CE" + SB + "ED",
            EntryNameWrap.withSoftBreaks(withLongWord),
        )
    }

    // ---------- 核心：原本不可断的尾巴现在有断点 ----------

    @Test
    fun `三条合成样本名的尾巴里都有了断点`() {
        // 尾巴 `訳]-1600x`：改动前右括号前、连字符前、纯数字内部、数字与字母之间都不可断
        val processed = EntryNameWrap.withSoftBreaks(withDigits)
        assertTrue("右括号之后要有断点", processed.contains("]" + EntryNameWrap.SOFT_BREAK))
        assertTrue("连字符之后要有断点", processed.contains("-" + EntryNameWrap.SOFT_BREAK))
        assertTrue("数字与字母交界要有断点", processed.contains("0" + EntryNameWrap.SOFT_BREAK + "x"))
        assertTrue("原本一个断点都没有 → 现在 >= 3 个", processed.count { it == EntryNameWrap.SOFT_BREAK } >= 3)
    }

    @Test
    fun `三条合成样本名的第一行填充率都被拉到九成以上`() {
        // 改动前：`訳]-1600x` 不可断，贪心只能退到「翻」后面换行，前两条只填到 ~77%；
        // 第三条本来就能顶到右边界（维护者说「下面的其他书就正常」），处理后不许变差。
        val improved = mutableListOf<String>()
        listOf(
            withDigits to "試作読本17",
            plainTitle to "試作猫の見本帳",
            withDlTag to "フィクションタイトル",
        ).forEach { (name, marker) ->
            val before = firstLineFill(EntryNameWrap.withoutSoftBreaks(name))
            val after = firstLineFill(EntryNameWrap.withSoftBreaks(name))
            assertTrue("$marker：处理后的填充率不得变差（$before → $after）", after >= before)
            assertTrue("$marker：处理后第一行应填到九成以上，实测 $after", after >= 0.9)
            if (after > before) improved += marker
        }
        assertEquals(
            "原本提前换行的两条都必须变好",
            listOf("試作読本17", "試作猫の見本帳"),
            improved,
        )
    }

    // ---------- 各类名称都不被搅坏 ----------

    @Test
    fun `纯中文名称按字断行 不需要补断点`() {
        assertEquals("合成名称", EntryNameWrap.withSoftBreaks("合成名称"))
    }

    @Test
    fun `三字符以下的 ASCII 串保持原子`() {
        // 票 #92：阈值 4 以下不动词内（`ABC` 3 字符、`1.jpg` 的 `1` 与 `jpg`），≥ 4 才补串内断点
        assertEquals("ABC", EntryNameWrap.withSoftBreaks("ABC"))
        assertEquals("1.jpg", EntryNameWrap.withSoftBreaks("1.jpg"))
        // 词间空格已经是断点，不再补；4 字符以上的词本身会在串内断开（下一行是它的正向样例）
        assertEquals("He" + SB + "ll" + SB + "o Wo" + SB + "rl" + SB + "d", EntryNameWrap.withSoftBreaks("Hello World"))
    }

    // ---------- 票 #92：ASCII 字母/数字串内部补断点（行尾填满） ----------

    @Test
    fun `达到阈值的长串每两个字符补一个断点`() {
        // 4 字符（= 阈值）起算，偶数位置补：AB<ZW>CD（<ZW> = 零宽空格）
        assertEquals("AB" + SB + "CD", EntryNameWrap.withSoftBreaks("ABCD"))
        // 3 字符（= 阈值 − 1）不动
        assertEquals("XYZ", EntryNameWrap.withSoftBreaks("XYZ"))
        // 长数字串同理（UAX#14 里数字序列内部也不可断）
        assertEquals("12" + SB + "34", EntryNameWrap.withSoftBreaks("1234"))
    }

    @Test
    fun `维护者报障形状 六字符英文词现在能在词内断开`() {
        // 维护者那本书里的英文词只有 6 字符（票面示例写作 `SAMPLE`）：阈值 8 时本规则对它一字未断、
        // 行尾留白依旧；阈值 4 后该词必须拿到串内断点（SA<ZW>MP<ZW>LE）——把阈值改回 8 本用例即变红。
        val processed = EntryNameWrap.withSoftBreaks(reportedShape)
        assertTrue(
            "`SAMPLE` 必须有串内断点：实测 ${processed.replace(EntryNameWrap.SOFT_BREAK.toString(), "<ZW>")}",
            processed.contains("SA" + SB + "MP" + SB + "LE"),
        )
    }

    @Test
    fun `长西文串不再整词挪到下一行 第一行填充率被拉满`() {
        val before = firstLineFill(EntryNameWrap.withoutSoftBreaks(withLongWord))
        val after = firstLineFill(EntryNameWrap.withSoftBreaks(withLongWord))
        assertTrue("改动前长词整段不可断（实测 $before）", before < 0.6)
        assertTrue("改动后第一行应填满（实测 $after）", after >= 0.9)
    }

    @Test
    fun `长西文串内断不引入连字符`() {
        // 断点是零宽空格，不是 `-`：处理后的可见内容与原文一致，且没有多出连字符
        listOf(withLongWord, reportedShape).forEach { name ->
            val processed = EntryNameWrap.withSoftBreaks(name)
            assertEquals(name, EntryNameWrap.withoutSoftBreaks(processed))
            assertEquals("不得引入连字符：$name", name.count { it == '-' }, processed.count { it == '-' })
        }
    }

    @Test
    fun `数字与字母交界补断点 两向都补`() {
        assertEquals("第" + SB + "1" + SB + "话", EntryNameWrap.withSoftBreaks("第1话"))
        // 票 #92 起：数字串内部也会按步长补（`1600x` 的 `16<ZW>00<ZW>x` 同时含两类断点）
        assertEquals("16" + SB + "00" + SB + "x", EntryNameWrap.withSoftBreaks("1600x"))
        assertEquals("x" + SB + "1" + SB + "60" + SB + "0", EntryNameWrap.withSoftBreaks("x1600"))
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

    /** 等宽模型的宽字符界（**仅测试模型用**：生产侧只看 ASCII，已无码点界） */
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
