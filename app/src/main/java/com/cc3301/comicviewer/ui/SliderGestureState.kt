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
 * 名字里的 Slider 是 Material3 的 `Slider`（票 #103 从 `SeekBarGestureState` 改名：SeekBar 是 Android
 * 遗留控件名，本处实际用的就是 Compose `Slider`）。
 *
 * **点击轨道为什么曾经没反应**（成因说明只有这一处，其它地方指向本段）：据 Material3 1.3.0 的调用序列推演
 * （`sliderTapModifier` 的 `onTap = { dispatchRawDelta(0f); gestureEndAction() }`，**未在真机复核**），
 * 点击轨道时 `onValueChange` → `onValueChangeFinished` 在同一次手势回调里被连续调用、两次之间不发生重新组合。
 * 跳页目标若取自组合期算出的值，点击时它还是点击前的页，`onSeek` 于是拿到旧页——页面不跳、预览不动；
 * 拖动跨多帧、中间有重新组合，所以拖动路径一直是好的。本类的值因此只由手势回调当场读写
 * （页位由 [ReaderMenuLayout.seekTargetPage] 按当次值换算）。
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

    /** 本次手势已经发出去的那一页：同一次点按里两条通路（Material3 + 自接手势）可能各发一次，同页不重发 */
    private var lastEmittedPage: Int? = null

    /** 滑块手势进行中：值变化起、手势结束止——**点击轨道那一次回调也算**（它同样先给值再结束） */
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
     * 为什么不交给 Material3 自己的点按换算（本类开头的推演 + 本轮实测）：它的 `onTap` 走
     * `dispatchRawDelta(0f)`，而 `rawOffset` 由 `onPress` 记下；按下与抬起之间只要发生一次重组，
     * 那条路径就会退回滑块**当前位置**（实测：五个不同位置全返回同一页），真机现象因此是
     * 「点哪儿都不动 / 只有个别位置有效」。本方法由 [SeekSlider] 自己的点按手势调用，
     * 只依赖按下位置，与重组时机无关；拖动路径仍归 Material3。
     * 与 Material3 那条通路的重叠由 [emitPage] 收口（同页不重发）。
     *
     * 比例→值的映射用线性比例（不从 M3 的「扣掉拇指半宽」映射）：两者在端点与中点的页位一致，
     * 长书里最多差几页，而线性式在纯函数层可断言。
     */
    fun onTapFraction(fraction: Float): Int? {
        value = (fraction.coerceIn(0f, 1f) * lastPage).coerceIn(0f, lastPage.toFloat())
        gestureActive = false
        return emitPage(targetPage)
    }

    /**
     * 手势结束（松手 / 点击抬手）：返回要跳到的页——按**当次最新值**算，不是组合期的旧值。
     *
     * 两种情况返回 `null`（不跳页）：
     * ① **本次手势没挪动滑块**（值没变）：Material3 自己的点按换算在按下与抬起之间发生重组时会退回当前位置，
     *    只报「没变的值」；那一次不该跳页（真跳页由 [onTapFraction] 那条自接手势负责）；
     * ② **该页本次手势已经发过**：同一次点按里两条通路都可能发一次，重复 seek 是多余动作
     *    （`ReaderScreen` 每次都会 `scope.launch { host.goTo }`）。
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

    /** 发出一次跳页目标；同一页在本次手势里已经发过就返回 null（幂等，见 [onGestureFinished]） */
    private fun emitPage(page: Int): Int? {
        if (page == lastEmittedPage) return null
        lastEmittedPage = page
        return page
    }
}
