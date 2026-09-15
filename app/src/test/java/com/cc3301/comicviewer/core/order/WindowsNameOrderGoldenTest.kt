package com.cc3301.comicviewer.core.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黄金数据集：Windows 资源管理器名称排序硬约束（spec）。
 * 每一条都编码一条规则；维护者提供真实目录名样本时追加到 [fullOrderedSet]。
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
        "aa",        // a1 的数字段 < aa 的拉丁段（数字 < 拉丁）
        "b楼",       // 拉丁段整体先于汉字段（Windows NLS 实测）
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
        for (i in 0 until fullOrderedSet.size - 1) {
            val a = fullOrderedSet[i]
            val b = fullOrderedSet[i + 1]
            assertTrue(
                "期望 $a < $b，实际 compare=${cmp.compare(a, b)}",
                cmp.compare(a, b) < 0,
            )
        }
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

    @Test
    fun `中文按拼音排序`() {
        assertTrue(cmp.compare("二郎", "三味") < 0)  // er < san（code point 会给反例）
        assertTrue(cmp.compare("白山", "第二话") < 0) // bai < di
    }

    @Test
    fun `前缀短者在前`() {
        assertTrue(cmp.compare("a", "a1") < 0)
        assertTrue(cmp.compare("a1", "a1b") < 0)
    }

    @Test
    fun `数值相等的前导零按短者在前`() {
        // Windows 逻辑比较（StrCmpLogicalW 同源行为）：01 与 1 数值相等时 '0' < '1'
        assertTrue(cmp.compare("第01话", "第1话") < 0)
        assertTrue(cmp.compare("01.jpg", "1.jpg") < 0)
    }

    @Test
    fun `相同名称比较为零`() {
        assertEquals(0, cmp.compare("第2话", "第2话"))
    }
}
