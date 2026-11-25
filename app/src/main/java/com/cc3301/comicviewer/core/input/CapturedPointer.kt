package com.cc3301.comicviewer.core.input

/**
 * 指针捕获期间的鼠标事件映射（spec §指针自动隐藏）：捕获后事件由 `View.OnCapturedPointerListener`
 * 以相对坐标送进来，不再是 Compose 的指针事件。这里把平台的 action 与键位归一成界面要做的动作，
 * 让「移动只消费、左/右键唤醒并吞掉整段、侧键回退/前进」能用 JVM 单测锁住，而不是散在监听回调里。
 * 显隐判据仍在 [PointerAutoHide]。
 *
 * 滚轮不在映射里：捕获期间滚轮仍走 Activity 的普通分发（进不到捕获监听），由阅读器的滚轮处理器处理。
 *
 * action 常量自行定义（值与 `android.view.MotionEvent.ACTION_*` 一致），与 [MOUSE_BUTTON_*] 同一手法：
 * 单测不加载框架类。
 */
const val CAPTURED_ACTION_DOWN = 0
const val CAPTURED_ACTION_MOVE = 2
const val CAPTURED_ACTION_BUTTON_PRESS = 11

/** 捕获期间一帧鼠标事件要做的事 */
sealed interface CapturedPointerAction {
    /** 移动类：只消费，不改显隐状态 */
    data object Ignore : CapturedPointerAction

    /** 左键或右键按下：释放捕获、唤醒并重开 3 秒计时，这一次按下整段被消费 */
    data object WakeClick : CapturedPointerAction

    /** 后退侧键：系统返回 */
    data object HistoryBack : CapturedPointerAction

    /** 前进侧键：前进历史 */
    data object HistoryForward : CapturedPointerAction
}

/**
 * 捕获期间一帧鼠标事件 → 动作。
 *
 * 主键按下在捕获里以 [CAPTURED_ACTION_DOWN] 到达，其余键位以 [CAPTURED_ACTION_BUTTON_PRESS] 到达，
 * 两者都算唤醒（左键与右键同一口径）；移动类（[CAPTURED_ACTION_MOVE]）只消费、不改变显隐。
 *
 * @param action `MotionEvent.actionMasked`
 * @param actionButton `MotionEvent.actionButton`（只有按键事件有意义）
 * @param buttons `MotionEvent.buttonState`：主键的按下事件靠它认出
 */
fun capturedPointerAction(
    action: Int,
    actionButton: Int,
    buttons: Int,
): CapturedPointerAction = when (action) {
    CAPTURED_ACTION_BUTTON_PRESS -> when (actionButton) {
        MOUSE_BUTTON_PRIMARY, MOUSE_BUTTON_SECONDARY -> CapturedPointerAction.WakeClick
        MOUSE_BUTTON_BACK -> CapturedPointerAction.HistoryBack
        MOUSE_BUTTON_FORWARD -> CapturedPointerAction.HistoryForward
        else -> CapturedPointerAction.Ignore
    }
    CAPTURED_ACTION_DOWN ->
        if (buttons and (MOUSE_BUTTON_PRIMARY or MOUSE_BUTTON_SECONDARY) != 0) {
            CapturedPointerAction.WakeClick
        } else {
            CapturedPointerAction.Ignore
        }
    CAPTURED_ACTION_MOVE -> CapturedPointerAction.Ignore
    else -> CapturedPointerAction.Ignore
}
