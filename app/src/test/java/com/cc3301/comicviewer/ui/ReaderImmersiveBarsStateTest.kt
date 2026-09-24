package com.cc3301.comicviewer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸态系统栏的驱动（票 #111 r9 ③）：离开阅读器后**多留一个过渡窗口**再恢复系统栏。
 *
 * 判据：pop 那一帧 `currentRoute` 已是浏览页，而黑底阅读页还在往右滑出去——此刻 `show()` 就是真机反馈的
 * 「返回时会闪一下」。把恢复推到过渡窗口之后，系统栏就不会在黑底还在动的时候冒出来。
 *
 * 判别力：把判定改回 `route == Routes.READER`（不看那一帧），第一条「离开的当帧仍隐藏」即红；
 * 把窗口写成 0 或漏掉「上一帧真的是阅读器」这条边，后两条分别变红。
 */
class ReaderImmersiveBarsStateTest {

    @Test
    fun `进入阅读器隐藏系统栏 离开的当帧仍隐藏`() {
        val state = ReaderImmersiveBarsState(windowMillis = 300)

        assertTrue("进阅读器：隐藏", state.immersiveFor(Routes.READER, nowMillis = 1_000))
        assertTrue("停在阅读器：仍隐藏", state.immersiveFor(Routes.READER, nowMillis = 5_000))
        assertTrue(
            "返回的当帧（黑底阅读页还在滑出）：仍隐藏——否则系统栏先冒出来就是「闪一下」",
            state.immersiveFor(Routes.BROWSER, nowMillis = 6_000),
        )
    }

    @Test
    fun `离开后过渡窗口走完才恢复系统栏`() {
        val state = ReaderImmersiveBarsState(windowMillis = 300)
        state.immersiveFor(Routes.READER, nowMillis = 1_000)
        state.immersiveFor(Routes.BROWSER, nowMillis = 6_000)

        assertTrue("窗口内（第 299ms）：仍隐藏", state.immersiveFor(Routes.BROWSER, nowMillis = 6_299))
        assertFalse("窗口到点（第 300ms）：恢复", state.immersiveFor(Routes.BROWSER, nowMillis = 6_300))
        assertFalse("之后一直是恢复态", state.immersiveFor(Routes.BROWSER, nowMillis = 9_000))
    }

    @Test
    fun `别处的路由切换不藏系统栏`() {
        val state = ReaderImmersiveBarsState(windowMillis = 300)

        assertFalse("首页不藏", state.immersiveFor(Routes.HOME, nowMillis = 1_000))
        assertFalse("首页 → 书柜：上一帧不是阅读器，不藏", state.immersiveFor(Routes.BOOKSHELF, nowMillis = 1_100))
        assertFalse("启动中转页也不藏", state.immersiveFor(Routes.STARTUP, nowMillis = 1_200))
    }

    @Test
    fun `再次进出阅读器时窗口重新起算`() {
        val state = ReaderImmersiveBarsState(windowMillis = 300)
        state.immersiveFor(Routes.READER, nowMillis = 1_000)
        state.immersiveFor(Routes.BROWSER, nowMillis = 6_300)

        assertTrue("第二次进阅读器：隐藏", state.immersiveFor(Routes.READER, nowMillis = 20_000))
        assertTrue("第二次返回的当帧：仍隐藏", state.immersiveFor(Routes.BROWSER, nowMillis = 20_000))
        assertTrue("新窗口内：仍隐藏", state.immersiveFor(Routes.BROWSER, nowMillis = 20_299))
        assertFalse("新窗口到点：恢复", state.immersiveFor(Routes.BROWSER, nowMillis = 20_300))
    }
}
