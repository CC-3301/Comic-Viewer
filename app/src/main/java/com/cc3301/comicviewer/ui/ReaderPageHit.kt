package com.cc3301.comicviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * 指针位置落在**哪一页**（纯函数，由 [ReaderPageHitTest] 锁定）。双击放大、双指捏合、放大后平移
 * 都以它为准（spec 故事 33/34：作用于指针位置所在的那一页，手势期间锁定）。
 *
 * **为什么要「可见页」这一份输入**：[bounds] 里躺着的是每页最后一次布局时的**窗口矩形**，而离屏的页
 * 从来不会把旧值删掉（它还有别的用：离屏放大态的钳制要读页尺寸）。条漫滚到后面时，第 0 页的旧矩形仍在
 * 视口顶部——只看矩形的话「第一个命中的」就是它，于是双击把放大写给了一页早就不在屏上的页面，
 * 眼前那一页看上去**没有任何反应**（设备实测：13 次双击里 9 次目标页是第 0 页，而屏上实际是第 19~24 页）。
 *
 * 判定次序：
 * 1. 只考虑 [visible] 里的页（在屏的页）；[bounds] 里其余的页一律不看；
 * 2. 当前页在屏且其矩形含 [pos] ⇒ 当前页（与改动前一致：先试当前页）；
 * 3. 否则取在屏页里矩形含 [pos] 的那一页（多命中时取页号小的，结果确定）；
 * 4. 都不含（例如布局刚变、矩形还没重测）⇒ 回退当前页。
 *
 * 第 4 条是退化方向：宁可把放大写给「当前页」，也不写给一个早就不在屏上的页。
 */
internal fun readerPageIndexAt(
    pos: Offset,
    current: Int,
    visible: Set<Int>,
    bounds: Map<Int, Rect>,
    viewportLeft: Float,
    viewportTop: Float,
): Int {
    /** 该页的矩形是否含 [pos]：矩形是窗口坐标，先折成视口局部坐标（与手势坐标同一口径） */
    fun hit(index: Int): Boolean {
        val rect = bounds[index] ?: return false
        return pos.x >= rect.left - viewportLeft && pos.x <= rect.right - viewportLeft &&
            pos.y >= rect.top - viewportTop && pos.y <= rect.bottom - viewportTop
    }

    if (current in visible && hit(current)) return current
    return visible.sorted().firstOrNull { hit(it) } ?: current
}
