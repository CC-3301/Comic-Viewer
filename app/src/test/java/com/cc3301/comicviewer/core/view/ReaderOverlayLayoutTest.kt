package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读器贴底浮层的**底部 inset 口径**（票 #61 AC3）。
 *
 * 背景：票 #61 让阅读器路由进入沉浸（由 `AppNav` 按当前路由驱动，隐藏状态栏与导航栏、离开即恢复）。
 * 系统栏隐藏后 `WindowInsets.systemBars` 变成 0（不可见的栏种按定义报 0，见 `WindowInsetsCompat.getInsets`），
 * 于是 `系统栏 ∪ 挖孔` 这份贴底浮层 inset 在沉浸态只剩挖孔——竖屏无挖孔就是 0，
 * 阅读菜单面板的底部一行与跨书确认条的按钮会直接贴到屏幕下缘，落进手势导航「上滑回首页」的触发带。
 *
 * 本文件钉住两支口径（期望值一律写字面量，不在此重抄生产公式）：
 * - **系统栏占位**（底 inset > 0，含边缘上拨瞬态唤出的那几秒）⇒ 用真实 inset（= 系统栏底 ∪ 挖孔底），
 *   与改动前逐像素相同；底 inset 小于 24dp 的可见态**不得被抬高**（这是「可见支」的判别力所在）；
 * - **系统栏缺席**（底 inset == 0）⇒ `max(挖孔底, 24dp × density)`：挖孔照旧保留，两者都缺席时留 24dp。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：阅读器路由真的去 hide/show 系统栏、以及隐藏后系统是否只剩挖孔
 * inset —— 那是平台行为，仓库没有能观测它的 JVM 测试基建（Robolectric 的 `rootWindowInsets` 恒为全 0、
 * 探不到隐藏状态），由真机验收（AC5：手机手势导航 + 平板各一次、附前后对比截图）与 `ui/ReaderSystemBars.kt`
 * 的接线共同把守；**可见支**因此只能在这一层用纯函数钉（真机那一侧依赖平台的真实 inset）；
 * 横屏挖孔在左/右时不被切属本口径之外的横向 inset，本票未改。
 */
class ReaderOverlayLayoutTest {

    @Test
    fun `系统栏可见时底部 inset 就是系统栏底 与改动前逐像素相同`() {
        // 三按钮导航的导航栏底 inset：48dp @3x = 144px
        assertEquals(144, ReaderOverlayLayout.overlayBottomPx(144, 0, 3f))
    }

    @Test
    fun `系统栏可见但底 inset 小于最小留白时也不被抬高`() {
        // 12dp @1x：手势导航下可见导航栏底 inset 可能远小于 24dp——可见支必须用真实值，
        // 不得被 24dp 的地板顶上去（顶上去就比改动前高，SPEC 的「逐像素相同」不再成立）
        assertEquals(12, ReaderOverlayLayout.overlayBottomPx(12, 0, 1f))
    }

    @Test
    fun `系统栏可见时挖孔底更大则取挖孔底 与改动前的并集口径一致`() {
        // 可见栏 12px + 挖孔底 102px（34dp @3x）：仍取两者中较大的那个
        assertEquals(102, ReaderOverlayLayout.overlayBottomPx(12, 102, 3f))
    }

    @Test
    fun `系统栏隐藏后底部留白不塌成 0 且恰为最小留白`() {
        // 沉浸态（栏隐藏、无挖孔）：24dp @3x = 72px
        assertEquals(72, ReaderOverlayLayout.overlayBottomPx(0, 0, 3f))
    }

    @Test
    fun `挖孔的底部 inset 在栏隐藏后照旧保留`() {
        // 挖孔是物理特征，不受系统栏可见性影响；比最小留白大时以挖孔为准：34dp @3x = 102px
        assertEquals(102, ReaderOverlayLayout.overlayBottomPx(0, 102, 3f))
    }

    @Test
    fun `挖孔底小于最小留白时取最小留白`() {
        assertEquals(72, ReaderOverlayLayout.overlayBottomPx(0, 10, 3f))
    }

    @Test
    fun `按密度换算成像素 平板 1x 与 2x`() {
        assertEquals(24, ReaderOverlayLayout.overlayBottomPx(0, 0, 1f))
        assertEquals(48, ReaderOverlayLayout.overlayBottomPx(0, 0, 2f))
    }

    @Test
    fun `分数密度下按就近取整换算 与生产同一口径`() {
        // tvdpi（213dpi）密度 1.33125：24dp = 31.95px，就近取整 = 32px（截断会得 31，故这条钉住取整口径）
        assertEquals(32, ReaderOverlayLayout.overlayBottomPx(0, 0, 1.33125f))
    }
}
