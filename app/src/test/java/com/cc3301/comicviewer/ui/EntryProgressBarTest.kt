package com.cc3301.comicviewer.ui

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 进度条样式常量（票 #92 需求 3）：条高、轨道不透明度、端帽方头。三者都抽成了常量，因此用可失败断言钉住——
 * - 条高 **6dp**：网格档它同时就是条遮住封面的画面高度，列表档是名称正下方那条的厚度；
 * - 轨道 = 主题 `onSurface` 的 **30% 不透明度**（维护者 2026-09-20 从渲染预览 4 档中选定）：固定灰度值在浅色主题
 *   偏亮、深色主题偏暗，跟主题走才能两边都合底色；本值 < 1 **有意为之**（允许轨道透出底图）；
 * - 端帽**方头**（[StrokeCap.Butt]，不是 M3 默认的 [StrokeCap.Round]）：圆头会在条的两端留出极小空白。
 *
 * 钉的是**常量值**，不覆盖接线（把 `trackColor = MaterialTheme.colorScheme…` 从 `EntryProgressBar` 里删掉时
 * 本用例不会变红）；接线由调用点义务保证。不可测部分（写明原因）：本仓库没有 compose-ui-test 基建
 * （`androidTest` 只有一条冒烟用例），因此「条的位置」（网格档压在封面下缘 / 列表档在名称正下方、
 * 左缘与名称左缘对齐、右缘与名称文本盒同一条线）无法在此测量——落地形式是两档各自的布局代码：
 * 列表档名称与条共用同一份 `fillMaxWidth()` 约束（`LIST_COVER_WIDTH` 只被封面的 `CoverSizing.OwnAspect` 读），
 * 网格档是封面盒内的 `Alignment.BottomCenter` 覆盖层。
 */
class EntryProgressBarTest {

    @Test
    fun `进度条高度是 6dp`() {
        assertEquals(6.dp, PROGRESS_BAR_HEIGHT)
    }

    @Test
    fun `轨道不透明度是 0_30`() {
        assertEquals(0.30f, PROGRESS_TRACK_ALPHA, 0f)
    }

    @Test
    fun `端帽是方头 不是 M3 默认的圆头`() {
        assertEquals(StrokeCap.Butt, PROGRESS_BAR_STROKE_CAP)
        assertNotEquals(StrokeCap.Round, PROGRESS_BAR_STROKE_CAP)
    }
}
