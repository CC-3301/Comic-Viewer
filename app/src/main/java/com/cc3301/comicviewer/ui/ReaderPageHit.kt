package com.cc3301.comicviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * 指针位置落在**哪一页**（纯函数，由 [ReaderPageHitTest] 锁定）。双击放大、双指缩放、放大后平移
 * 都以它为准（spec 故事 33/34：作用于指针位置所在的那一页，手势期间锁定）。
 *
 * **为什么要「在屏页」这一份输入**：[bounds] 里躺着的是每页最后一次布局时的**窗口矩形**，而离屏的页
 * 从来不会把旧值删掉（它还有别的用：离屏放大态的钳制要读页尺寸）。条漫滚到后面时，第 0 页的旧矩形仍在
 * 视口顶部——只看矩形的话「第一个命中的」就是它，于是双击把放大写给了一页早就不在屏上的页面，
 * 眼前那一页看上去**没有任何反应**（设备实测：13 次双击里 9 次目标页是第 0 页，而屏上实际是第 19~24 页）。
 *
 * 判定次序：
 * 1. 只考虑 [visible] 里的页（在屏的页，来源 `PageHost.visiblePages()`）；[bounds] 里其余的页一律不看；
 * 2. 当前页在屏且其矩形含 [pos] ⇒ 当前页；
 * 3. 否则取在屏页里矩形含 [pos] 的那一页（多命中时取页号小的，结果确定）；
 * 4. 都不含（例如布局刚变、矩形还没重测）⇒ 取**离指针最近**的那个在屏页（距离相同再比页号）。
 *
 * 第 4 条的退化方向是「眼睛看着的那一页，而不是页号最小的那一页」：条漫里当前页是**顶部**可见页，
 * 直接回退当前页会把「点靠下那页」的放大写到顶部那页上。
 */
internal fun readerPageIndexAt(
    pos: Offset,
    current: Int,
    visible: Set<Int>,
    bounds: Map<Int, Rect>,
    viewportOrigin: Offset,
): Int {
    /** 该页矩形在视口局部坐标下的样子（矩形是窗口坐标，手势坐标是视口局部坐标） */
    fun local(index: Int): Rect? = bounds[index]?.translate(-viewportOrigin.x, -viewportOrigin.y)

    fun hit(index: Int): Boolean {
        val rect = local(index) ?: return false
        return pos.x >= rect.left && pos.x <= rect.right && pos.y >= rect.top && pos.y <= rect.bottom
    }

    if (current in visible && hit(current)) return current
    visible.sorted().firstOrNull { hit(it) }?.let { return it }
    return visible
        .filter { bounds[it] != null }
        .minWithOrNull(compareBy({ distanceSquaredTo(local(it)!!, pos) }, { it }))
        ?: current
}

/** 点到矩形的距离平方：点在矩形内为 0，否则到最近边的距离（只用来比大小，不开方） */
private fun distanceSquaredTo(rect: Rect, pos: Offset): Float {
    val dx = maxOf(rect.left - pos.x, 0f, pos.x - rect.right)
    val dy = maxOf(rect.top - pos.y, 0f, pos.y - rect.bottom)
    return dx * dx + dy * dy
}
