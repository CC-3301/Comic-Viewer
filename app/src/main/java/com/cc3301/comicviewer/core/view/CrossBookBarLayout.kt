package com.cc3301.comicviewer.core.view

import com.cc3301.comicviewer.core.touch.TouchZone
import com.cc3301.comicviewer.core.touch.touchZoneAt

/**
 * 跨书确认条的版式口径（票 #100，纯函数，由 [CrossBookBarLayoutTest] 锁定）。
 *
 * ## 版式（票面方案 + 批次 6 口径）
 * 贴屏幕左右边与底边的**整宽条**（无圆角、无胶囊、无边框），底色 = **黑 60%**（[BAR_ALPHA]，批次 6 拍板
 * 的四档里的 D5-B3），高 [BAR_HEIGHT_DP]，文字 [LABEL_SP]，内容三列等宽：左格 / 中格 / 右格。
 * 中格恒是位置提示（「第一页」/「最后一页」，居中）；
 * **按钮格由方向决定**——首页方向（确认换上一本）在左格、末页方向（换下一本）在右格
 * （[actionZone]）。这样「从触摸区直直往下移动」时，按钮就在刚才点的那个触发区正下方。
 *
 * ## 条面高与文案的垂直位置（批次 6）
 * 条面（有底色的那一块）恒贴屏幕底边，因此它比内容带 [BAR_HEIGHT_DP] 多出**底部避让**那一截
 * （沉浸态下是「挖孔底 ∪ [MIN_BOTTOM_DP]」，见 `ReaderOverlayLayout`）：条面高 = [bandHeightDp]。
 * 文案在**整块条面**里垂直居中（[labelCenterFromBottomDp]，上下留白一致），不是居中在内容带里
 * ——后者会让文字看上去偏上、下方空一大截（维护者真机反馈）。避让与居中共存：条面 88dp 时文案中心在
 * 距底 44dp 处，文案下缘仍 ≥ [MIN_BOTTOM_DP]，不落进手势导航的上滑带。
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

    /** 条高（dp）：内容带与动作格命中区的高都是它（整宽三等分格 × 本高 = 命中区） */
    const val BAR_HEIGHT_DP: Float = 64f

    /**
     * 条面（黑底）的不透明度：**黑 60%**（批次 6 拍板的 D5-B3）。
     * 画面从底下透出来一档、被压暗——不是纯黑（1f）、不是全透明（0f）、也不是毛玻璃。
     */
    const val BAR_ALPHA: Float = 0.6f

    /** 条内文案字号（sp）：位置格与动作格同一档 */
    const val LABEL_SP: Float = 20f

    /**
     * 条面（有底色的那一块）总高（dp）= 内容带 [BAR_HEIGHT_DP] + 底部避让 [bottomInsetDp]。
     * 条面恒贴屏幕底边，因此底部避让那一截也在条面之内（黑底铺到屏幕底边，见宿主 `CrossBookBar`）。
     *
     * @param bottomInsetDp 底部避让（dp）：沉浸态下是「挖孔底 ∪ 24dp」，由 `ReaderOverlayLayout` 算（px → dp 由调用方换算）
     */
    fun bandHeightDp(bottomInsetDp: Float): Float = BAR_HEIGHT_DP + bottomInsetDp

    /**
     * 文案在条面内的垂直中心距条面底边的距离（dp）——批次 6 的「上下留白一致」判据：
     * 中心落在条面正中，则到条面顶与到条面底的距离都是它，文字上下留白各 = (条面高 − 文案高) / 2。
     *
     * 与「居中在 [BAR_HEIGHT_DP] 内容带里」（= [BAR_HEIGHT_DP] / 2 = 32dp）差半个底部避让：
     * 后者正是维护者看到的「偏上、下方空隙大」，两者在 88dp 条面上差 12dp、已能一眼看出。
     */
    fun labelCenterFromBottomDp(bottomInsetDp: Float): Float = bandHeightDp(bottomInsetDp) / 2f

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
