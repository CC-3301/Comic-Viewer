package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页「整屏内容可以显示了吗」（票 #111 r9 A2 + r10 b2/2 就绪兑底，用例在 r10 b3/3 补上）。
 *
 * 为什么这个类需要用例：它的失败后果是**整屏不可见**（`contentAlpha` 恒 0，连失败文案与重试按钮都压在
 * alpha 0 上，只能退出重进）。r9 只把信号接在**首页**那一页上，而回调在那一页自己的 `LaunchedEffect` 里——
 * 开屏就甩动 / 切阅读模式时那一页在解码完成前离开组合，effect 被取消就永远不就绪。
 *
 * 判别力：
 * - 把「**任一页**到位」退回「只有首页那一页到位」⇒ `任一页到位就够` 即红；
 * - 去掉「确定失败也算就绪」⇒ `失败页也算就绪` 即红（正是上面那条后果）；
 * - 去掉空书那条 ⇒ `空书立即就绪` 即红（那句「此书没有可显示的页面」永远浮不出来）；
 * - 把「有图才让位」写成「到位就让位」⇒ `让位只认真的画出图` 即红（`readerContentFadeMillis` 会给 0，
 *   失败文案也会被硬切）。
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
        readiness.onPageSettled(hasImage = true)

        assertTrue("任一页到位即算就绪（首页那一路断掉也不影响）", readiness.ready)
    }

    @Test
    fun `失败页也算就绪 不会卡在整屏不可见`() {
        val readiness = ReaderContentReadiness(pageCount = 40)

        readiness.onPageSettled(hasImage = false)

        assertTrue("确定失败也算就绪：否则失败文案与重试按钮永远压在 alpha 0 上", readiness.ready)
        assertFalse("失败页不算「有图可画」", readiness.settledWithImage)
        assertEquals(
            "没有图 ⇒ 整屏仍走 150ms 淡入（当前口径 CONTENT_FADE_MILLIS，私有常量不外露）",
            150,
            readerContentFadeMillis(readiness.settledWithImage),
        )
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

        readiness.onPageSettled(hasImage = true)

        assertTrue("有图 ⇒ 整屏立即置 1（0ms），只留图片自己那条 150ms 斜坡", readiness.settledWithImage)
        assertEquals(0, readerContentFadeMillis(readiness.settledWithImage))
    }

    @Test
    fun `换书与重试换实例 切阅读模式不换实例`() {
        // 换书 / 重试：`ReaderScreen` 的 remember 键是 (bookId, reloadTick) + 页数 ⇒ 新实例，从「未就绪」开始
        assertFalse(
            "换一本 / 重试 = 新实例：不继承上一本的已就绪（新书要重新等首批）",
            ReaderContentReadiness(pageCount = 40).ready,
        )

        // 切阅读模式：键里没有 mode ⇒ 实例不换 ⇒ 已记下的就绪事实不因「换一批页去组合」而回退。
        // 这正是兑底要保住的：切模式不该把整屏内容重新扣掉。
        val acrossModeSwitch = ReaderContentReadiness(pageCount = 40)
        acrossModeSwitch.onPageSettled(hasImage = true)
        assertTrue("切模式后再问：仍就绪", acrossModeSwitch.ready)
        assertTrue("已记下的「有图」不被后来的调用抹掉", acrossModeSwitch.settledWithImage)

        acrossModeSwitch.onPageSettled(hasImage = false)
        assertTrue("后来的页不带图，也不影响已记下的「有图」", acrossModeSwitch.settledWithImage)
        assertTrue("仍就绪", acrossModeSwitch.ready)
    }
}
