package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 进度条样式常量（票 #92 需求 3）：条高、轨道不透明、端帽方头。三者都抽成了常量，因此用可失败断言钉住——
 * - 条高 **6dp**：网格档它同时就是条遮住封面的画面高度，列表档是名称下方那条的厚度；
 * - 轨道**不透明**（alpha = 1）：网格档的条压在封面画面上，带 alpha 的轨道会透出底图；
 * - 端帽**方头**（[StrokeCap.Butt]，不是 M3 默认的 [StrokeCap.Round]）：圆头会在条的两端留出极小空白。
 *
 * 不可测部分（写明原因）：本仓库没有 compose-ui-test 基建（`androidTest` 只有一条冒烟用例），
 * 因此「条的位置」（网格档压在封面下缘 / 列表档在名称下方且左缘贴封面右缘）与「不占布局、无进度不绘制」
 * 无法在此测量——它们由两档调用点的布局与一个 `if (progress != null)` 保证，见实施证据。
 */
class EntryProgressBarTest {

    @Test
    fun `进度条高度是 6dp`() {
        assertEquals(6.dp, PROGRESS_BAR_HEIGHT)
    }

    @Test
    fun `轨道是不透明灰`() {
        assertEquals(1f, PROGRESS_TRACK_COLOR.alpha, 0f)
    }

    @Test
    fun `端帽是方头 不是 M3 默认的圆头`() {
        assertEquals(StrokeCap.Butt, PROGRESS_BAR_STROKE_CAP)
        assertNotEquals(StrokeCap.Round, PROGRESS_BAR_STROKE_CAP)
    }
}
