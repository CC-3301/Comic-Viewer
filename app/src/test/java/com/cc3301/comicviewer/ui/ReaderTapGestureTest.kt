package com.cc3301.comicviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读页单击 / 双击在**界面侧**的产品口径（票 #129 r4；r5 起判定全部搬进 `core/input/ReaderTapGestureState.kt`）。
 *
 * 本文件只钉一件事：**双击等待窗口 = [ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS] = 150ms**——
 * 它是本票在界面侧唯一的口径声明（平台默认是 300ms），也是单击感知延迟里那段静等。
 *
 * **判定不在这一层**（r5 评审 standards P2-3 的收口）：哪一支发单击 / 双击 / 放弃、窗口与最小间隔怎么算，
 * 全在 `core/input/ReaderTapGestureState.kt` 的状态机里，由 `core/input/ReaderTapGestureStateTest.kt` 直接驱动
 * （换一组窗口/间隔就换一条规则，不需要组合、帧时钟或指针事件）。`ui/ReaderTapGesture.kt` 只剩
 * 「指针事件 → 状态机输入」「效果 → onTap / onDoubleTap」「读 `viewConfiguration` 的平台量」三件翻译工作。
 *
 * **本机钉不住的是翻译本身**（本仓库无 Compose UI 测试依赖，SPEC 的 Testing Decisions 把 UI 层交给手动验收）：
 * 真机判据 —— 点屏幕中区呼出菜单**不再有明显等待**、双击放大**仍灵**；坏掉的表现是「双击不放大」或
 * 「单击没反应」，那就一行切回 `detectTapGestures`（调用点在 `ui/ReaderScreen.kt` 的 `pointerInput`）。
 *
 * 面板出现 120ms / 消失 100ms 那一侧的口径见 `ReaderMenuTransitionsTest`（与本文件无关）。
 */
class ReaderTapGestureTest {

    /**
     * 窗口大小**就是**单击的感知延迟里那段静等：把它改大（如回到平台默认 300ms）真机立刻变钝，
     * 改小则双击更容易被误判成两次单击（第二次单击会翻页）。
     *
     * 本轮取 150ms：出现支从 50ms 拉到 120ms（真机反馈那支「很急」），这段静等就砍回 50ms
     * ——「点了到看见」≈ 150 + 120 = 270ms（上一轮 ≈ 250ms，两支之和由 `ReaderMenuTransitionsTest` 钉住）。
     */
    @Test
    fun `双击窗口是 150ms`() {
        assertEquals(
            "单击的感知延迟 = 本窗口 + 出现时长（ReaderMenuTransitions.ENTER_DURATION_MILLIS）；" +
                "150ms 比平台默认 300ms 少等 150ms",
            150L,
            ReaderTapGesture.DOUBLE_TAP_WINDOW_MILLIS,
        )
    }
}
