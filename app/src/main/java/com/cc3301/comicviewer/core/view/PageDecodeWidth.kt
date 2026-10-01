package com.cc3301.comicviewer.core.view

/**
 * 页面解码的目标宽度（px，票 #108 E1-A/E2-B，由 [PageDecodeWidthTest] 锁定）：**唯一出处**。
 *
 * 读同一页有两条路，两条都必须落到**同一个**整数，否则 `PageDecoder.memoryKey` 那把键（宽度写进键里）
 * 差 1px 就不命中，前置解好的首帧白解、进阅读页仍要重解（首帧退回「先黑一帧再出图」，AC2 当场落空）：
 * - 浏览页的前置：`LocalView.current.width`（整窗宽，Int 像素）；
 * - 阅读页的页容器：`BoxWithConstraints.maxWidth.toPx()`（Float 像素）。
 *
 * 所以两处都调本函数，不各自 `toInt()`：取整口径（截断）与非正数兜底（布局首帧可能给 0 宽）只有这里一处。
 * JVM 侧只能断言这个映射本身（见 `PageDecodeWidthTest`）；「两条路在真机上是否拿到同一个宽度」按 SPEC 的
 * Testing Decisions 走真机验收。
 */
fun pageDecodeWidthPx(viewportWidthPx: Float): Int = viewportWidthPx.toInt().coerceAtLeast(1)
