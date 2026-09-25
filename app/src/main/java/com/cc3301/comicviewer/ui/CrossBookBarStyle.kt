package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 跨书确认条的文字配色口径（票 #100 r3，纯函数/常量，由 [CrossBookBarStyleTest] 锁定）。
 *
 * **两块文字、两种色**（r3 口径，维护者真机反馈「首末页的文案不要用橙色 和上/下一本按钮混色了」）：
 * - 中格**位置标签**（「第一页」/「最后一页」）用纯白 [CROSS_BOOK_LABEL_COLOR]——它只是提示当前位置，
 *   不能与可点的按钮同色；
 * - 两侧**动作格按钮**（「上一本书」/「下一本书」）用仓库强调橙 [CROSS_BOOK_ACTION_COLOR]
 *   （= [ACCENT_ORANGE]，SPEC 故事 27 的「橙色跳转按钮」不动）。
 *
 * 两个色**各有各的来源**、由 [CrossBookBarStyleTest] 分别断言并断言两者不相等：把其中一个改成另一个
 * 的色（例如把按钮也改白）会立刻变红。r2 曾把三格并成一个橙（当时口径是「位置格不该用白」），
 * r3 又拆回两个——合并与拆分都只由这一处决定，宿主 `CrossBookBar` 不再各写一份。
 *
 * 为什么这两个色在半透黑底上都可读：[crossBookBarLabelContrast] 给出 WCAG 对比度。
 * 条底是**半透**的（黑 `core/view/CrossBookBarLayout.BAR_ALPHA`），最坏情况是它压在**最亮的底图**（白页）上——
 * 那时条底最亮、与文字的对比度最低，因此判据取这一档。白字在这一档上远超 AA 的 4.5:1，
 * 橙按钮也在其之上（原来的 `Color.Gray` 则低于它，正是真机上的「看不清」）。
 */
internal val CROSS_BOOK_LABEL_COLOR: Color = Color.White

/**
 * 跨书条**按钮格**的文案色 = 仓库强调橙 [ACCENT_ORANGE]（SPEC 故事 27「橙色跳转按钮」）。
 *
 * 与中格位置标签 [CROSS_BOOK_LABEL_COLOR]（白）是两个来源：位置标签只是提示、按钮才是动作，
 * 两者同色会让「哪一格能点」失去视觉线索。
 */
internal val CROSS_BOOK_ACTION_COLOR: Color = ACCENT_ORANGE

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
