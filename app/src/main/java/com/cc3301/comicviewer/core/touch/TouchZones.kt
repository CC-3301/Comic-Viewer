package com.cc3301.comicviewer.core.touch

/**
 * 触摸区域类型 3（spec 硬约束，1.jpg 参考）：屏幕纵向三等分。
 * 纯几何判定 + 区域意图映射 + 条漫页位与条漫/单页翻页目标计算（票 05/07/#87）；菜单与跨书行为在 ReaderScreen。
 */
enum class TouchZone { LEFT, CENTER, RIGHT }

/** 触摸区域意图（票 07）：两模式、两方向统一——左区恒上一页、中区菜单、右区下一页 */
enum class TapIntent { PREV_PAGE, MENU, NEXT_PAGE }

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

/**
 * 条漫模式当前页位（票 #87）：默认取顶部可见页索引；但**已滚到书末且内容超过一屏**时报最后一页。
 *
 * 背景（维护者真机验收原文：「读到最后一页时，页面预览仍高亮在倒数第二页」）：末页矮于视口时，
 * LazyColumn 滚到底会把末页顶到屏幕底部、「顶部可见页」停在倒数第二页，于是页位永远走不到末页——
 * 菜单预览高亮/页码、进度写入（读到末页才算读完）全都差一页。
 * 前后都不可滚（整本不满一屏）时不算书末：那是「从头看起」而不是「读到末页」，照旧报顶部可见页。
 *
 * @param firstVisibleIndex LazyListState.firstVisibleItemIndex
 * @param canScrollForward  LazyListState.canScrollForward
 * @param canScrollBackward LazyListState.canScrollBackward
 */
fun webtoonCurrentPage(
    firstVisibleIndex: Int,
    pageCount: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
): Int {
    val last = (pageCount - 1).coerceAtLeast(0)
    val first = firstVisibleIndex.coerceIn(0, last)
    val atBookEnd = !canScrollForward && canScrollBackward
    return if (atBookEnd) last else first
}

/**
 * 触摸区域 → 意图（票 07，spec 故事 26）：条漫与单页、LTR 与 RTL 下语义完全一致。
 * 签名不含模式/方向参数即为契约：点击区不受阅读方向影响。
 */
fun tapIntentAt(x: Float, width: Float): TapIntent = when (touchZoneAt(x, width)) {
    TouchZone.LEFT -> TapIntent.PREV_PAGE
    TouchZone.CENTER -> TapIntent.MENU
    TouchZone.RIGHT -> TapIntent.NEXT_PAGE
}

/** 单页模式左区目标：上一页；已在书首返回 null（交由跨书两段式确认） */
fun pagedPrevTarget(cur: Int, pageCount: Int): Int? =
    if (pageCount <= 0 || cur <= 0) null else cur - 1

/** 单页模式右区目标：下一页；已在书末返回 null（交由跨书两段式确认） */
fun pagedNextTarget(cur: Int, pageCount: Int): Int? =
    if (pageCount <= 0 || cur >= pageCount - 1) null else cur + 1
