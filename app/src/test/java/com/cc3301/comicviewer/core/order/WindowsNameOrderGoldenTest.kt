package com.cc3301.comicviewer.core.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黄金数据集：Windows 资源管理器名称排序硬约束（spec）。
 * 每一条都编码一条规则；样本一律用**合成名**——仓库是 PUBLIC，真实书库名一律不进仓库，
 * 真实顺序只由 [WindowsNameOrderLocalProbeTest] 读 `references/name-order-expected.txt`（不入库）比对。
 */
class WindowsNameOrderGoldenTest {

    private val cmp = WindowsNameOrder.COMPARATOR

    /** 全序表：表中任意相邻两项，前项 < 后项；整体排序后必须等于自身（语义经 Windows NLS 实测校准） */
    private val fullOrderedSet = listOf(
        "!a",        // 符号最先
        "#1",        // 符号内按字符序
        "1a",        // 数字类 < 拉丁字母类
        "2.jpg",     // 数字按数值
        "10.jpg",    // 2 < 10（数值，非字典序）
        "a-1",       // a 后跟符号段 < a 后跟数字段（符号 < 数字）
        "a1",
        "a1b",       // 前缀短者在前
        "aa",        // token 前缀：a1 < aa（首段 a 与 aa：a 是 aa 的前缀）
        "b楼",       // 拉丁段整体先于汉字段（Windows NLS 实测）
        "カナデモジ本",  // 假名段先于汉字段（票 #71，ka）
        "クジラ雲",    // ku
        "ホシノ歌",    // ho
        "阿汤",      // 拼音 a；汉字段内部按拼音
        "白山",      // bai
        "第2话",     // di；数字段 2
        "第10话",    // 数值 2 < 10
        "第二话",     // 数字段 < 汉字段（第2话 < 第二话）
        "二郎",      // er（拼音守护样本：code point 是 三<二，拼音必须 二<三）
        "三味",      // san
        "中篇",      // zhong
    )

    @Test
    fun `全序表逐对比较成立`() {
        assertAscending(fullOrderedSet)
    }

    @Test
    fun `全序表排序后保持原序`() {
        assertEquals(fullOrderedSet, fullOrderedSet.sortedWith(cmp))
    }

    @Test
    fun `数字按数值比较`() {
        assertTrue(cmp.compare("第2话", "第10话") < 0)
        assertTrue(cmp.compare("2.jpg", "10.jpg") < 0)
        assertTrue(cmp.compare("img-2a", "img-10a") < 0)
    }

    @Test
    fun `类优先级 符号小于数字小于字母`() {
        assertTrue(cmp.compare("!a", "1a") < 0)
        assertTrue(cmp.compare("1a", "aa") < 0)
        assertTrue(cmp.compare("a-1", "aa") < 0)
    }

    @Test
    fun `大小写不影响主要顺序`() {
        // a 的 primary 顺序不受大小写影响
        assertTrue(cmp.compare("a.txt", "B.txt") < 0)
        assertTrue(cmp.compare("file 2", "file 10") < 0)
        assertTrue(cmp.compare("APPLE", "banana") < 0)
    }

    /** 合成样本：目录名形态（前缀符号 + 名字 + 后缀符号/数字），假名整段先于汉字整段 */
    private val syntheticFolderNames = listOf(
        "(0)[x] カナデモジ本 [合成]-1280x",
        "(0)[x] クジラ雲 [合成]-1280x",
        "(0)[x] ホシノ歌 [合成]-1280x",
        "(0)[x] 阿呆与辣妹 [合成]-1600x",
        "(0)[x] 白菜与萝卜 [合成]-1600x",
    )

    @Test
    fun `假名先于汉字（目录名形态）`() {
        assertAscending(syntheticFolderNames)
        assertEquals(syntheticFolderNames, syntheticFolderNames.sortedWith(cmp))
    }

    @Test
    fun `假名按五十音序`() {
        assertTrue(cmp.compare("か", "く") < 0)   // ka < ku
        assertTrue(cmp.compare("く", "ほ") < 0)   // ku < ho
        assertTrue(cmp.compare("あか", "いか") < 0)
        assertTrue(cmp.compare("カナデモジ本", "クジラ雲") < 0)
    }

    @Test
    fun `平假名与片假名主要层级同权`() {
        // 同音平/片假名紧挨，片假名不得整块落在平假名之后（票 #71）
        assertTrue(cmp.compare("カ", "が") < 0)
        assertTrue(cmp.compare("か", "キ") < 0)
        assertEquals(
            listOf("あ", "ア", "か", "カ", "く", "ク"),
            listOf("く", "カ", "あ", "ク", "か", "ア").sortedWith(cmp),
        )
    }

