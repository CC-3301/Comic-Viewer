package com.cc3301.comicviewer.ui

import android.view.MotionEvent
import android.view.View
import com.cc3301.comicviewer.core.input.CapturedPointerAction
import com.cc3301.comicviewer.core.input.HistoryAction
import com.cc3301.comicviewer.core.input.PointerAutoHide
import com.cc3301.comicviewer.core.input.capturedPointerAction

/**
 * 指针自动隐藏在界面侧的执行（spec §指针自动隐藏）：判据说隐藏就申请指针捕获（[request]），
 * 说可见就释放（[release]）。捕获期间系统不绘制指针，鼠标移动、按键与侧键由
 * [View.OnCapturedPointerListener] 以相对坐标送到这里；动作映射在 `core/input/CapturedPointer`
 * （单测锁定），本类只落地：
 *
 * - **移动**：只消费，不改显隐。
 * - **左键 / 右键按下**：[release] 释放捕获，[onWakeClick] 唤醒判据并重开 3 秒计时。释放之后这一段
 *   剩下的移动与抬起走普通分发，界面按 [onWakeClick] 置起的本段标记吞到抬起为止：不翻页、
 *   不呼阅读菜单、不双击缩放、不拖动滚动，也不进双击历史；抬起后的下一次点击照常。
 * - **侧键**：后退 = 系统返回、前进 = 前进历史（[onHistory]）；前进真的前进了才消费这一段，
 *   没前进则不消费（与可见态同一口径）。
 *
 * 滚轮不经过捕获：捕获期间滚轮仍走 Activity 的普通分发，由阅读器的滚轮处理器自己滚（见 `ReaderScreen`）。
 *
 * 捕获由系统异步确认，申请当下读不到结果；没生效（被拒或已被撤销）时界面会重新收到普通分发的鼠标事件，
 * 那时按可见处理（判据同步为可见），不假装隐藏。窗口失焦（[onWindowFocusLost]）同样按可见处理；
 * 回焦后判据仍要求隐藏时再申请。判据本身仍只在 [PointerAutoHide]。
 *
 * @param onWakeClick 唤醒判据，并由界面置起「这一段被吞」的标记
 * @param onAutoHideChanged 判据被同步改过（捕获丢失）之后让界面重读一次
 * @param onHistory 侧键动作：后退走系统返回、前进走浏览历史；返回这段事件是否被消费，后退总视为已处理
 */
internal class ReaderPointerCapture(
    private val view: View,
    private val autoHide: PointerAutoHide,
    private val onWakeClick: () -> Unit,
    private val onAutoHideChanged: () -> Unit,
    private val onHistory: (HistoryAction) -> Boolean,
) {
    private val capturedListener = View.OnCapturedPointerListener { _, event ->
        val action = capturedPointerAction(
            action = event.actionMasked,
            actionButton = event.actionButton,
            buttons = event.buttonState,
        )
        when (action) {
            CapturedPointerAction.Ignore -> true
            CapturedPointerAction.WakeClick -> {
                release()
                onWakeClick()
                true
            }
            CapturedPointerAction.HistoryBack -> onHistory(HistoryAction.BACK)
            CapturedPointerAction.HistoryForward -> onHistory(HistoryAction.FORWARD)
        }
    }

    /** 进入阅读器：接上捕获期间的事件（捕获本身由 [request] 申请） */
    fun attach() {
        view.setOnCapturedPointerListener(capturedListener)
    }

    /**
     * 判据要求隐藏：窗口有焦点时申请指针捕获。
     * 捕获由系统异步确认，申请当下读不到结果；没生效时界面会重新收到普通分发的鼠标事件。
     */
    fun request() {
        if (view.hasWindowFocus()) view.requestPointerCapture()
    }

    /** 判据要求可见（含面板出现、双击放大、退出阅读器）：释放捕获 */
    fun release() {
        view.releasePointerCapture()
    }

    /**
     * 窗口失焦：系统会撤销捕获，指针按可见处理——判据同步为可见并重新计时，
     * 回焦后判据仍要求隐藏时再申请。
     */
    fun onWindowFocusLost() {
        release()
        if (!autoHide.hidden) return
        autoHide.onCaptureLost()
        onAutoHideChanged()
    }

    /** 退出阅读器：释放捕获并清掉捕获监听 */
    fun dispose() {
        release()
        view.setOnCapturedPointerListener(null)
    }
}
