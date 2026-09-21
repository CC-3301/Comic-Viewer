package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.touch.TouchZone
import com.cc3301.comicviewer.core.touch.touchZoneAt

/**
 * 跨书确认条的版式口径（票 #100，纯函数，由 [CrossBookBarLayoutTest] 锁定）。
 *
 * ## 版式（票面方案）
 * 贴屏幕左右边与底边的**整宽纯黑条**（无圆角、无胶囊、无边框），高 [BAR_HEIGHT_DP]，文字 [LABEL_SP]，
 * 内容三列等宽：左格 / 中格 / 右格。中格恒是位置提示（「第一页」/「最后一页」，居中）；
 * **按钮格由方向决定**——首页方向（确认换上一本）在左格、末页方向（换下一本）在右格
 * （[actionZone]）。这样「从触摸区直直往下移动」时，按钮就在刚才点的那个触发区正下方。
 *
 * ## 命中判定为什么复用触摸区
 * 三列划分与触摸区的三等分**是同一个函数**（[touchZoneAt]）：命中判定 [confirmsAt] 直接拿它算格位，
 * 条内点击因此与阅读页的触摸分区逐像素同源——不是在两处各写一遍「除以三」（那种写法随着
 * 内边距/inset 的改动就会漂移，正是维护者报的「按钮不在触发区正下方」的根因）。
 * 列的**视觉**落位由宿主 `CrossBookBar` 用三等份权重铺满条宽，其与触摸区的重合由
 * `ui/CrossBookBarTest` 实测（真实点击的横坐标边界）锁定。
 *
 * ## 只有动作格响应
 * 中格（提示）与反向那一侧的空白格**都不响应**：首页方向点右格不跳下一本、点中格不关条
 * （[confirmsAt] 对它们返回 false）。跨书条的两段式语义因此更明确：首点触摸区弹条 →
 * 只有动作格真换书；关条只由条外的点击负责。
 */
internal object CrossBookBarLayout {

    /** 条高（dp）：动作格命中区的高就是它（整宽三等分格 × 本高 = 命中区） */
    const val BAR_HEIGHT_DP: Float = 64f

    /** 条内文案字号（sp）：位置格与动作格同一档 */
    const val LABEL_SP: Float = 20f

    /**
     * 动作格（按方向 [forward] 选）：forward = 末页方向 → 右格「下一本书」；否则首页方向 → 左格「上一本书」。
     * 返回的是**与触摸区同一枚举**的格位：调用方据此把橙色文案摆到对应格。
     */
    fun actionZone(forward: Boolean): TouchZone = if (forward) TouchZone.RIGHT else TouchZone.LEFT

    /**
     * 条内点击的横坐标 [x]（视口宽 [width]）是否落在动作格上：true = 真换书。
     * 中格与反向空白格一律 false；这些格不该有任何动作（既不换书、也不关条——关条见宿主里条外那层点击）。
     */
    fun confirmsAt(x: Float, width: Float, forward: Boolean): Boolean =
        touchZoneAt(x, width) == actionZone(forward)
}
