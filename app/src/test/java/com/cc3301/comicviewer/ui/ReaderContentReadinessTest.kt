package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页「整屏内容可以显示了吗」（票 #111 r9 A2 + r10 b2/2 就绪兜底，用例在 r10 b3/3 补上）。
 *
 * 为什么这个类需要用例：它的失败后果是**整屏不可见**（`contentAlpha` 恒 0，连失败文案与重试按钮都压在
 * alpha 0 上，只能退出重进）。r9 只把信号接在**首页**那一页上，而回调在那一页自己的 `LaunchedEffect` 里——
 * 开屏就甩动 / 切阅读模式时那一页在解码完成前离开组合，effect 被取消就永远不就绪。
 *
 * 判别力（只列**在本类接缝上真能红**的形态）：
 * - 把 [ReaderContentReadiness.ready] 改成「有图才算就绪」（拿掉「确定失败也算」）⇒ `失败页也算就绪` 即红；
 * - 去掉 `pageCount == 0` 那条 ⇒ `空书立即就绪` 即红（那句「此书没有可显示的页面」永远浮不出来）；
 * - 把到位集合换回**计数器**、或去掉幂等 ⇒ `同一页重复上报算一页` 即红（同页重报会算成多页）；
 * - 把「有图」与「到位」合成同一份记录 ⇒ `让位只认真的画出图` 即红（失败页也会被当成「有图」）。
 *
 * 整屏淡入时长（有图 0ms / 没有图 150ms）的判据**不在这里**：它在 `ReaderBackgroundTest`（`ReaderScreen.kt`
 * 里纯判据的家，与 [readerShowsThemeBackground] 同类）；本文件只断言「哪一页到位 / 有没有画出图」这两件事。
 *
 * **本文件钉不住的两半**（不为它编造断言）：① `ReaderScreen` 是否真的把回调接在**每一页**上
 *（组合树里的事，本仓无 Compose 组合测试面）；② 切阅读模式**不**换实例这件事（靠 `ReaderScreen` 的
 * `remember` 键，同样只在组合期可见）。真机判据：开屏后**立刻**快速甩动（条漫上下甩 / 单页连翻）或
 * 条漫↔单页来回切，阅读器不出现「整屏空白且没有任何内容」；断链时失败文案与「点此重试」仍浮出来。
 */
class ReaderContentReadinessTest {

    @Test
    fun `任一页到位就够 不再只有首页那一页`() {
        val readiness = ReaderContentReadiness(pageCount = 40)
        assertFalse("还没任何页到位：整屏内容先不显示（新屏先是主题背景色纯色）", readiness.ready)

        // 到位的是第 17 页的形态（首页因开屏甩动提前离开组合、它的 effect 被取消）
        readiness.onPageSettled(index = 17, hasImage = true)

        assertTrue("任一页到位即算就绪（首页那一路断掉也不影响）", readiness.ready)
    }

    @Test
    fun `失败页也算就绪 不会卡在整屏不可见`() {
        val readiness = ReaderContentReadiness(pageCount = 40)

        readiness.onPageSettled(index = 0, hasImage = false)

        assertTrue("确定失败也算就绪：否则失败文案与重试按钮永远压在 alpha 0 上", readiness.ready)
        assertFalse("失败页不算「有图可画」", readiness.settledWithImage)
        // 淡入时长按「有没有画出图」取 0 / 150 的判据在 `ReaderBackgroundTest`（那边的家），这里不重复
    }

    @Test
    fun `空书立即就绪`() {
        assertTrue(
            "pageCount == 0：那句「此书没有可显示的页面」必须能浮出来",
            ReaderContentReadiness(pageCount = 0).ready,
        )
    }

    @Test
    fun `让位只认真的画出图`() {
        val readiness = ReaderContentReadiness(pageCount = 3)
        assertFalse("还没到位：没有可让位的（整屏淡入仍是 150ms）", readiness.settledWithImage)

        readiness.onPageSettled(index = 0, hasImage = true)

        assertTrue("有图 ⇒ 整屏立即置 1（0ms），只留图片自己那条 150ms 斜坡", readiness.settledWithImage)
    }

    @Test
    fun `新实例从零开始 已记下的事实不被后来的上报改写`() {
        // 本用例钉**实例自身的语义**，不声称「`ReaderScreen` 何时换实例」（那靠它的 remember 键，属组合期；
        // 类 KDoc 的「钉不住的两半 ①」已写明。真机判据：换一本 / 重试后要重新等首批；条漫↔单页来回切，内容不消失）。
        // 「新实例」= 换一本 / 重试之后 read 到的那一份（其 remember 键含 bookId + reloadTick + 页数）：
        // 不继承上一本的已就绪
        assertFalse(
            "换一本 / 重试 = 新实例：不继承上一本的已就绪（新书要重新等首批）",
            ReaderContentReadiness(pageCount = 40).ready,
        )

        // 同一实例被继续上报（切模式后换一批页去组合的形态）：已记下的事实不回退——
        // 这正是兜底要保住的：换一批页去组合不该把整屏内容重新扣掉。
        val acrossModeSwitch = ReaderContentReadiness(pageCount = 40)
        acrossModeSwitch.onPageSettled(index = 0, hasImage = true)
        assertTrue("切模式后再问：仍就绪", acrossModeSwitch.ready)
        assertTrue("已记下的「有图」不被后来的调用抹掉", acrossModeSwitch.settledWithImage)

        acrossModeSwitch.onPageSettled(index = 1, hasImage = false)
        assertTrue("后来的页不带图，也不影响已记下的「有图」", acrossModeSwitch.settledWithImage)
        assertTrue("仍就绪", acrossModeSwitch.ready)
    }

    /**
     * 已到位的页是**集合**而不是计数器（票 #111 r10 b4/4，评审 r10-b2 P2）：同一页的 effect 重跑 / 切阅读模式
     * 重入会重复上报同一个页号，计数会失真（`> 0` 看不出来，但「同页算一页」这件事不再成立）。
     * 拿掉幂等（换回计数）⇒ 第一条断言即红。
     */
    @Test
    fun `同一页重复上报算一页 不重复累加`() {
        val readiness = ReaderContentReadiness(pageCount = 40)

        readiness.onPageSettled(index = 17, hasImage = true)
        readiness.onPageSettled(index = 17, hasImage = true)

        assertEquals("同页重报幂等（切模式重入 / effect 重跑）", setOf(17), readiness.settledPages)
        assertTrue("仍算就绪", readiness.ready)
        assertTrue("仍算「有图」", readiness.settledWithImage)

        readiness.onPageSettled(index = 3, hasImage = false)
        assertEquals("另一页到位：集合里两页，且失败页不进「有图」那一份", setOf(17, 3), readiness.settledPages)
        assertTrue(readiness.settledWithImage)
    }
}
