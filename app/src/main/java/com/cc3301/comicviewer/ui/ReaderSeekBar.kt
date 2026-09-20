package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.view.ReaderMenuLayout

/**
 * 跳页滑动条的手势状态（票 #63）：滑块值、拖动中标志、以及「手势结束要跳到哪一页」。
 *
 * 为什么要有这个持有者：Material3 的**点击轨道**路径在同一次手势回调里依次调用
 * `onValueChange` → `onValueChangeFinished`（`SliderState.dispatchRawDelta(0f)` 之后 `gestureEndAction()`），
 * 两次调用之间**不发生重新组合**。跳页目标若取自组合期算出的局部量，点击时它还是点击前的页，
 * `onSeek` 于是拿到旧页——点击看起来「没反应」；拖动跨多帧、中间有重新组合，所以拖动路径一直是好的。
 * 这里的 [targetPage] 与 [onGestureFinished] 都**当场读自己的 state**（[ReaderMenuLayout.seekTargetPage]
 * 由传入的当次值换算），因此点击与拖动走同一条口径，不再依赖组合时机。
 */
@Stable
internal class ReaderSeekBar(initialPage: Int, val pageCount: Int) {

    /** 末页页位（0-based）：空书与单页书都是 0；跳页滑动条的值域上限用它 */
    val lastPage = ReaderMenuLayout.lastPage(pageCount)

    /** 滑块值（`Slider` 的 value）；拖动/点击中预览跟随它换算出的页 */
    var value by mutableFloatStateOf(initialPage.coerceIn(0, lastPage).toFloat())
        private set

    /** 手势进行中：为真时预览跟随滑块，为假时预览回到当前页 */
    var dragging by mutableStateOf(false)
        private set

    /** 滑块此刻对应的页（0-based，夹在 0..[lastPage]） */
    val targetPage: Int
        get() = ReaderMenuLayout.seekTargetPage(value, pageCount)

    /** 滑块值变化：拖动中每一帧、以及点击轨道抬手前的那一次，都走这里 */
    fun onValueChange(newValue: Float) {
        value = newValue
        dragging = true
    }

    /** 手势结束（松手 / 点击抬手）：返回要跳到的页——按**当次最新值**算，不是组合期的旧值 */
    fun onGestureFinished(): Int {
        dragging = false
        return targetPage
    }

    /** 菜单外页面变了（音量键/滚轮翻页、跳页落地）让滑块跟上；拖动中不回写，避免把滑块闪回旧页 */
    fun syncToPage(page: Int) {
        if (!dragging) value = page.coerceIn(0, lastPage).toFloat()
    }
}
