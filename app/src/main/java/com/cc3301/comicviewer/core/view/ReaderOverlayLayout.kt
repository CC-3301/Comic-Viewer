package com.cc3301.comicviewer.core.view

import kotlin.math.roundToInt

/**
 * 阅读器贴底浮层的底部 inset 口径（票 #61，纯函数，由 [ReaderOverlayLayoutTest] 锁定）。
 *
 * 票 #61 让阅读器路由进入沉浸：由 `AppNav` 按**当前路由**驱动——路由在场即隐藏状态栏与导航栏、离开即恢复
 * （接线 `ui/ReaderSystemBars.kt`，调用点 `ui/AppNav.kt`）。系统栏隐藏后贴底浮层（阅读菜单面板、跨书确认条）
 * 原来那份 `系统栏 ∪ 挖孔` inset 会塌成 0（`WindowInsetsCompat.getInsets` 对不可见的栏种返回 0），
 * 面板底部那行与确认条按钮就贴到屏幕下缘，落进手势导航「上滑回首页」的触发带里。因此底部要有明确兜底。
 *
 * 口径按**系统栏是否占位**分两支（菜单与确认条共用 `ui/ReaderMenu.kt` 的 `readerOverlayInsets` 一处）：
 * - **系统栏底 inset > 0**（栏可见）：用真实 inset（= 系统栏底 ∪ 挖孔底），与改动前逐像素相同——
 *   列表界面照旧由 Scaffold 自己消费、不双重内缩。边缘上拨**瞬态唤出**的那几秒是否也走这一支，
 *   取决于平台在瞬态显示期间是否把 `systemBars` 底报为非 0——**本机未验证（真机观察项）**：
 *   报非 0 则浮层自动抬到真实栏高、胶囊压不住菜单最底一行；仍报 0 则浮层停在下支的 24dp 兜底上（即旧问题未消）；
 * - **系统栏底 inset == 0**（栏隐藏，沉浸态）：`max(挖孔底, [MIN_BOTTOM_DP] × density)`——挖孔是物理特征、
 *   不受栏可见性影响，照旧保留；两者都缺席时（竖屏无挖孔的沉浸态）仍有最小留白。
 *
 * **横向（左/右）与顶部不在这里**：横屏挖孔在左/右时照旧由 `系统栏 ∪ 挖孔` 的原始 inset 承担，本票不改。
 */
internal object ReaderOverlayLayout {

    /**
     * 沉浸态（系统栏底 inset 为 0）下的最小底部留白（dp）。
     *
     * 取 24dp 的依据：手势导航的上滑带回首页触发带贴着屏幕下缘（真机上约 16–24dp），
     * 留白盖住这条带的上限即可——比它小会把菜单底部一行吞进触发带，比它大则在沉浸画面里凭空多出一条无用的底边。
     * 它**只在栏隐藏那一支生效**（[overlayBottomPx]），因此对「栏可见」的既有布局逐像素无影响；
     * 边缘上拨瞬态唤出的系统栏/胶囊（约 48dp）理应走「真实 inset」那一支而不靠这个常量兜，但那一支是否真的生效
     * 取决于平台在瞬态显示期间是否把 `systemBars` 底报为非 0——**本机未验证（真机观察项）**：
     * 报 0 时浮层就停在本常量兜住的 24dp 上，压不住胶囊这一条随之不成立。
     */
    const val MIN_BOTTOM_DP: Float = 24f

    /**
     * 贴底浮层的底部 inset（px）：系统栏占位时用真实 inset，栏隐藏时才用「挖孔底 ∪ [MIN_BOTTOM_DP]」兜底。
     *
     * @param systemBarsBottomPx `WindowInsets.systemBars` 的底部 inset（px）；栏隐藏时为 0；
     *   边缘上拨瞬态唤出时是否报非 0 属**平台行为**（本机未验证，真机观察项）
     * @param cutoutBottomPx `WindowInsets.displayCutout` 的底部 inset（px）；无挖孔时为 0
     * @param density 当前显示密度（`Density.density`），把 [MIN_BOTTOM_DP] 换算成像素
     */
    fun overlayBottomPx(systemBarsBottomPx: Int, cutoutBottomPx: Int, density: Float): Int =
        if (systemBarsBottomPx > 0) {
            maxOf(systemBarsBottomPx, cutoutBottomPx)
        } else {
            maxOf(cutoutBottomPx, (MIN_BOTTOM_DP * density).roundToInt())
        }
}
