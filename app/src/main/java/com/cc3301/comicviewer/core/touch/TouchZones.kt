package com.cc3301.comicviewer.core.touch

/**
 * 触摸区域类型 3（spec 硬约束，1.jpg 参考）：屏幕纵向三等分。
 * 纯几何判定 + 条漫滚动目标计算（票 05）；菜单/跨书行为票 06 接入。
 */
enum class TouchZone { LEFT, CENTER, RIGHT }

/** 横坐标 → 分区（竖向三等分；非法宽度防御返回 CENTER） */
fun touchZoneAt(x: Float, width: Float): TouchZone = when {
    width <= 0f -> TouchZone.CENTER
    x < width / 3f -> TouchZone.LEFT
    x < width * 2f / 3f -> TouchZone.CENTER
    else -> TouchZone.RIGHT
}

/**
 * 条漫模式左区目标：上一张图起始位置；已到首页即停（跨书跳转票 06）。
 * cur = 当前屏幕顶部可见页索引。
 */
fun webtoonPrevTarget(cur: Int, pageCount: Int): Int = (cur - 1).coerceAtLeast(0)

/**
 * 条漫模式右区目标：下一张图起始位置；已到末页即停（跨书跳转票 06）。
 */
fun webtoonNextTarget(cur: Int, pageCount: Int): Int = (cur + 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
