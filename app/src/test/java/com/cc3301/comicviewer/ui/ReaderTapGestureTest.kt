package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页单击 / 双击的手势口径（票 #129 r4）：**单击要马上有反馈，双击又不能闪**。
 *
 * 真机反馈「点击之后是延时出现的」的因果：`detectTapGestures` 只要拿到 `onDoubleTap`，单击就必然被推后到
 * **双击等待窗口**超时之后才触发（识别器没法知道你会不会点第二下），而 Android 的默认窗口是 300ms
 * ⇒ 单击的感知延迟 = 300ms 静等 + 出现动画 100ms ≈ 0.4s（与维护者量到的 ≈0.5s 对得上）。
 * 两条出路里选的是**砍窗口**：`ui/ReaderTapGesture.kt` 自己写一小段手势，把窗口钉到
 * [ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS] = 200ms。另一条路（单击立即响应 + 双击第二下撤销）
 * 会让菜单在双击时**闪一下**，已被维护者明确否决。
 *
 * **本机钉得住的是常量与纯函数**：窗口、第二下「太早」的下限、以及「这一下算不算双击第二下」的边界。
 * **钉不住的是手势本身**（本仓库无 Compose UI 测试依赖，SPEC 的 Testing Decisions 把 UI 层交给手动验收）：
 * 真机判据 —— 单击唤出菜单（不再有明显等待）、双击放大**都还灵**；出问题的表现是「双击不放大」或
 * 「单击没反应」，那就一行切回 `detectTapGestures`（调用点在 `ui/ReaderScreen.kt`）。
 */
class ReaderTapGestureTest {

    /**
     * 窗口大小**就是**单击的感知延迟里那段静等：把它改大（如回到系统默认 300ms）真机立刻变钝，
     * 改小则双击更容易被误判成两次单击（第二次单击会翻页）。
     */
    @Test
    fun `双击窗口是 200ms`() {
        assertEquals(
            "单击的感知延迟 = 本窗口 + 出现时长（ReaderMenuTransitions.ENTER_DURATION_MILLIS）；" +
                "200ms 比系统默认 300ms 少等 100ms",
            200L,
            ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS,
        )
    }

    /**
     * 边界必须钉死：**含**窗口那一毫秒（第 200ms 算双击）、**不含**第 201ms。
     * 若判成 `< 200`，实际窗口就比声明少 1ms；若判成 `<= 201` 以上，则「窗口 200ms」名不副实。
     */
    @Test
    fun `第二下按下落在窗口内才算双击`() {
        assertTrue("窗口内（100ms 后）", ReaderTapGesture.isSecondDownWithinWindow(1_000L, 1_100L))
        assertTrue("边界值算在内（第 200ms）", ReaderTapGesture.isSecondDownWithinWindow(1_000L, 1_200L))
        assertFalse(
            "越界 1ms 就不算（第 201ms）——否则声明的窗口是假的",
            ReaderTapGesture.isSecondDownWithinWindow(1_000L, 1_201L),
        )
    }

    /**
     * 「第二下」有一个下限：与第一下抬起几乎同一时刻的按下（同一帧里的多指 / 合成事件）不算第二下，
     * 而是**继续等**（平台默认 `doubleTapMinTimeMillis` = 40ms，本类沿用同一档）。
     * 若没有这条，多指手势里紧贴抬起的按下会被当成双击 ⇒ 意外放大。
     */
    @Test
    fun `第二下与第一下抬起几乎同时的不算双击`() {
        assertTrue("39ms：早于下限，不算第二下", ReaderTapGesture.isSecondDownTooEarly(1_000L, 1_039L))
        assertFalse("40ms：刚好到下限，算第二下", ReaderTapGesture.isSecondDownTooEarly(1_000L, 1_040L))
        assertFalse("窗口内的正常双击间隔（100ms）", ReaderTapGesture.isSecondDownTooEarly(1_000L, 1_100L))
    }
}
