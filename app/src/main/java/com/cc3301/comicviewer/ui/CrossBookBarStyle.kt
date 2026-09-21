package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 跨书确认条的文字配色口径（票 #100 r2，纯函数/常量，由 [CrossBookBarStyleTest] 锁定）。
 *
 * 三格文案**同一个色**：位置格（「第一页」/「最后一页」）与两侧动作格都用仓库既有强调橙
 * [ACCENT_ORANGE]——维护者真机反馈「第一页/最后一页的文字不应该用白色，配合透明背景看不清」，
 * 口径是「改用仓库既有强调色，与阅读器菜单的橙字一致」。
 *
 * 为什么这个色在半透黑底上可读、而原来的灰不行：[crossBookBarLabelContrast] 给出 WCAG 对比度。
 * 条底是**半透**的（黑 `core/view/CrossBookBarLayout.BAR_ALPHA`），最坏情况是它压在**最亮的底图**（白页）上——
 * 那时条底最亮、与文字的对比度最低，因此判据取这一档。原来的 `Color.Gray` 在这一档上低于 AA 的
 * 4.5:1（真机上的「看不清」），强调橙高于它。
 */
internal val CROSS_BOOK_LABEL_COLOR: Color = ACCENT_ORANGE

/**
 * 条上文字 [label] 在「黑 [barAlpha] 压在最亮底图 [pageColor] 上」时的 WCAG 对比度（相对亮度比）。
 *
 * **纯函数、不渲染**：条底是半透的，所以对比度不是「文字 vs 黑」而是「文字 vs 条底压过底图之后的色」。
 * 底图取最亮的一档（白页）即对比度的下界；底图越暗对比度越高（全黑底图上就是文字 vs 纯黑）。
 *
 * 混合按 sRGB 逐通道（与系统合成同口径），亮度按 WCAG 2.x 的 sRGB 线性化 + 0.2126/0.7152/0.0722 权重。
 *
 * @param label 条上文字的颜色
 * @param barAlpha 条面的黑底不透明度（`core/view/CrossBookBarLayout.BAR_ALPHA`）
 * @param pageColor 条底之下的底图色：取最亮的一档（`Color.White`）得到对比度下界
 * @return 对比度（≥ 1）；4.5 = WCAG AA 正文阈值
 */
internal fun crossBookBarLabelContrast(label: Color, barAlpha: Float, pageColor: Color): Double {
    // 条底 = 黑 × barAlpha 合成在底图之上（半透：底图透出来的那一档正是「不够黑」的来源）
    val bar = Color(
        red = pageColor.red * (1f - barAlpha),
        green = pageColor.green * (1f - barAlpha),
        blue = pageColor.blue * (1f - barAlpha),
        alpha = 1f,
    )
    val a = relativeLuminance(label)
    val b = relativeLuminance(bar)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** WCAG 2.x 相对亮度：sRGB 线性化后按 0.2126 / 0.7152 / 0.0722 加权 */
private fun relativeLuminance(color: Color): Double =
    0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

private fun linearize(channel: Float): Double {
    val c = channel.toDouble()
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}
