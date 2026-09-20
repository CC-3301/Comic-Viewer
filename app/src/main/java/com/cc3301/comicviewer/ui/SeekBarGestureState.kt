package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.view.ReaderMenuLayout

/**
 * 跳页滑动条的手势状态（票 #63）：滑块值、手势是否进行中、以及「手势结束要跳到哪一页」。
 * 无 UI、不是 `@Composable`，只把 `Slider` 的三个参数（value / onValueChange / onValueChangeFinished）
 * 与组合之间的值收在一处。
 *
 * **点击轨道为什么曾经没反应**（成因说明只有这一处，其它地方指向本段）：据 Material3 1.3.0 的调用序列推演
 * （`sliderTapModifier` 的 `onTap = { dispatchRawDelta(0f); gestureEndAction() }`，**未在真机复核**），
 * 点击轨道时 `onValueChange` → `onValueChangeFinished` 在同一次手势回调里被连续调用、两次之间不发生重新组合。
 * 跳页目标若取自组合期算出的值，点击时它还是点击前的页，`onSeek` 于是拿到旧页——页面不跳、预览不动；
 * 拖动跨多帧、中间有重新组合，所以拖动路径一直是好的。本类的值因此只由手势回调当场读写
 * （页位由 [ReaderMenuLayout.seekTargetPage] 按当次值换算）。
 */
@Stable
internal class SeekBarGestureState(initialPage: Int, private val pageCount: Int) {

    /** 末页页位（0-based）：空书与单页书都是 0；跳页滑动条的值域上限用它 */
    val lastPage = ReaderMenuLayout.lastPage(pageCount)

    /** 滑块值（`Slider` 的 value） */
    var value by mutableFloatStateOf(ReaderMenuLayout.clampPage(initialPage, pageCount).toFloat())
        private set

    /** 滑块手势进行中：值变化起、手势结束止——**点击轨道那一次回调也算**（它同样先给值再结束） */
    var gestureActive by mutableStateOf(false)
        private set

    /** 滑块此刻对应的页（0-based） */
    val targetPage: Int
        get() = ReaderMenuLayout.seekTargetPage(value, pageCount)

    /**
     * 预览窗口该以哪一页为中心：手势进行中、或**跳页还没落地**（目标页 ≠ 当前页）时跟滑块走，否则跟当前页。
     * 这样点击后预览不必等 `goTo` 落地就已居中于点击位置对应的页（票 #63）；跳页落地后两者相等、表现不变。
     */
    fun previewTarget(currentPage: Int): Int {
        val target = targetPage
        return if (gestureActive || target != currentPage) target
        else ReaderMenuLayout.clampPage(currentPage, pageCount)
    }

    /** 滑块值变化：拖动中每一帧、以及点击轨道抬手前的那一次，都走这里 */
    fun onValueChange(newValue: Float) {
        value = newValue
        gestureActive = true
    }

    /** 手势结束（松手 / 点击抬手）：返回要跳到的页——按**当次最新值**算，不是组合期的旧值 */
    fun onGestureFinished(): Int {
        gestureActive = false
        return targetPage
    }

    /** 菜单外页面变了（音量键/滚轮翻页、跳页落地）让滑块跟上；手势进行中不回写，避免把滑块闪回旧页 */
    fun syncToPage(page: Int) {
        if (!gestureActive) value = ReaderMenuLayout.clampPage(page, pageCount).toFloat()
    }
}
