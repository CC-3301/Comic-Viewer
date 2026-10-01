package com.cc3301.comicviewer.ui

import androidx.compose.ui.unit.dp
import com.cc3301.comicviewer.core.input.PullRefreshGesture
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下拉更新的触发阈值（票 #147 AC：「下拉阈值留在一个可断言的出处，并补一条『阈值 = 期望 dp』的用例」）。
 *
 * 阈值只有一处出处——`ui/PullToRefreshArea.kt` 的 [REFRESH_THRESHOLD]（`progress` 与刷新期间指示器的停位
 * 都由它派生），这里把它连同「手指实际要拖多少」的那笔账一起钉住：量的是**指示器位移**，手指位移要除以
 * 阻尼 [PullRefreshGesture.DRAG_MULTIPLIER]，因此数值改动一眼看得见。
 *
 * 真机手感（在顶部轻拽不触发、明确拽一把触发一次）不在本用例范围（走手动验收）。
 */
class PullToRefreshAreaTest {

    @Test
    fun `下拉触发阈值是 160dp`() {
        assertEquals("票 #147：112dp → 160dp（手指需拖约 320dp）", 160.dp, REFRESH_THRESHOLD)
    }

    @Test
    fun `阈值折算到手指位移约 320dp 因为阻尼是一半`() {
        assertEquals(
            "指示器位移 ÷ 阻尼 = 手指位移（再各加一次 touchSlop）；阻尼改了这条会红，提醒连带重算手感",
            320f,
            REFRESH_THRESHOLD.value / PullRefreshGesture.DRAG_MULTIPLIER,
            0.001f,
        )
    }
}
