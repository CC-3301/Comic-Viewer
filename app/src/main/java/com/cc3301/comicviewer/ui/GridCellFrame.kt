package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.gridNameRow
import kotlin.math.roundToInt

/** 量高副本的槽 id（只测量、不放置；份数与理由见 [GridCellFrame] 的 KDoc） */
private const val NAME_PROBE_SLOT: String = "grid-cell-name-probe"

/** 封面槽 id */
private const val COVER_SLOT: String = "grid-cell-cover"

/** 名字槽 id（真正放置的那份） */
private const val NAME_SLOT: String = "grid-cell-name"

/**
 * 网格格子的骨架（票 #106 r2）：把**封面盒**与**名字行**摆在同一条中线上——封面盒居中、名字行宽度等于封面宽。
 *
 * 两件东西一起定：
 * 1. **封面盒尺寸**：高取「格高（[CoverLayout.gridCellHeight]）」与「格子可用高度里名字块之外的部分」中的
 *    较小者（[CoverLayout.gridCellSize]），宽按格比例反算（等比、不拉伸）。横屏 2 格格宽大 ⇒ 格高超过可视
 *    高度，封面据此收缩、两侧留白。
 * 2. **名字行摆位**：宽 = 封面宽、左缘与封面左缘对齐（[gridNameRow]）；**行内文字对齐也由本帧给**
 *    （[name] 的入参）：收缩态 [TextAlign.Center]（短书名的**字形**才落在封面中线上，不是只有行盒居中），
 *    未收缩态 [TextAlign.Start]（既有口径，竖屏 2/3/4 格因此逐像素不变）。
 *
 * 名字块高**取自真渲染**：测量出的高度进封面预算——不靠「行高 × 行数」的字体度量推算（推算偏小就会把
 * 名字行挤出可视区）。名字槽因此被组合两份：一份**量高**（只测量、不放置、清掉语义），一份**真正放置**。
 *
 * 为什么是 [SubcomposeLayout]（而不是 `Layout` + 子件）：两件要在**测量之后**才能定——① 封面宽 = f(可用高
 * − 名字块高)，名字块高得先量；② 行内文字对齐是给 [name] 的**参数** ⇒ 名字槽必须在测量之后组合
 * （`Layout` 的子件在测量前组合，参数传不进去）。量高那一份仍独立组合：Compose 硬约束「同一个 Measurable
 * 一个测量回合只能测一次」，槽的宽度只能由本帧给定。量高那一份用格宽，因此要求名字槽的高度**与宽度无关**
 * （[EntryNameText] 网格档 `minLines == maxLines == 2` ⇒ 恒两行；对齐更不影响高度）。
 *
 * 两个槽的**占位由本帧给定**，槽内不必自己算尺寸：
 * - [cover] 的父级盒 = 封面盒（固定宽高），槽内用 `fillMaxSize` 填满即可（生产侧在槽内按同一份
 *   [CoverLayout.gridCellSize] 复算盒，两者恒等）；
 * - [name] 的父级盒宽 = 封面宽，槽内用 `fillMaxWidth` 填满（名字行因此与封面同宽同中线）。
 *
 * @param cellMaxHeight 格子高度上限（票 #106：= 可视高度 − 上下留白，由 [com.cc3301.comicviewer.core.view.gridCellMaxHeight] 给出）
 * @param spacing 封面与名字行之间的间距（网格档的格子内间距，调用点传同一份常量）
 * @param name 名字槽：入参是本帧按收缩态指定的**行内文字对齐**（收缩态 Center、未收缩态 Start）
 */
@Composable
internal fun GridCellFrame(
    cellMaxHeight: Dp,
    spacing: Dp,
    cover: @Composable () -> Unit,
    name: @Composable (TextAlign) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = cellMaxHeight),
    ) { constraints ->
        val cellWidthPx = constraints.maxWidth
        val spacingPx = spacing.roundToPx()
        // 量高副本（真渲染、与宽度/对齐无关）：先用格宽量它拿名字块高
        val nameProbe = subcompose(NAME_PROBE_SLOT) {
            Box(Modifier.fillMaxWidth().clearAndSetSemantics {}) { name(TextAlign.Start) }
        }.first().measure(Constraints(maxWidth = cellWidthPx))
        // 封面可用高度 = 格子高度上限 − 间距 − 名字块高；上限无界（未施加 heightIn）时按无约束处理
        val cellMaxHeightPx = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.POSITIVE_INFINITY
        val coverSize = CoverLayout.gridCellSize(
            cellWidth = cellWidthPx.toFloat(),
            availableHeight = cellMaxHeightPx - spacingPx - nameProbe.height,
        )
        val coverWidthPx = coverSize.width.roundToInt()
        val coverHeightPx = coverSize.height.roundToInt()
        val nameRow = gridNameRow(cellWidthPx.toFloat(), coverSize.width)
        val nameWidthPx = nameRow.width.roundToInt()
        val nameLeftPx = nameRow.left.roundToInt()
        // 封面盒（固定宽高）：槽内 fillMaxSize 因此恰好填满
        val coverPlaceable = subcompose(COVER_SLOT) {
            Box(Modifier.fillMaxSize()) { cover() }
        }.first().measure(Constraints.fixed(coverWidthPx, coverHeightPx))
        // 名字行（固定宽 = 封面宽）：收缩态行内文字居中（否则短书名的字形仍贴行左缘、看着还是「居左」），
        // 未收缩态左对齐 = 既有口径（竖屏 2/3/4 格逐像素不变）
        val shrunk = coverWidthPx < cellWidthPx
        val namePlaceable = subcompose(NAME_SLOT) {
            Box(Modifier.fillMaxWidth()) { name(if (shrunk) TextAlign.Center else TextAlign.Start) }
        }.first().measure(Constraints(minWidth = nameWidthPx, maxWidth = nameWidthPx))
        layout(width = cellWidthPx, height = coverHeightPx + spacingPx + namePlaceable.height) {
            coverPlaceable.place(nameLeftPx, 0)
            namePlaceable.place(nameLeftPx, coverHeightPx + spacingPx)
        }
    }
}