    @Test
    fun `半角片假名并入假名段且与全角同权`() {
        // 半角折全角后比较：ｶ 落在 カ 旁边，不会整块掉到全角假名之后
        assertEquals(
            listOf("か", "カ", "ｶ", "ク", "ｸ"),
            listOf("ｸ", "ｶ", "ク", "カ", "か").sortedWith(cmp),
        )
        assertTrue(cmp.compare("ｶ", "く") < 0)
    }

    @Test
    fun `假名段先于汉字段`() {
        assertTrue(cmp.compare("ホシノ歌", "阿汤") < 0)
        assertTrue(cmp.compare("b楼", "カナデモジ本") < 0)  // 拉丁段仍先于假名段
    }

    @Test
    fun `中文按拼音排序`() {
        assertTrue(cmp.compare("二郎", "三味") < 0)  // er < san（code point 会给反例）
        assertTrue(cmp.compare("白山", "第二话") < 0) // bai < di
    }

    @Test
    fun `GB2312 表外字仍归汉字段`() {
        // 表外字（許 U+8A31 / 嬢 U+5B22 / 獣 U+7363：GB2312 编不出）是字母、不是符号，
        // 段优先级与表内汉字一致；具体位次与 Windows NLS 的差异见 SPEC「已知限制（票 #83）」
        for (name in listOf("許田", "嬢花", "獣森")) {
            assertTrue("$name 应排在符号段之后", cmp.compare("-", name) < 0)
            assertTrue("$name 应排在数字段之后", cmp.compare("9", name) < 0)
            assertTrue("$name 应排在拉丁段之后", cmp.compare("a", name) < 0)
            assertTrue("$name 应排在假名段之后", cmp.compare("カ", name) < 0)
        }
    }

    @Test
    fun `GB2312 表外字两两不等且比较稳定`() {
        // 表外字之间不得并列、方向可重复（本实现内部全序；与 Windows 的位次差属已知限制）
        val outOfTable = listOf("許田", "嬢花", "獣森")
        outOfTable.indices.forEach { i ->
            outOfTable.indices.forEach { j ->
                if (i != j) {
                    val first = cmp.compare(outOfTable[i], outOfTable[j])
                    val second = cmp.compare(outOfTable[i], outOfTable[j])
                    assertTrue("${outOfTable[i]} 与 ${outOfTable[j]} 不应相等", first != 0)
                    assertTrue("重复比较应给出同方向", first == second)
                }
            }
        }
        assertEquals(outOfTable.sortedWith(cmp), outOfTable.sortedWith(cmp).sortedWith(cmp))
    }

    @Test
    fun `前缀短者在前`() {
        assertTrue(cmp.compare("a", "a1") < 0)
        assertTrue(cmp.compare("a1", "a1b") < 0)
    }

    @Test
    fun `数值相等的前导零按整体原字典序回退`() {
        // 主比较阶段不回退；全等后整体回退：01 < 1（Windows NLS 实测一致）
        assertTrue(cmp.compare("第01话", "第1话") < 0)
        assertTrue(cmp.compare("01.jpg", "1.jpg") < 0)
        assertTrue(cmp.compare("0", "000") < 0)
    }

    @Test
    fun `大小写等价时前缀规则生效`() {
        // 忽略大小写时 a 是 A1 的前缀（Windows 语义；SP3 回归守护）
        assertTrue(cmp.compare("a", "A1") < 0)
        assertTrue(cmp.compare("A1", "a1") < 0)  // 等价变体定序稳定（大写码点在前）
    }

    @Test
    fun `代理对汉字不落入符号段`() {
        // 𠮷 U+20BB7（CJK 扩展 B，代理对）；若被逐 Char 误判为符号段则此断言反转
        assertTrue(cmp.compare("A", "𠮷") < 0)
    }

    @Test
    fun `相同名称比较为零`() {
        assertEquals(0, cmp.compare("第2话", "第2话"))
    }

    /** 全序表式断言：任意相邻两项满足前项 < 后项（全序表与合成目录名样本共用） */
    private fun assertAscending(names: List<String>) {
        for (i in 0 until names.size - 1) {
            val a = names[i]
            val b = names[i + 1]
            assertTrue(
                "期望 $a < $b，实际 compare=${cmp.compare(a, b)}",
                cmp.compare(a, b) < 0,
            )
        }
    }
}
