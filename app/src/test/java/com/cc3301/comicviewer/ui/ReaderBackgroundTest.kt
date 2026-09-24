package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页里**纯判据**的家（票 #111）：根背景的取色（[readerShowsThemeBackground]）、整屏内容淡入的时长
 * （[readerContentFadeMillis]）与首批窗口那一格的判据（[readerPageWaitsForFirstPaint]）。三者都在
 * `ReaderScreen.kt` 里、都不碰 Compose 状态，因此放在本文件里钉。
 *
 * 根背景的判据（票 #111 AC-6 + 修复轮）：**只有「屏上还没有正文可看」**那一支用主题背景色；
 * 失败分支的文案是**写死的白字**（`ReaderScreen` 的错误分支与空态文案），浅色主题（`MainActivity` 用
 * `lightColorScheme()`）下主题背景近白 ⇒ 白字压上去读不到（症状是「有重试按钮、没有失败原因」、
 * 或空书那句「此书没有可显示的页面」看不见）。因此 r10 b6/6 把判据收成**单语义 + 有终点**：
 * `contentReady`（= 书已落地且任一页到位；空书在其中直接算就绪）为真就回黑底。
 */
class ReaderBackgroundTest {

    @Test
    fun `打开失败时不走主题背景 白字才有对比`() {
        assertFalse(
            "失败分支是白字：浅色主题下主题背景近白，白字压上去读不到",
            readerShowsThemeBackground(hasError = true, contentReady = false),
        )
        assertFalse(
            "已就绪 + 打开失败同样是黑底（失败优先）",
            readerShowsThemeBackground(hasError = true, contentReady = true),
        )
    }

    @Test
    fun `屏上已有正文可看时回到阅读器黑底`() {
        assertFalse(readerShowsThemeBackground(hasError = false, contentReady = true))
    }

    @Test
    fun `屏上还没有正文可看时用主题背景色`() {
        assertTrue(readerShowsThemeBackground(hasError = false, contentReady = false))
    }

    /**
     * 本票的 P1（b5 引入）：b5 的根背景判据是「书没落地 **或** 首批窗口」——对**空书**永不开闭
     *（没有任何页会报到），于是空书停在主题背景色上，而空态文案是写死的白字 ⇒ 浅色主题下看不见。
     *
     * 这里钉的是**整条链**：空书在 [ReaderContentReadiness] 里直接算就绪 ⇒ `contentReady` 为真 ⇒ 走黑底。
     * 判别力：把 `ready` 改成「有页报到才算就绪」（或删掉 `pageCount == 0` 那条），第一条断言即红。
     */
    @Test
    fun `空书的就绪状态不会把它留在主题背景色上`() {
        val emptyBookReady = ReaderContentReadiness(pageCount = 0).ready
        assertTrue("空书直接算就绪（没有页会报到）", emptyBookReady)
        assertFalse("因此根背景走黑底，白字空态文案读得到", readerShowsThemeBackground(hasError = false, contentReady = emptyBookReady))
    }

    /**
     * 首批窗口那一格的三条边界（票 #111 r10 b5/5 + b6/6）：空书不开窗、只有入口页开窗、入口页到位即关窗。
     * 判别力：去掉 `pageCount > 0`（空书也开窗）⇒ 第一条即红；去掉 `index == startIndex`（洩到每一页）
     * ⇒ 第三条即红；去掉 `!entryPageSettled`（窗口没有终点）⇒ 第四条即红。
     */
    @Test
    fun `首批窗口的三条边界 空书不开窗 只有入口页开窗 入口页到位即关窗`() {
        val startIndex = 17

        assertFalse(
            "空书（pageCount == 0）不开窗：没有任何页会报到，开了就永不开闭",
            readerPageWaitsForFirstPaint(index = startIndex, startIndex = startIndex, pageCount = 0, entryPageSettled = false),
        )
        assertTrue(
            "有页 + 入口页还没到位：开窗（这一格走空占位 + 主题背景色）",
            readerPageWaitsForFirstPaint(index = startIndex, startIndex = startIndex, pageCount = 40, entryPageSettled = false),
        )
        assertFalse(
            "其余页永不开窗：入口页 effect 被取消时只影响它自己，别的未解码页照旧画圈",
            readerPageWaitsForFirstPaint(index = 3, startIndex = startIndex, pageCount = 40, entryPageSettled = false),
        )
        assertFalse(
            "入口页到位即关窗（可画或确定失败都算）",
            readerPageWaitsForFirstPaint(index = startIndex, startIndex = startIndex, pageCount = 40, entryPageSettled = true),
        )
    }

    /**
     * 整屏内容淡入的时长（票 #111 r10 b2/2 + b4/4）：有图可画时**让位**（0ms，只留图片自己那条 150ms），
     * 没有任何到位页画出过图时仍走 150ms。
     *
     * 判别力：把函数写成恒返回 `CONTENT_FADE_MILLIS`（两条斜坡相乘的二段式）⇒ 第一条断言即红；
     * 写成恒返回 0（失败文案也硬切）⇒ 第二条即红。150 是当前口径的数值（`CONTENT_FADE_MILLIS` 私有，不外露）。
     */
    @Test
    fun `有图让位给图片自己那条斜坡 没有图才走整屏淡入`() {
        assertEquals("有图可画：整屏立即置 1（0ms），只留 imageAlpha 的 150ms", 0, readerContentFadeMillis(settledWithImage = true))
        assertEquals("没有任何到位页画出过图（失败文案 / 空书 / 首图还没到）：整屏仍 150ms 淡入", 150, readerContentFadeMillis(settledWithImage = false))
    }
}
