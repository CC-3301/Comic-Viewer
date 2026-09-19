package com.cc3301.comicviewer.core.order

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Windows 资源管理器式名称自然排序（纯 JVM，无 Android 依赖）。
 * 符号/数字/拉丁/汉字段的语义已用 Windows NLS（zh-CN CompareInfo）逐对实测校准；假名段（票 #71 新增规则）
 * 无 NLS 复测，按维护者截图 `references/11.png` 的实测顺序校准。
 *
 * 规则（spec 硬约束，黄金数据集守护）：
 * 1. 名称按码点切分为同类型字符段：符号 < 数字 < 拉丁字母 < 假名 < 汉字/其它非 ASCII 字母；段类型不同立即分胜负
 * 2. 数字段按数值比较（第2话 < 第10话）
 * 3. 拉丁段大写化比较（primary 大小写不敏感）
 * 4. 假名段（票 #71）按五十音序，用**日语** Collator——与汉字段的**中文** Collator 是两份，不能混用：
 *    平/片假名主要权重等价，同音平假名与片假名紧挨（片假名不会整块后置）；
 *    半角片假名 U+FF66–FF9D 归入假名段，比较前 NFKC 折成全角——Java 的日语 Collator 不折半角，
 *    不折会让半角假名整块落在全角假名之后（半角浊音符 U+FF9E/U+FF9F 不在半角假名区间内，仍按“其它非 ASCII 字母”归类）
 * 5. 汉字/非 ASCII 字母段（票 #71 起假名不再混在此）用中文 Collator 按拼音（二郎 < 三味）
 * 6. 主比较阶段不做段内平局回退；逐段全等且段数相同时，整体按原串字典序回退——
 *    数值相等的前导零（01 < 1）与大小写等价的前缀（a < A1）由此统一获得 Windows 行为
 *
 * Collator 实例非线程安全，比较内同步。
 *
 * 与相邻的 `core/sort` 分工：本包只放名称比较本身（[COMPARATOR]）；排序**设置**（档位与方向）在
 * `core/sort/SortSetting.kt`。两包都在 `core`，改排序时不要放错边。
 */
object WindowsNameOrder {

    val COMPARATOR: Comparator<String> = Comparator { a, b -> compare(a, b) }

    /** 假名段：五十音序（票 #71；平/片假名主要权重等价） */
    private val kanaCollator: Collator = Collator.getInstance(Locale.JAPANESE)

    /** 汉字/非 ASCII 字母段：拼音序 */
    private val wordCollator: Collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)

    /** ordinal 即段类型优先级：符号 < 数字 < 拉丁 < 假名 < 汉字/非 ASCII 字母 */
    private enum class Kind { SYMBOL, NUMBER, LATIN, KANA, WORD }

    private data class Token(val kind: Kind, val text: String)

    fun compare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val cmp = compareToken(ta[i], tb[i])
            if (cmp != 0) return cmp
        }
        if (ta.size != tb.size) return ta.size - tb.size
        // 段级 primary 全等（大小写/前导零等价变体）：整体原串回退保证全序（01 < 1；A1 < a1）
        return a.compareTo(b)
    }

    private fun tokenize(s: String): List<Token> {
        val tokens = ArrayList<Token>(s.length)
        val run = StringBuilder()
        var kind: Kind? = null

        fun classify(cp: Int): Kind = when {
            cp in '0'.code..'9'.code -> Kind.NUMBER
            cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code -> Kind.LATIN
            isKana(cp) -> Kind.KANA
            Character.isLetter(cp) -> Kind.WORD   // 汉字/其它非 ASCII 字母（含增补平面汉字，代理对按码点判定）
            else -> Kind.SYMBOL
        }

        fun flush() {
            if (run.isNotEmpty()) tokens += Token(kind ?: Kind.SYMBOL, run.toString())
        }
        for (cp in s.codePoints()) {
            val k = classify(cp)
            if (k != kind) {
                flush()
                kind = k
                run.setLength(0)
            }
            run.appendCodePoint(cp)
        }
        flush()
        return tokens
    }

    private fun compareToken(x: Token, y: Token): Int {
        if (x.kind != y.kind) return x.kind.ordinal - y.kind.ordinal
        return when (x.kind) {
            Kind.NUMBER -> compareNumbers(x.text, y.text)
            Kind.LATIN -> compareLatin(x.text, y.text)
            Kind.KANA -> compareKana(x.text, y.text)
            Kind.WORD -> compareWords(x.text, y.text)
            Kind.SYMBOL -> x.text.compareTo(y.text)
        }
    }

    /**
     * 假名区间内的**字母**才算假名段：段内不是字母的码点（`・` U+30FB、`゠` U+30A0、`゛゜` U+309B/U+309C 等）
     * 仍是符号段，与 Windows 一致（`・` 属标点，不因落在片假名区间就升到假名段）。
     * 平假名 U+3040–309F、片假名 U+30A0–30FF、半角片假名 U+FF66–FF9D。
     */
    private fun isKana(cp: Int): Boolean =
        (cp in 0x3040..0x309F || cp in 0x30A0..0x30FF || cp in 0xFF66..0xFF9D) &&
            Character.isLetter(cp)

    /** 无符号十进制：先比去前导零后的位数，再逐位；不回退（等值交给整体回退） */
    private fun compareNumbers(a: String, b: String): Int {
        val na = a.trimStart('0')
        val nb = b.trimStart('0')
        if (na.length != nb.length) return na.length - nb.length
        return na.compareTo(nb)
    }

    /** 拉丁段：大写化比较（primary 大小写不敏感），不回退 */
    private fun compareLatin(a: String, b: String): Int =
        a.uppercase(Locale.ROOT).compareTo(b.uppercase(Locale.ROOT))

    /**
     * 假名段：五十音序（日语 Collator，默认强度 Tertiary），半角先折全角，不回退。
     * 该强度上平/片假名的主要权重等价（差异只在次级）：同音平假名与片假名紧挨，片假名不会整块后置；
     * 改强度会弄坏这条（改成 PRIMARY 后 `カ` 与 `が` 同权，只能靠整体原串回退定序）。
     */
    private fun compareKana(a: String, b: String): Int = synchronized(kanaCollator) {
        kanaCollator.compare(
            Normalizer.normalize(a, Normalizer.Form.NFKC),
            Normalizer.normalize(b, Normalizer.Form.NFKC),
        )
    }

    /** 汉字/非 ASCII 字母段：拼音序（中文 Collator Tertiary），不回退 */
    private fun compareWords(a: String, b: String): Int =
        synchronized(wordCollator) { wordCollator.compare(a, b) }
}
