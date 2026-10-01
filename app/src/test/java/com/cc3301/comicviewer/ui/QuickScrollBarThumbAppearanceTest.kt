package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速定位滑条本体的**按住态外观**（票 #148 ③）：按住/拖动期间取仓库强调橙 [ACCENT_ORANGE] 且**不透明**，
 * 松手回主题灰 + [QUICK_SCROLL_BAR_ALPHA]（0.4）。
 *
 * 为什么钉这两个纯函数而不是量渲染：本仓库没有 compose-ui-test 基建（同 [QuickScrollBarSizeTest] 的限制：
 * 滑条只在 1.2 秒淡出窗口内存在、单测里起不了帧），因此按仓库既有做法钉**出货取值的同源函数**——
 * `ui/QuickScrollBar.kt` 里本体的 `background` 就是拿这两个函数算 color 与 alpha，屏上的
 * 色值与不透明度直接由它们决定。
 *
 * **色值只读 `ACCENT_ORANGE` 一处**：`ui/QuickScrollBar.kt` 不写任何 `Color(0x…)` 字面量，橙色只有
 * `ui/AccentColor.kt:14` 这一个来源（票 #105 起仓库口径；票 #100 的「橙色跳转按钮」就是它）。
 *
 * **变橙是输入态，不是可见态**：入参只有「按住/拖动」（`held`），滚动导致滑条现身时仍是灰色 0.4。
 */
class QuickScrollBarThumbAppearanceTest {

    /** 主题静息色（界面传 `MaterialTheme.colorScheme.onSurface`；本用例只需要一个可区分的占位色） */
    private val idleColor = Color(0xFF333333)

    /** 票 #148 ③：按住/拖动 = 强调橙（#100 那个橙）+ 不透明 */
    @Test
    fun `按住或拖动时本体是不透明的强调橙`() {
        assertEquals(ACCENT_ORANGE, quickScrollBarThumbColor(idleColor = idleColor, held = true))
        assertEquals(1f, quickScrollBarThumbAlpha(held = true), 1e-6f)
    }

    /** 票 #148 ③：松手回灰 0.4——颜色回到传入的静息色，不透明度回到静息档 */
    @Test
    fun `松手后本体回静息色与 0_4 不透明度`() {
        assertEquals(idleColor, quickScrollBarThumbColor(idleColor = idleColor, held = false))
        assertEquals(0.4f, quickScrollBarThumbAlpha(held = false), 1e-6f)
    }

    /**
     * 判别力（**两个方向都钉**）：按住态与静息态必须真的不同——把 `held` 忽略掉（恒返静息色 /
     * 恒返 0.4）时上面两条与这条一起变红；反过来把静息态也当成橙时 [松手后本体回静息色与 0_4 不透明度] 变红。
     */
    @Test
    fun `按住态与静息态在颜色与不透明度上都不同`() {
        assertNotEquals(
            quickScrollBarThumbColor(idleColor = idleColor, held = false),
            quickScrollBarThumbColor(idleColor = idleColor, held = true),
        )
        assertTrue(quickScrollBarThumbAlpha(held = true) > quickScrollBarThumbAlpha(held = false))
    }
}
