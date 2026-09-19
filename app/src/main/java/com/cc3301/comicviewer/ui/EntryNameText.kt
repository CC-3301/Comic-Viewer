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
 * 名称的行数上限（票 #47 口径：最多两行、不省略号），也是网格档名称块**固定**占用的行数（票 #94）。
 */
internal const val ENTRY_NAME_MAX_LINES = 2

/**
 * 名称块的行数下限（票 #94）——按档位取值，两档调用点都读这一处，行数口径因此只有一份：
 * - **网格档**：固定 [ENTRY_NAME_MAX_LINES] 行——1 行名也占两行。格内元素纵坐标 = 封面高 + 间距
 *   + 名称块高 + 间距，而名称块的高若随实际行数变化，同一排里「1 行名」与「2 行名」两格的
 *   格底（进而下一排的起点）就不齐；固定成行数上限后格子高度与名称内容无关。
 *   「短名称下方留一行空白」的空白就是第二行本身（行高取自 [TextStyle] 的 lineHeight/字体度量），
 *   因此不是硬编码像素值。
 * - **列表档**：1（即 Compose 默认值）——列表行高随名称 1/2 行变化是既有行为，列表不存在
 *   「同排对齐」诉求，因此本票不动它。
 */
internal fun entryNameMinLines(gridMode: Boolean): Int = if (gridMode) ENTRY_NAME_MAX_LINES else 1

/**
 * 条目名称的统一渲染（票 #47）：列表档的行与网格档的格子都走这一处——两档的断行口径因此必然一致
 * （票 #45 AC 要求「同一名称在两种布局下的断行行为相同」）。
 *
 * 名称块的**高度**口径由 `minLines` 决定（票 #94），取值来自 [entryNameMinLines]；该参数**没有默认值**，
 * 两档调用点必须显式声明自己的档位——某个调用点漏传或传错即编译不过，不会默默回落成“两档一样高”。
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
    /** 名称块最少占几行（票 #94）：取值来自 [entryNameMinLines]，按档位不同 */
    minLines: Int,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = EntryNameWrap.withSoftBreaks(name),
        style = style.copy(lineBreak = ENTRY_NAME_LINE_BREAK),
        maxLines = ENTRY_NAME_MAX_LINES,
        minLines = minLines,
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
