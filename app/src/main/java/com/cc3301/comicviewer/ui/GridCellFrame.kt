package com.cc3301.comicviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.cc3301.comicviewer.core.view.CoverLayout
import com.cc3301.comicviewer.core.view.gridNameRow
import kotlin.math.roundToInt

/**
 * 网格格子的骨架（票 #106 r2）：把**封面盒**与**名字行**摆在同一条中线上——封面盒居中、名字行宽度等于封面宽。
 *
 * 两件东西一起定：
 * 1. **封面盒尺寸**：高取「格高（[CoverLayout.gridCellHeight]）」与「格子可用高度里名字块之外的部分」中的
 *    较小者（[CoverLayout.gridCellSize]），宽按格比例反算（等比、不拉伸）。横屏 2 格格宽大 ⇒ 格高超过可视
 *    高度，封面据此收缩、两侧留白。
 * 2. **名字行摆位**：宽 = 封面宽、左缘与封面左缘对齐（[gridNameRow]）。真机未通过的现象是名字铺满格宽、
 *    居左，收缩时与封面不在一条中线上。
 *
 * 名字块高**取自真渲染**：[name] 是普通子件，测量出的高度进封面预算——不靠「行高 × 行数」的字体
 * 度量推算（推算偏小就会把名字行挤出可视区）。名字槽被组合两份：一份**量高**（同一份内容，只测量、
 * 不放置、清掉语义），一份**真正放置**（宽 = 封面宽）——Compose 硬约束「同一个 Measurable 一个测量
 * 回合只能测一次」，而封面宽得等名字块高出来才能算（槽的宽度只能由本帧给定）。量高那一份用格宽，
 * 因此要求名字槽的高度**与宽度无关**：[EntryNameText] 网格档 `minLines == maxLines == 2` ⇒ 恒两行。
 *
 * 两个槽的**占位由本帧给定**，槽内不必自己算尺寸：
 * - [cover] 的父级盒 = 封面盒（固定宽高），槽内用 `fillMaxSize` 填满即可（生产侧在槽内按同一份
 *   [CoverLayout.gridCellSize] 复算盒，两者恒等）；
 * - [name] 的父级盒宽 = 封面宽，槽内用 `fillMaxWidth` 填满（名字的左缘因此与封面左缘对齐）。
 *
 * @param cellMaxHeight 格子高度上限（票 #106：= 可视高度 − 上下留白，由 [com.cc3301.comicviewer.core.view.gridCellMaxHeight] 给出）
 * @param spacing 封面与名字行之间的间距（网格档的格子内间距，调用点传同一份常量）
 */
@Composable
internal fun GridCellFrame(
    cellMaxHeight: Dp,
    spacing: Dp,
    cover: @Composable () -> Unit,
    name: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = cellMaxHeight),
        content = {
            Box(Modifier.fillMaxSize()) { cover() }
            // 真正放置的那份名字（宽 = 封面宽，换行宽度因此 = 封面宽）
            Box(Modifier.fillMaxWidth()) { name() }
            // 量高副本（只测量、不放置；清掉语义，不进无障碍树）：份数与理由见 KDoc
            Box(Modifier.fillMaxWidth().clearAndSetSemantics {}) { name() }
        },
    ) { measurables, constraints ->
        val cellWidthPx = constraints.maxWidth
        val spacingPx = spacing.roundToPx()
        // 名字块高（真渲染、与宽度无关）：用格宽量那一份，算出封面宽后再按封面宽量真正放置的那份
        val nameProbe = measurables[2].measure(Constraints(maxWidth = cellWidthPx))
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
        // 封面盒（固定宽高）与名字行（固定宽 = 封面宽）：槽内 fillMaxSize / fillMaxWidth 因此恰好填满
        val coverPlaceable = measurables[0].measure(Constraints.fixed(coverWidthPx, coverHeightPx))
        val namePlaceable = measurables[1].measure(Constraints(minWidth = nameWidthPx, maxWidth = nameWidthPx))
        layout(width = cellWidthPx, height = coverHeightPx + spacingPx + namePlaceable.height) {
            coverPlaceable.place(nameLeftPx, 0)
            namePlaceable.place(nameLeftPx, coverHeightPx + spacingPx)
        }
    }
}
