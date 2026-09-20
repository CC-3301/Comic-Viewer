package com.cc3301.comicviewer.core.view

/**
 * 条目名称的断行口径（票 #47，纯函数，由 [EntryNameWrapTest] 锁定）。
 *
 * 背景（维护者报告：条目名称「标题右边还有空位，但是直接换行了」）：
 * `[SYNTH] 試作読本17 [中国翻訳]-1600x` 这类名称的尾巴 `訳]-1600x` 被 UAX#14 判成**一个不可断单元**
 * （右括号前不可断、连字符前不可断、纯数字序列内部不可断、数字后接字母不可断），贪心断行塞不下整段，
 * 只能退回上一个断点换行，于是第一行右侧空出好几个字宽。
 *
 * 口径：**能塞就塞**——在 `]` `-` 这类闭合/连接标点之后、数字与字母交界处补一个零宽空格
 * （U+200B，宽度为 0、不参与排版宽度），给断行算法多几个合法断点。
 *
 * 票 #92 补上第二段（维护者真机反馈：`(0)[作者] 示例篇名 SAMPLE (系列) [汉化组]-1600x` 这类名字，
 * 第一行在「示例篇名」后面留了一大块空白，因为贪心断行遇到一个 **6 字符**的英文词（示例里写作 `SAMPLE`）
 * 塞不下就整词挪到下一行）：**够长的 ASCII 字母/数字串在串内也补断点**（每 [INNER_BREAK_STEP] 个字符一个），
 * 词内因此可以就地断开、把行尾填满。维护者在 a/b/c 三候选中选了「b：允许长词内断、把行尾填满，不引入连字符」
 * ——所以这里只补零宽空格，绝不出连字符。
 *
 * 这属于**展示层**处理：不改条目 id、排序输入与进度键，只作用于名称这一处文本的渲染。
 *
 * 为什么同时还要给文本样式指定断行配置（见 `ui/EntryNameText.kt`）：断行配置（`LineBreakConfig`）
 * 只在 API 33+ 真正生效（低版本由 `StaticLayoutFactory23` 分支接管，只处理 hyphenation），
 * 零宽空格则在所有版本上都是合法断点，是覆盖 minSdk 26 的兜底路径。
 */
internal object EntryNameWrap {

    /** 零宽空格：合法断点、显示宽度为 0（不改变名称的可见内容） */
    const val SOFT_BREAK: Char = '\u200B'

    /**
     * 「ASCII 字母/数字串」内补断点的串长下限（含）：达到这个长度的串才在串内补断点（票 #92）。
     * 取 **4**——维护者那本书里的英文词只有 6 个字符，阈值 8 时新规则对它一个字都没断（行尾留白依旧）；
     * 4 能覆盖常见的 4–6 字符词，1–3 字符残词的留白可忽略。
     * 短词（≤ [MIN_RUN_FOR_INNER_BREAK] − 1 字符）保持原子。
     */
    const val MIN_RUN_FOR_INNER_BREAK: Int = 4

    /**
     * 长串内的断点步长（票 #92）：每这么多个字符补一个断点。取 2 而不是 1——
     * 行尾最多余 1 个字符（约 8dp）的空白，代价是插入的零宽空格少一半（对西文连字/字距的影响更小）。
     */
    const val INNER_BREAK_STEP: Int = 2

    /**
     * 之后补一个断点的字符：闭合类标点（UAX#14 禁止在它们**之前**断行）与连字符类
     * （连字符之前不可断、之后可断）。
     */
    private val BREAK_AFTER: Set<Char> = setOf(']', ')', '）', '】', '}', '」', '』', '-', '－', '_')

    /**
     * 在合法位置插入零宽空格后的名称（幂等：已插入过的不会重复插入）。
     * 调用方用它渲染文本，绝不用它做 id / 排序 / 进度键。
     *
     * 断点两段：① 标点之后 / 数字字母交界（票 #47）；② 够长的 ASCII 字母/数字串内部（票 #92）。
     * 单次扫描同时处理两段：串内的位置需要知道**整段串长**，因此进入一个串时一次性把它的范围找出来。
     */
    fun withSoftBreaks(name: String): String {
        if (name.isEmpty()) return name
        val out = StringBuilder(name.length + 8)
        // 当前所处的 ASCII 字母/数字串范围（不在串内时为 -1）：用来判定「串内断点」是否该在这个位置补
        var runStart = -1
        var runEnd = -1
        name.forEachIndexed { index, ch ->
            if (!isAsciiLetterOrDigit(ch)) {
                runStart = -1
                runEnd = -1
            } else if (runStart < 0) {
                runStart = index
                runEnd = index
                while (runEnd + 1 < name.length && isAsciiLetterOrDigit(name[runEnd + 1])) runEnd++
            }
            out.append(ch)
            val next = name.getOrNull(index + 1) ?: return@forEachIndexed
            if (next == SOFT_BREAK || next.isWhitespace()) return@forEachIndexed
            val innerBreak = isAsciiLetterOrDigit(next) && runStart >= 0 &&
                (runEnd - runStart + 1) >= MIN_RUN_FOR_INNER_BREAK &&
                (index - runStart + 1) % INNER_BREAK_STEP == 0
            if (needsBreakBetween(ch, next) || innerBreak) out.append(SOFT_BREAK)
        }
        return out.toString()
    }

    /**
     * 「ASCII 字母/数字串」的成员（票 #92）：**只看 ASCII**——`a-z`/`A-Z`/`0-9`。
     *
     * 不用 `isLetterOrDigit`：那会把西里尔/阿拉伯/天城文/泰文也算进来，在串内插零宽空格会静默切断
     * 这些连写体系的词形；CJK/假名/谚文则本来就逐字可断，也不需要补断点。其余文字因此保持原子。
     */
    private fun isAsciiLetterOrDigit(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    /** 两个相邻字符之间是否补断点（`]`/`-` 这类之后，以及数字与字母的交界） */
    private fun needsBreakBetween(current: Char, next: Char): Boolean = when {
        current in BREAK_AFTER -> true
        // 数字与字母交界（含 CJK：`第1话` 这类也算字母）：UAX#14 不允许在数字与字母之间断行
        current.isDigit() && next.isLetter() -> true
        current.isLetter() && next.isDigit() -> true
        else -> false
    }

    /** 去掉补过的零宽空格（测试与调试用：可见内容必须与原名一致） */
    fun withoutSoftBreaks(name: String): String = name.replace(SOFT_BREAK.toString(), "")
}
