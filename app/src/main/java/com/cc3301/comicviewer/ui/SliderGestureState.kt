package com.cc3301.comicviewer.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.comicviewer.core.view.ReaderMenuLayout

/**
 * 跳页滑动条的手势状态（票 #63）：滑块值、手势是否进行中、以及「手势结束要跳到哪一页」。
 * 无 UI、不是 `@Composable`：手势回调与组合之间的值收在这一处。
 *
 * 名字里的 Slider 是历史名（票 #103 从 `SeekBarGestureState` 改名：SeekBar 是 Android 遗留控件名）。
 * **第 6 轮起滑动条不再是 Material3 的 `Slider`**：`SeekSlider` 自绘轨道（2dp 线 + 8dp 圆球）并用**一个**
 * `pointerInput` 接管整行（按下 / 拖动 / 抬手），本类只负责「值 → 页」与幂等。
 * 因果与实测（为什么第 6 轮要删掉 Material3 那条通路：两条并行的按压换算里生效的是 M3 那条、
 * 自接那条根本没触发，真机现象是「只有个别位置有效」）写在 [SeekSlider] 与
 * [ReaderMenuLayout.SLIDER_BAND_HEIGHT_OTHER_VIEWPORT_DP] 的 KDoc 里——**本类不再重复叙述，也不自称「唯一成因说明」**。
 */
@Stable
internal class SliderGestureState(initialPage: Int, private val pageCount: Int) {

    /** 末页页位（0-based）：空书与单页书都是 0；跳页滑动条的值域上限用它 */
    val lastPage = ReaderMenuLayout.lastPage(pageCount)

    /** 滑块值（`Slider` 的 value） */
    var value by mutableFloatStateOf(ReaderMenuLayout.clampPage(initialPage, pageCount).toFloat())
        private set

    /** 本次手势开始时的值：用来判断「手势真的挪动了滑块」（没挪动就没必要跳页） */
    private var gestureStartValue = value

    /** 本次手势已经发出去的那一页：同一次手势里同一页不重发（幂等，见 [emitPage]） */
    private var lastEmittedPage: Int? = null

    /** 滑块手势进行中：值变化起、手势结束止（拖动与点按都走这一对） */
    var gestureActive by mutableStateOf(false)
        private set

    /** 滑块此刻对应的页（0-based） */
    val targetPage: Int
        get() = ReaderMenuLayout.seekTargetPage(value, pageCount)

    /**
     * 预览窗口该以哪一页为中心：恒等于 [targetPage]——**预览只看滑块目标页**。
     *
     * 手势进行中或跳页还没落地时，目标页就是滑块当前所在的那一页；跳页落地后两者相等。
     * 因此不需要「否则跟当前页」这条分支（原分支只在目标页 == 当前页时走到，那时它与 [targetPage] 同值、
     * 不可观测；票 #63）。
     */
    val previewTarget: Int get() = targetPage

    /** 滑块值变化：拖动中每一帧、以及点击轨道抬手前的那一次，都走这里 */
    fun onValueChange(newValue: Float) {
        if (!gestureActive) {
            // 新手势：记下起点值，并清掉上一次手势的「已发页」
            gestureStartValue = value
            lastEmittedPage = null
        }
        value = newValue
        gestureActive = true
    }

    /**
     * **点按滑动条行的某个位置**（0..1 的横向比例，票 #105 AC9）：把滑块挪到该位置对应的值并返回要跳到的页。
     *
     * 由 [SeekSlider] 的点按路径调用，只依赖按下位置；比例 → 值/页**没有第二份实现**——本方法读
     * [ReaderMenuLayout.sliderValueForFraction] 与 [ReaderMenuLayout.seekTargetPageForFraction]
     * （第 7 轮 standards P1 收口：此前本类自己写了一份 `fraction × lastPage`）。
     * 映射是线性的（不从 M3 的「扣掉拇指半宽」映射）：两者在端点与中点的页位一致，长书里最多差几页，
     * 而线性式在纯函数层可断言。
     */
    fun onTapFraction(fraction: Float): Int? {
        // 比例 → 值 / 页都读生产口径（第 7 轮 standards P1）：本类不再自己写一份换算，
        // 否则「被测的函数不是跑着的那条路」会重演（本票连挂三轮的同一失效模式）
        value = ReaderMenuLayout.sliderValueForFraction(fraction, pageCount)
        gestureActive = false
        return emitPage(ReaderMenuLayout.seekTargetPageForFraction(fraction, pageCount))
    }

    /**
     * 手势结束（松手 / 点击抬手）：返回要跳到的页——按**当次最新值**算，不是组合期的旧值。
     *
     * 两种情况返回 `null`（不跳页）：
     * ① **本次手势没挪动滑块**（值没变）：值本来就停在该页，再 `goTo` 一次是多余动作
     *    （点按那条路走 [onTapFraction]，不经过本方法）；
     * ② **该页本次手势已经发过**：重复 seek 是多余动作（`ReaderScreen` 每次都会 `scope.launch { host.goTo }`）。
     */
    fun onGestureFinished(): Int? {
        val moved = gestureActive && value != gestureStartValue
        gestureActive = false
        return if (moved) emitPage(targetPage) else null
    }

    /** 菜单外页面变了（音量键/滚轮翻页、跳页落地）让滑块跟上；手势进行中不回写，避免把滑块闪回旧页 */
    fun syncToPage(page: Int) {
        if (gestureActive) return
        val clamped = ReaderMenuLayout.clampPage(page, pageCount)
        value = clamped.toFloat()
        // 页面被菜单外改过：允许再发同一页（否则「跳到第 5 页 → 音量键到第 7 页 → 再点第 5 页」会被静默吞掉）
        if (clamped != lastEmittedPage) lastEmittedPage = null
    }

    /** 发出一次跳页目标；同一页在本次手势里已经发过就返回 null（幂等，见 [onGestureFinished] 与 [emitPage] 的调用点） */
    private fun emitPage(page: Int): Int? {
        if (page == lastEmittedPage) return null
        lastEmittedPage = page
        return page
    }
}
