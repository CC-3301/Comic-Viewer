package com.cc3301.comicviewer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页根背景的判据（票 #111 AC-6 + 修复轮 P1）：**只有「等页且没有失败」**那一支用主题背景色。
 *
 * 背景在 r1 从恒 `Color.Black` 改成「等页 = 主题背景色」，而失败分支的文案是**写死的白字**
 * （`ReaderScreen` 的错误分支）：浅色主题（`MainActivity` 用 `lightColorScheme()`）下就是近白底 + 白字，
 * 用户看不到失败原因（症状是「有重试按钮、没有失败原因」）。判据收在 [readerShowsThemeBackground] 里。
 *
 * 纯 JVM 用例拿不到 Compose 主题色，因此断言的是「**不用**主题背景」——失败与页就绪一律走阅读器黑底，
 * 白字的对比度因此不依赖主题（拿不到颜色就断言「不是主题浅底」，比断言某个具体颜色更贴住本条的失因）。
 */
class ReaderBackgroundTest {

    @Test
    fun `打开失败时不走主题背景 白字才有对比`() {
        assertFalse(
            "失败分支是白字：浅色主题下主题背景近白，白字压上去读不到",
            readerShowsThemeBackground(hasError = true, isWaitingPages = true),
        )
    }

    @Test
    fun `页就绪后回到阅读器黑底`() {
        assertFalse(readerShowsThemeBackground(hasError = false, isWaitingPages = false))
    }

    @Test
    fun `只有等页期间用主题背景色`() {
        assertTrue(readerShowsThemeBackground(hasError = false, isWaitingPages = true))
    }
}
