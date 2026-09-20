package com.cc3301.comicviewer.core.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器贴底浮层的**底部 inset 兜底口径**（票 #61 AC3）。
 *
 * 背景：票 #61 让阅读器路由进入沉浸（隐藏状态栏与导航栏），离开即恢复。系统栏隐藏后
 * `WindowInsets.systemBars` 变成 0（不可见的栏种按定义报 0，见 `WindowInsetsCompat.getInsets`），
 * 于是 `系统栏 ∪ 挖孔` 这份贴底浮层 inset 在沉浸态只剩挖孔——竖屏无挖孔就是 0，
 * 阅读菜单面板的底部一行与跨书确认条的按钮会直接贴到屏幕下缘，落进手势导航「上滑回首页」的触发带。
 *
 * 本文件钉住口径：底部 inset = 系统栏底 ∪ 挖孔底 ∪ [ReaderOverlayLayout.MIN_BOTTOM_DP] 的最小留白。
 * 三档取最大 ⇒ **栏可见时结果与改动前逐像素相同**（导航栏底 inset 原样生效，不出现「双重内缩」），
 * 栏隐藏时也不会塌成 0。
 *
 * 不覆盖的部分（写明，避免读成全覆盖）：阅读器路由真的去 hide/show 系统栏、以及隐藏后系统是否只剩挖孔
 * inset —— 那是平台行为，仓库没有能观测它的 JVM 测试基建（Robolectric 的 `rootWindowInsets` 恒为全 0、
 * 探不到隐藏状态），由真机验收（AC5：手机手势导航 + 平板各一次、附前后对比截图）与 `ui/ReaderSystemBars.kt`
 * 的接线共同把守；横屏挖孔在左/右时不被切属 [ReaderOverlayLayout] 之外的横向 inset，本票未改。
 */
class ReaderOverlayLayoutTest {

    /** 手机基准密度（Pixel 一类 3x） */
    private val phoneDensity = 3f

    @Test
    fun `系统栏可见时底部 inset 与改动前逐像素相同`() {
        // 三按钮导航的导航栏底 inset 48dp @3x：取最大后仍是系统栏那一条，浮层位置不变
        val systemBarBottomPx = (48 * phoneDensity).toInt()

        val bottom = ReaderOverlayLayout.overlayBottomPx(
            systemBarsBottomPx = systemBarBottomPx,
            cutoutBottomPx = 0,
            density = phoneDensity,
        )

        assertEquals(systemBarBottomPx, bottom)
    }

    @Test
    fun `系统栏隐藏后底部留白不塌成 0 且不小于手势带`() {
        val bottom = ReaderOverlayLayout.overlayBottomPx(
            systemBarsBottomPx = 0,
            cutoutBottomPx = 0,
            density = phoneDensity,
        )

        assertEquals((ReaderOverlayLayout.MIN_BOTTOM_DP * phoneDensity).toInt(), bottom)
        assertTrue("手势导航的上滑带贴屏幕下缘，底部留白必须 > 0：$bottom", bottom > 0)
        assertTrue(
            "不得小于手势带（16–24dp）的上限：${ReaderOverlayLayout.MIN_BOTTOM_DP}dp",
            ReaderOverlayLayout.MIN_BOTTOM_DP >= 24f,
        )
    }

    @Test
    fun `挖孔的底部 inset 在栏隐藏后照旧保留`() {
        // 挖孔是物理特征，不受系统栏可见性影响；比最小留白大时以挖孔为准
        val cutoutBottomPx = (34 * phoneDensity).toInt()

        val bottom = ReaderOverlayLayout.overlayBottomPx(
            systemBarsBottomPx = 0,
            cutoutBottomPx = cutoutBottomPx,
            density = phoneDensity,
        )

        assertEquals(cutoutBottomPx, bottom)
    }

    @Test
    fun `挖孔底小于最小留白时取最小留白`() {
        val bottom = ReaderOverlayLayout.overlayBottomPx(
            systemBarsBottomPx = 0,
            cutoutBottomPx = 10,
            density = phoneDensity,
        )

        assertEquals((ReaderOverlayLayout.MIN_BOTTOM_DP * phoneDensity).toInt(), bottom)
    }

    @Test
    fun `系统栏与挖孔都比最小留白大时以两者中较大的为准`() {
        val systemBarBottomPx = (48 * phoneDensity).toInt()
        val cutoutBottomPx = (34 * phoneDensity).toInt()

        assertEquals(
            systemBarBottomPx,
            ReaderOverlayLayout.overlayBottomPx(systemBarBottomPx, cutoutBottomPx, phoneDensity),
        )
    }

    @Test
    fun `按密度换算成像素 平板 1x 上就是 24px`() {
        assertEquals(24, ReaderOverlayLayout.overlayBottomPx(0, 0, density = 1f))
        assertEquals(48, ReaderOverlayLayout.overlayBottomPx(0, 0, density = 2f))
    }
}
