package com.cc3301.comicviewer.core.order

import java.text.Collator
import java.util.Locale

/**
 * Windows 资源管理器式名称自然排序（纯 JVM，无 Android 依赖）。
 * 语义已用 Windows NLS（zh-CN CompareInfo）逐对实测校准。
 *
 * 规则（spec 硬约束，黄金数据集守护）：
 * 1. 名称切分为同类型字符段：符号 < 数字 < 拉丁字母 < 汉字/其它非 ASCII 字母；段类型不同立即分胜负
 * 2. 数字段按数值比较（第2话 < 第10话）；数值相等时前导零少者在前（01 < 1，Windows 实测一致）
 * 3. 拉丁段大小写不敏感（primary 顺序）；汉字/非 ASCII 字母段用中文 Collator 按拼音（二郎 < 三味）
 * 4. 逐段全相等时，前缀短者在前（a < a1）
 *
 * Collator 实例非线程安全，比较内同步。
 */
object WindowsNameOrder {

    val COMPARATOR: Comparator<String> = Comparator { a, b -> compare(a, b) }

    private const val KIND_SYMBOL = 0
    private const val KIND_NUMBER = 1
    private const val KIND_LATIN = 2
    private const val KIND_CJK = 3

    private val collator: Collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)

    private data class Token(val kind: Int, val text: String)

    fun compare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val cmp = compareToken(ta[i], tb[i])
            if (cmp != 0) return cmp
        }
        // 逐段相等：前缀短者在前（a < a1）
        return ta.size - tb.size
    }

    private fun tokenize(s: String): List<Token> {
        val tokens = ArrayList<Token>(s.length)
        val run = StringBuilder()
        var kind = -1
        fun flush() {
            if (run.isNotEmpty()) tokens += Token(kind, run.toString())
        }
        for (ch in s) {
            val k = when {
                ch in '0'..'9' -> KIND_NUMBER
                ch in 'a'..'z' || ch in 'A'..'Z' -> KIND_LATIN
                ch.isLetter() -> KIND_CJK   // 汉字、假名、谚文及其它非 ASCII 字母
                else -> KIND_SYMBOL
            }
            if (k != kind) {
                flush()
                kind = k
                run.setLength(0)
            }
            run.append(ch)
        }
        flush()
        return tokens
    }

    private fun compareToken(x: Token, y: Token): Int {
        if (x.kind != y.kind) return x.kind - y.kind
        return when (x.kind) {
            KIND_NUMBER -> compareNumbers(x.text, y.text)
            KIND_LATIN -> compareLatin(x.text, y.text)
            KIND_CJK -> compareWords(x.text, y.text)
            else -> x.text.compareTo(y.text)
        }
    }

    /** 无符号十进制：先比去前导零后的位数，再逐位；全等则原串字典序（01 < 1） */
    private fun compareNumbers(a: String, b: String): Int {
        val na = a.trimStart('0')
        val nb = b.trimStart('0')
        if (na.length != nb.length) return na.length - nb.length
        val cmp = na.compareTo(nb)
        if (cmp != 0) return cmp
        return a.compareTo(b)
    }

    /** 拉丁段：大写化比较（primary 大小写不敏感）；回退原串打破平局 */
    private fun compareLatin(a: String, b: String): Int {
        val cmp = a.uppercase(Locale.ROOT).compareTo(b.uppercase(Locale.ROOT))
        if (cmp != 0) return cmp
        return a.compareTo(b)
    }

    /** 汉字/非 ASCII 字母段：拼音序；Collator 判等时回退原串打破平局 */
    private fun compareWords(a: String, b: String): Int {
        val cmp = synchronized(collator) { collator.compare(a, b) }
        if (cmp != 0) return cmp
        return a.compareTo(b)
    }
}
