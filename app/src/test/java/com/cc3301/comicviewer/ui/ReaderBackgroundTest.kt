package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页里**纯判据**的家：整屏内容淡入的时长（[readerContentFadeMillis]）与首批窗口那一格的判据
 * （[readerPageWaitsForFirstPaint]）。两者都在 `ReaderScreen.kt` 里、都不碰 Compose 状态，因此放在本文件里钉。
 *
 * 根背景没有判据可钉：**恒黑**（从进档到就绪一路黑底、不随就绪变）——空态与失败文案都是写死的白字，
 * 压在黑底上读得到。
 */
class ReaderBackgroundTest {

    /**
     * 空书的就绪判据（[ReaderContentReadiness]）：空书**直接算就绪**（没有页会报到，等它永不开闭）。
     * 判别力：把 `ready` 改成「有页报到才算就绪」（或删掉 `pageCount == 0` 那条），断言即红。
     */
    @Test
    fun `空书直接算就绪`() {
        assertTrue("空书直接算就绪（没有页会报到）", ReaderContentReadiness(pageCount = 0).ready)
    }

    /**
     * 首批窗口那一格的三条边界：空书不开窗、只有入口页开窗、入口页到位即关窗。
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
            "有页 + 入口页还没到位：开窗（这一格走空占位、不画进度圈）",
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
     * 整屏内容淡入的时长：屏幕从黑底切到正文那一刻**只有一条斜坡**——
     * - 那一屏的图**已经在首帧就到手**（命中解码缓存 ⇒ 图片自己不会淡）⇒ 整屏补一条 150ms；
     * - 那一屏的图**是刚到、自己会淡**⇒ 整屏立即（0ms），不要两条斜坡叠成「先暗后亮」；
     * - **根本没有图**（失败文案 / 空书 / 首图还没到）⇒ 文案也走 150ms，不让它硬切。
     *
     * 第一条就是修的那条：旧口径只看「有没有图」，命中缓存那一屏因此被当作「有图可画、让位给
     * 图片自己那条」⇒ 整屏 0ms，而那一屏的图**根本没有斜坡**（它就是秒出的）⇒ 设备上看到的是「画面突然碎出来」。
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
