package com.cc3301.comicviewer.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.cc3301.comicviewer.core.view.EntryNameWrap

/**
 * 条目名称的统一渲染（票 #47）：列表档的行与网格档的格子都走这一处——两档的断行口径因此必然一致
 * （票 #45 AC 要求「同一名称在两种布局下的断行行为相同」）。
 *
 * 两件事一起做：
 * 1. **零宽空格兜底**（[EntryNameWrap.withSoftBreaks]）：`訳]-1600x` 这类尾巴在 UAX#14 下是整段不可断单元，
 *    补上断点后贪心断行才能把第一行填满。零宽空格宽度为 0，所有版本（minSdk 26）都生效。
 * 2. **断行配置**：[LineBreak] 用「贪心 + 宽松 + 按字符断」——它在 **API 33+** 才真正生效
 *    （低版本由 StaticLayoutFactory 的 23 分支接管，只处理 hyphenation），所以与上面的兜底并存。
 *
 * 仍然是「最多两行、不省略号」（spec 既有口径），不撑破行/格子，也不改动名称原文（id/排序/进度键照旧）。
 */
@Composable
internal fun EntryNameText(
    name: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = EntryNameWrap.withSoftBreaks(name),
        style = style.copy(lineBreak = ENTRY_NAME_LINE_BREAK),
        maxLines = 2,
        overflow = TextOverflow.Clip,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/**
 * 名称的断行配置（票 #47）：
 * - [LineBreak.Strategy.Simple]：贪心断行（能塞就塞），不做整段优化、不把行拉平均；
 * - [LineBreak.Strictness.Loose]：最宽松的禁则（允许在 々 这类字符前断行）；
 * - [LineBreak.WordBreak.Default]：允许在字符之间断行（不使用「按短语不断」的 [LineBreak.WordBreak.Phrase]）。
 */
private val ENTRY_NAME_LINE_BREAK: LineBreak = LineBreak(
    strategy = LineBreak.Strategy.Simple,
    strictness = LineBreak.Strictness.Loose,
    wordBreak = LineBreak.WordBreak.Default,
)
