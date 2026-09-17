package com.cc3301.comicviewer.core.input

import com.cc3301.comicviewer.core.touch.TapIntent
import com.cc3301.comicviewer.core.touch.tapIntentAt

/**
 * 鼠标输入映射（票 17，spec 故事 22/35/36/37）：把平台事件（滚轮、左右键、侧键）归一成纯函数结果，
 * 让滚轮三场景、左右键等价、侧键前进/后退这些规则能用 JVM 单测锁住，而不是散在 Compose 回调里。
 *
 * 键位常量自行定义（值与 android.view.MotionEvent.BUTTON_* 一致），与 core/reader 的音量键常量同一手法：
 * 单测不加载框架类。
 */

/** 鼠标键位（值与 MotionEvent.BUTTON_* 一致）：左键 / 右键 / 后退侧键 / 前进侧键 */
const val MOUSE_BUTTON_PRIMARY = 1
const val MOUSE_BUTTON_SECONDARY = 2
const val MOUSE_BUTTON_BACK = 8
const val MOUSE_BUTTON_FORWARD = 16

/** 滚轮所处界面（spec 故事 22/35）：列表与条漫都是连续滚动容器，单页是分页容器 */
enum class WheelSurface { LIST, WEBTOON, PAGED }

/** 滚轮一格的动作 */
enum class WheelAction {
    /** 交给容器自身滚动（列表 / 条漫） */
    SCROLL_SELF,
    PREV_PAGE,
    NEXT_PAGE,

    /** 无纵向分量（纯横向滚轮）：不处理 */
    IGNORE,
}

/**
 * 滚轮 → 动作（spec 故事 22 列表 / 35 阅读器）：只有单页模式拦截为翻页（一格=一页），
 * 列表与条漫一律交给容器自身滚动。
 *
 * [verticalDelta] 取 AXIS_VSCROLL：正值 = 向下滚（内容上移），单页模式下即下一页；
 * 幅度不参与判定（一格即一次事件），方向由符号决定。
 */
fun wheelAction(surface: WheelSurface, verticalDelta: Float): WheelAction = when {
    verticalDelta == 0f -> WheelAction.IGNORE
    surface != WheelSurface.PAGED -> WheelAction.SCROLL_SELF
    verticalDelta > 0f -> WheelAction.NEXT_PAGE
    else -> WheelAction.PREV_PAGE
}

/**
 * 前台界面的滚轮接入（票 17）：[surface] 声明该界面的滚轮语义，[onWheelPageTurn] 只在单页模式下被调用
 * （true = 下一页），返回 true = 已消费。滚轮在 [WheelSurface.LIST]/[WheelSurface.WEBTOON] 下不拦截。
 */
class WheelHandler(val surface: WheelSurface, val onWheelPageTurn: (Boolean) -> Boolean)

/**
 * 鼠标键 + 点击横坐标 → 触摸区域意图（spec 故事 36）：左键与右键完全等价，其余键位不触发（返回 null）。
 * 分区判定复用 [tapIntentAt]，与触摸输入是同一份契约（左区上一页 / 中区菜单 / 右区下一页）。
 */
fun mouseTapIntent(button: Int, x: Float, width: Float): TapIntent? = when (button) {
    MOUSE_BUTTON_PRIMARY, MOUSE_BUTTON_SECONDARY -> tapIntentAt(x, width)
    else -> null
}

/** 浏览历史动作（spec 故事 37）：后退侧键 = 后退、前进侧键 = 前进 */
enum class HistoryAction { BACK, FORWARD }

/**
 * 侧键 → 浏览历史动作；非侧键返回 null。
 * 前进只作用于浏览历史（历史里只有浏览位置），因此前进不会把用户带回阅读器（spec Out of Scope）。
 */
fun sideButtonAction(button: Int): HistoryAction? = when (button) {
    MOUSE_BUTTON_BACK -> HistoryAction.BACK
    MOUSE_BUTTON_FORWARD -> HistoryAction.FORWARD
    else -> null
}
