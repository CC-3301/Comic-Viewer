package com.cc3301.comicviewer.core.view

/**
 * 条目名称的断行口径（票 #47，纯函数，由 [EntryNameWrapTest] 锁定）。
 *
 * 背景（维护者报告：条目名称「标题右边还有空位，但是直接换行了」）：
 * `[SYNTH] 試作読本17 [中国翻訳]-1600x` 这类名称的尾巴 `訳]-1600x` 被 UAX#14 判成**一个不可断单元**
 * （右括号前不可断、连字符前不可断、纯数字序列内部不可断、数字后接字母不可断），贪心断行塞不下整段，
 * 只能退回上一个断点换行，于是第一行右侧空出好几个字宽。
 *
 * 口径：**能塞就塞**——在 `]` `-` 这类闭合/连接标点之后、以及数字与字母交界处补一个零宽空格
 * （U+200B，宽度为 0、不参与排版宽度），给断行算法多几个合法断点。这属于**展示层**处理：
 * 不改条目 id、排序输入与进度键，只作用于名称这一处文本的渲染。
 *
 * 为什么同时还要给文本样式指定断行配置（见 `ui/EntryNameText.kt`）：断行配置（`LineBreakConfig`）
 * 只在 API 33+ 真正生效（低版本由 `StaticLayoutFactory23` 分支接管，只处理 hyphenation），
 * 零宽空格则在所有版本上都是合法断点，是覆盖 minSdk 26 的兜底路径。
 */
internal object EntryNameWrap {

    /** 零宽空格：合法断点、显示宽度为 0（不改变名称的可见内容） */
    const val SOFT_BREAK: Char = '\u200B'

    /**
     * 之后补一个断点的字符：闭合类标点（UAX#14 禁止在它们**之前**断行）与连字符类
     * （连字符之前不可断、之后可断）。
     */
    private val BREAK_AFTER: Set<Char> = setOf(']', ')', '）', '】', '}', '」', '』', '-', '－', '_')

    /**
     * 在合法位置插入零宽空格后的名称（幂等：已插入过的不会重复插入）。
     * 调用方用它渲染文本，绝不用它做 id / 排序 / 进度键。
     */
    fun withSoftBreaks(name: String): String {
        if (name.isEmpty()) return name
        val out = StringBuilder(name.length + 8)
        name.forEachIndexed { index, ch ->
            out.append(ch)
            val next = name.getOrNull(index + 1) ?: return@forEachIndexed
            if (next == SOFT_BREAK || next.isWhitespace()) return@forEachIndexed
            if (needsBreakBetween(ch, next)) out.append(SOFT_BREAK)
        }
        return out.toString()
    }

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
