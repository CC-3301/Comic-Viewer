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
     * 整屏内容淡入的时长（票 #111 r11 §4）：屏幕从主题背景色切到正文那一刻**只有一条斜坡**——
     * - 那一屏的图**已经在首帧就到手**（命中解码缓存 ⇒ 图片自己不会淡）⇒ 整屏补一条 150ms；
     * - 那一屏的图**是刚到、自己会淡**⇒ 整屏立即（0ms），不要两条斜坡叠成「先暗后亮」；
     * - **根本没有图**（失败文案 / 空书 / 首图还没到）⇒ 文案也走 150ms，不让它硬切。
     *
     * 本轮修的就是第一条：旧口径（r10 b2/2）只看「有没有图」，命中缓存那一屏因此被当作「有图可画、让位给
     * 图片自己那条」⇒ 整屏 0ms，而那一屏的图**根本没有斜坡**（它就是秒出的）⇒ 真机看到的是「画面突然碎出来」。
     * 判别力：只按「有没有图」判 ⇒ 第一条断言即红；恒返回 150 ⇒ 第二条即红；恒返回 0 ⇒ 第三条即红。
     */
    @Test
    fun `图已经在手时整屏补一条 图刚到则整屏立即 没有图仍淡入`() {
        assertEquals(
            "图已在手（命中解码缓存）：图片自己那条不会发生 ⇒ 整屏补 150ms",
            150,
            readerContentFadeMillis(settledWithImage = true, imageFadesItself = false),
        )
        assertEquals(
            "图刚到（它自己有一条 150ms）⇒ 整屏立即，只留一条斜坡",
            0,
            readerContentFadeMillis(settledWithImage = true, imageFadesItself = true),
        )
        assertEquals(
            "没有任何到位页画出过图（失败文案 / 空书 / 首图还没到）：整屏仍 150ms 淡入",
            150,
            readerContentFadeMillis(settledWithImage = false, imageFadesItself = false),
        )
    }
}
