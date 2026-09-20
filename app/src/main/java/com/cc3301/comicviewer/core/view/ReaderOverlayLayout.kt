package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 阅读器贴底浮层的底部 inset 兜底口径（票 #61，纯函数，由 [ReaderOverlayLayoutTest] 锁定）。
 *
 * 票 #61 让阅读器路由进入沉浸：组合期内隐藏状态栏与导航栏、离开组合即恢复（接线见 `ui/ReaderSystemBars.kt`）。
 * 系统栏隐藏后贴底浮层（阅读菜单面板、跨书确认条）原来那份 `系统栏 ∪ 挖孔` inset 会塌成 0
 * （`WindowInsetsCompat.getInsets` 对不可见的栏种返回 0），面板底部那行与确认条按钮就贴到屏幕下缘，
 * 落进手势导航「上滑回首页」的触发带里。因此底部要有明确兜底：**系统栏底 ∪ 挖孔底 ∪ 最小留白**。
 *
 * 三条口径合在这里一处（菜单与确认条共用 `ui/ReaderMenu.kt` 的 `readerOverlayInsets`），取最大所以：
 * - 系统栏可见时结果 = 系统栏底 inset（与改动前逐像素相同，列表界面照旧由 Scaffold 自己消费、不双重内缩）；
 * - 沉浸态下挖孔底的 inset 照旧保留（挖孔是物理特征，不受栏可见性影响）；
 * - 两者都缺席时（竖屏无挖孔的沉浸态）仍有 [MIN_BOTTOM_DP] 的最小留白。
 *
 * **横向（左/右）与顶部不在这里**：横屏挖孔在左/右时照旧由 `系统栏 ∪ 挖孔` 的原始 inset 承担，本票不改。
 */
internal object ReaderOverlayLayout {

    /**
     * 沉浸态下的最小底部留白（dp）。
     *
     * 取 24dp 的依据：手势导航的上滑带回首页触发带贴着屏幕下缘（真机上约 16–24dp），
     * 留白盖住这条带的上限即可——比它小会把菜单底部一行吞进触发带，比它大则在沉浸画面里凭空多出一条无用的底边。
     * 三按钮导航可见时导航栏底 inset（48dp）比它大，因此本常量对「栏可见」的既有布局不产生任何影响。
     */
    const val MIN_BOTTOM_DP: Float = 24f

    /**
     * 贴底浮层的底部 inset（px）= max(系统栏底 inset, 挖孔底 inset, [MIN_BOTTOM_DP] × density)。
     *
     * @param systemBarsBottomPx `WindowInsets.systemBars` 的底部 inset（px）；系统栏隐藏时为 0
     * @param cutoutBottomPx `WindowInsets.displayCutout` 的底部 inset（px）；无挖孔时为 0
     * @param density 当前显示密度（`Density.density`），把 dp 口径换算成 inset 需要的像素
     */
    fun overlayBottomPx(systemBarsBottomPx: Int, cutoutBottomPx: Int, density: Float): Int =
        maxOf(systemBarsBottomPx, cutoutBottomPx, (MIN_BOTTOM_DP * density).roundToInt())
}
